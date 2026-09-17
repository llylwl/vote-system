package com.vote.web.controller;

import com.vote.annotation.RateLimit;
import com.vote.common.config.SecurityProperties;
import com.vote.common.constant.RedisKeys;
import com.vote.common.exception.BusinessException;
import com.vote.common.result.ErrorCode;
import com.vote.common.result.Result;
import com.vote.model.dto.VoteRequest;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.security.UserContext;
import com.vote.service.VoteService;
import com.vote.service.VoteStatsService;
import com.vote.service.cache.ICacheService;
import com.vote.service.rank.VoteRankService;
import com.vote.support.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投票核心接口
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Tag(name = "投票核心接口", description = "投票、查询活动、排行榜")
@RestController
@RequestMapping("/api")
public class VoteController {

    private final VoteService voteService;
    private final VoteRankService voteRankService;
    private final VoteActivityMapper voteActivityMapper;
    private final ICacheService cacheService;
    private final VoteStatsService voteStatsService;
    private final ClientIpResolver clientIpResolver;
    private final SecurityProperties securityProperties;

    public VoteController(VoteService voteService,
                          VoteRankService voteRankService,
                          VoteActivityMapper voteActivityMapper,
                          @Qualifier("mutexCacheService") ICacheService cacheService,
                          VoteStatsService voteStatsService,
                          ClientIpResolver clientIpResolver,
                          SecurityProperties securityProperties) {
        this.voteService = voteService;
        this.voteRankService = voteRankService;
        this.voteActivityMapper = voteActivityMapper;
        this.cacheService = cacheService;
        this.voteStatsService = voteStatsService;
        this.clientIpResolver = clientIpResolver;
        this.securityProperties = securityProperties;
    }

    /**
     * 投票
     * <p>
     * 限流：按客户端可信 IP，1 秒内最多 20 次。
     * 参数校验由 {@code @Valid} 完成，失败时抛出的异常由全局处理器统一转换为 HTTP 400。
     * <p>
     * Lua 脚本的返回码在此映射为统一错误码，并抛出业务异常 ——
     * 这样响应会带上真实的 HTTP 状态码（如重复投票返回 409），而不是一律 200。
     */
    @Operation(summary = "投票")
    @PostMapping("/vote")
    @RateLimit(limit = 20, timeWindow = 1000, message = "投票过于频繁，请稍后再试")
    public Result<Long> vote(@Valid @RequestBody VoteRequest request, HttpServletRequest httpRequest) {
        // 投票人身份以服务端判定为准，覆盖请求体中的值
        request.setUserId(resolveVoterId(request));

        // 使用可信 IP：只有来自可信代理的 X-Forwarded-For 才会被采信。
        // 该值会作为 IP 黑名单与 IP 限流的依据，若可被伪造则两者都可被绕过。
        String userIp = clientIpResolver.resolve(httpRequest);
        Long result = voteService.vote(request, userIp);
        return switch (result.intValue()) {
            case 1 -> Result.success(1L);
            case -1 -> {
                voteStatsService.incr(VoteStatsService.TYPE_INVALID);
                throw new BusinessException(ErrorCode.ACTIVITY_NOT_VOTABLE);
            }
            case -2 -> {
                voteStatsService.incr(VoteStatsService.TYPE_BLACKLIST);
                throw new BusinessException(ErrorCode.BLACKLISTED);
            }
            case -3 -> {
                voteStatsService.incr(VoteStatsService.TYPE_DUPLICATE);
                throw new BusinessException(ErrorCode.DUPLICATE_VOTE);
            }
            default -> throw new BusinessException(ErrorCode.VOTE_SYSTEM_BUSY);
        };
    }

