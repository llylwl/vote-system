package com.vote.web.controller;

import com.vote.annotation.RateLimit;
import com.vote.common.constant.RedisKeys;
import com.vote.common.result.Result;
import com.vote.model.dto.VoteRequest;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.service.VoteService;
import com.vote.service.VoteStatsService;
import com.vote.service.cache.ICacheService;
import com.vote.service.rank.VoteRankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投票核心接口
 * @author hzp
 * @since 2026-9-15
 */
@Tag(name = "投票核心接口", description = "投票、查询活动、排行榜")
@RestController
@RequestMapping("/api")
public class VoteController {

    private final VoteService voteService;
    private final VoteRankService voteRankService;
    private final VoteActivityMapper voteActivityMapper;
    private final ICacheService cacheService;
    private final VoteStatsService voteStatsService;

    public VoteController(VoteService voteService,
                          VoteRankService voteRankService,
                          VoteActivityMapper voteActivityMapper,
                          @Qualifier("mutexCacheService") ICacheService cacheService,
                          VoteStatsService voteStatsService) {
        this.voteService = voteService;
        this.voteRankService = voteRankService;
        this.voteActivityMapper = voteActivityMapper;
        this.cacheService = cacheService;
        this.voteStatsService = voteStatsService;
    }

    /**
     * 投票
     * 限流：默认按客户端 IP，1 秒内最多 20 次
     */
    @Operation(summary = "投票")
    @PostMapping("/vote")
    @RateLimit(limit = 20, timeWindow = 1000, message = "投票过于频繁，请稍后再试")
    public Result<Long> vote(@RequestBody VoteRequest request, HttpServletRequest httpRequest) {
        if (request.getActivityId() == null || request.getTargetId() == null || request.getUserId() == null) {
            return Result.error(400, "参数不完整：activityId / targetId / userId 必填");
        }
        String userIp = getClientIp(httpRequest);
        Long result = voteService.vote(request, userIp);
        switch (result.intValue()) {
            case 1:
                return Result.success(1L);
            case -1:
                voteStatsService.incr(VoteStatsService.TYPE_INVALID);
                return Result.error(4001, "活动未开始或已结束");
            case -2:
                voteStatsService.incr(VoteStatsService.TYPE_BLACKLIST);
                return Result.error(4002, "您已被限制投票");
            case -3:
                voteStatsService.incr(VoteStatsService.TYPE_DUPLICATE);
                return Result.error(4003, "您今日已投过票");
            default:
                return Result.error(5000, "投票系统繁忙，请稍后再试");
        }
    }

    /** 查询活动详情（互斥锁缓存防击穿） */
    @Operation(summary = "查询活动详情")
    @GetMapping("/activity/{activityId}")
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
            m.put("totalVotes", voteRankService.getActivityTotal(activityId));
            return m;
        });
        if (data == null) {
            return Result.error(404, "活动不存在");
        }
        return Result.success(data);
    }

    /** TopN 排行榜 */
    @Operation(summary = "TopN 排行榜")
    @GetMapping("/activity/{activityId}/topn")
    public Result<List<Map<String, Object>>> getTopN(@PathVariable Long activityId,
                                                     @RequestParam(defaultValue = "10") int n) {
        return Result.success(voteRankService.getTopN(activityId, n));
    }

    /** 指定目标的排名与票数 */
    @Operation(summary = "指定目标排名")
    @GetMapping("/activity/{activityId}/rank/{targetId}")
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
    public Result<List<Map<String, Object>>> getRanking(@PathVariable Long activityId,
                                                        @RequestParam(defaultValue = "0") long start,
                                                        @RequestParam(defaultValue = "49") long end) {
        return Result.success(voteRankService.getActivityRanking(activityId, start, end));
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