    /**
     * 确定投票人身份
     * <p>
     * <ul>
     *   <li><b>已登录</b>：一律使用令牌中的用户 ID，请求体中的 userId 被忽略。
     *       否则登录用户可以在请求体里填别人的 ID 冒名投票 ——
     *       "每天一票"、黑名单、投票记录会全部算到别人头上。</li>
     *   <li><b>未登录</b>：生产环境拒绝（401）；
     *       开发环境允许使用请求体中的 userId，方便本地调试与压测。</li>
     * </ul>
     */
    private Long resolveVoterId(VoteRequest request) {
        Long authenticatedUserId = UserContext.currentUserId();
        if (authenticatedUserId != null) {
            if (request.getUserId() != null && !authenticatedUserId.equals(request.getUserId())) {
                log.warn("请求体中的 userId 与登录用户不一致，已按登录用户处理: token={}, body={}",
                        authenticatedUserId, request.getUserId());
            }
            return authenticatedUserId;
        }

        if (securityProperties.isRequireLoginToVote()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 必填");
        }
        return request.getUserId();
    }

    /**
     * 查询活动详情（互斥锁缓存防击穿）
     * <p>
     * <b>注意缓存里放的是什么：</b>只放活动的静态元数据。实时票数刻意<b>不</b>进缓存 ——
     * 早期实现把 Redis 的实时票数一起塞进了 1 小时 TTL 的缓存，而项目里没有任何一处
     * 调用过缓存失效方法，导致用户在整个活动期间看到的都是「活动开始那一刻的票数」。
     */
    @Operation(summary = "查询活动详情")
    @GetMapping("/activity/{activityId}")
    @RateLimit(limit = 60, timeWindow = 1000, message = "请求过于频繁，请稍后再试")
    public Result<Map<String, Object>> getActivity(@PathVariable Long activityId) {
        Map<String, Object> data = cacheService.getWithProtection(RedisKeys.ACTIVITY_DETAIL + activityId, () -> {
            VoteActivity a = voteActivityMapper.selectById(activityId);
            if (a == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("activityName", a.getActivityName());
            m.put("activityDesc", a.getActivityDesc());
            m.put("startTime", String.valueOf(a.getStartTime()));
            m.put("endTime", String.valueOf(a.getEndTime()));
            m.put("status", a.getStatus());
            return m;
        });
        if (data == null) {
            // 抛出业务异常，由全局处理器转换为 HTTP 404
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在: " + activityId);
        }
        // 实时票数每次单独读取，保证用户看到的是当前值
        data.put("totalVotes", voteRankService.getActivityTotal(activityId));
        return Result.success(data);
    }

    /** TopN 排行榜 */
    @Operation(summary = "TopN 排行榜")
    @GetMapping("/activity/{activityId}/topn")
    @RateLimit(limit = 60, timeWindow = 1000, message = "请求过于频繁，请稍后再试")
    public Result<List<Map<String, Object>>> getTopN(@PathVariable Long activityId,
                                                     @RequestParam(defaultValue = "10") int n) {
        return Result.success(voteRankService.getTopN(activityId, n));
    }

    /** 指定目标的排名与票数 */
    @Operation(summary = "指定目标排名")
    @GetMapping("/activity/{activityId}/rank/{targetId}")
    @RateLimit(limit = 60, timeWindow = 1000, message = "请求过于频繁，请稍后再试")
    public Result<Map<String, Object>> getRank(@PathVariable Long activityId,
                                               @PathVariable Long targetId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("targetId", targetId);
        item.put("rank", voteRankService.getRank(activityId, targetId));
        item.put("voteCount", voteRankService.getVoteCount(activityId, targetId));
        return Result.success(item);
    }

    /** 指定范围排行榜 */
    @Operation(summary = "排行榜（分页）")
    @GetMapping("/activity/{activityId}/ranking")
    @RateLimit(limit = 30, timeWindow = 1000, message = "请求过于频繁，请稍后再试")
    public Result<List<Map<String, Object>>> getRanking(@PathVariable Long activityId,
                                                        @RequestParam(defaultValue = "0") long start,
                                                        @RequestParam(defaultValue = "49") long end) {
        return Result.success(voteRankService.getActivityRanking(activityId, start, end));
    }

}
