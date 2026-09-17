package com.vote.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.annotation.RateLimit;
import com.vote.annotation.RequireAdmin;
import com.vote.common.constant.RedisKeys;
import com.vote.common.exception.BusinessException;
import com.vote.common.result.ErrorCode;
import com.vote.common.result.Result;
import com.vote.model.dto.ActivityCreateRequest;
import com.vote.model.dto.BlacklistCreateRequest;
import com.vote.model.dto.TargetCreateRequest;
import com.vote.model.entity.VoteActivity;
import com.vote.model.entity.VoteBlacklist;
import com.vote.model.entity.VoteRecord;
import com.vote.model.entity.VoteTarget;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.model.mapper.VoteBlacklistMapper;
import com.vote.model.mapper.VoteRecordMapper;
import com.vote.model.mapper.VoteTargetMapper;
import com.vote.service.VoteStatsService;
import com.vote.service.rank.VoteRankService;
import com.vote.service.reconcile.VoteReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口：活动管理、目标管理、预热、黑名单、控制台统计
 * <p>
 * <b>类级 {@link RequireAdmin}</b> 对本 Controller 的所有接口生效，
 * 由 {@code AuthInterceptor} 统一校验：未登录返回 401，非管理员返回 403。
 * 用类级注解而非逐个方法标注，是为了避免将来新增接口时忘记加权限 ——
 * 漏加一个写接口（比如删除黑名单）就可能让任何人操作生产数据。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Tag(name = "管理接口", description = "活动/目标/预热/黑名单/控制台统计（需要管理员权限）")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireAdmin
public class AdminController {

    private final VoteActivityMapper voteActivityMapper;
    private final VoteTargetMapper voteTargetMapper;
    private final VoteBlacklistMapper voteBlacklistMapper;
    private final VoteRecordMapper voteRecordMapper;
    private final VoteReconcileService voteReconcileService;
    private final VoteRankService voteRankService;
    private final VoteStatsService voteStatsService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitAdmin rabbitAdmin;

    /** 创建活动 */
    @Operation(summary = "创建活动")
    @PostMapping("/activity")
    public Result<Long> createActivity(@Valid @RequestBody ActivityCreateRequest request) {
        // 字段级校验由 @Valid 完成，跨字段的业务规则在此判断
        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间必须早于结束时间");
        }

        VoteActivity activity = new VoteActivity();
        activity.setActivityName(request.getActivityName());
        activity.setActivityDesc(request.getActivityDesc());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setStatus(0);
        activity.setTotalVotes(0L);
        activity.setRemainVotes(0L);
        voteActivityMapper.insert(activity);

        // 刻意不在此处初始化排行榜 ZSet：
        // 早期实现的预热逻辑会把所有目标写成 0 票，对进行中的活动等同于清空榜单。
        // 新活动的缓存由定时任务或 /warmup 接口按需创建。
        return Result.success(activity.getId());
    }

    /** 添加投票目标 */
    @Operation(summary = "添加投票目标")
    @PostMapping("/activity/{activityId}/target")
    public Result<Boolean> addTarget(@PathVariable Long activityId,
                                     @Valid @RequestBody TargetCreateRequest request) {
        // 校验活动存在：否则会创建出指向不存在活动的孤儿目标，
        // 其 ID 一旦被客户端提交投票，就会污染排行榜（出现幽灵成员）
        if (voteActivityMapper.selectById(activityId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动不存在: " + activityId);
        }
        VoteTarget target = new VoteTarget();
        target.setActivityId(activityId);
        target.setTargetName(request.getTargetName());
        target.setTargetDesc(request.getTargetDesc());
        voteTargetMapper.insert(target);
        return Result.success(true);
    }

    /**
     * 预热活动缓存（安全，活动进行中也可调用）
     * <p>
     * 确保活动 Hash 存在；排行榜缺失时从数据库流水重建，已有数据则原样保留。
     * <p>
     * <b>安全说明：</b>早期实现把排行榜一律按「0 票」初始化，对进行中的活动调用一次
     * 就会把真实票数全部清零，且数据库中没有可恢复的票数字段，只能重放全部流水。
     *
     * @return rebuilt=true 表示排行榜此前缺失并已重建；false 表示已有数据、未做改动
     */
    @Operation(summary = "预热活动缓存（安全，不覆盖已有票数）")
    @PostMapping("/activity/{activityId}/warmup")
    public Result<Map<String, Object>> warmUp(@PathVariable Long activityId) {
        boolean rebuilt = voteReconcileService.warmUp(activityId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activityId);
        data.put("rebuilt", rebuilt);
        data.put("message", rebuilt ? "排行榜缺失，已从数据库流水重建" : "排行榜已存在，未做改动");
        return Result.success(data);
    }

    /**
     * 强制以数据库为准重建缓存（故障恢复用）
     * <p>
     * <b>会丢弃尚未落库的在途票</b>，请在 Outbox 排空后使用。
     */
    @Operation(summary = "强制重建活动缓存（以数据库为准，会丢弃在途票）")
    @PostMapping("/activity/{activityId}/rebuild-cache")
    public Result<Map<String, Object>> rebuildCache(@PathVariable Long activityId) {
        VoteReconcileService.ReconcileReport report = voteReconcileService.forceRebuild(activityId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", report.describe());
        data.put("dbTotal", report.dbTotal());
        data.put("redisTotal", report.redisTotal());
        return Result.success(data);
    }

    /** 票数对账：只读比对数据库与 Redis 的差异，不修改任何数据 */
    @Operation(summary = "票数对账（只读）")
    @GetMapping("/activity/{activityId}/reconcile")
    public Result<Map<String, Object>> reconcile(@PathVariable Long activityId) {
        VoteReconcileService.ReconcileReport report = voteReconcileService.check(activityId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("consistent", report.consistent());
        data.put("summary", report.describe());
        data.put("dbTotal", report.dbTotal());
        data.put("redisTotal", report.redisTotal());
        data.put("dbByTarget", report.dbByTarget());
        data.put("redisByTarget", report.redisByTarget());
        return Result.success(data);
    }

    /** 添加黑名单（USER / IP / DEVICE），同步写入 Redis */
    @Operation(summary = "添加黑名单")
    @PostMapping("/blacklist")
    public Result<Boolean> addBlacklist(@Valid @RequestBody BlacklistCreateRequest request) {
        LocalDateTime expireTime = request.getExpireTime();
        if (expireTime != null && !expireTime.isAfter(LocalDateTime.now())) {
            // 过期时间已在过去时，原实现会插入数据库但不写 Redis，
            // 结果黑名单在列表里看得见、实际却不生效，属于静默失效
            throw new BusinessException(ErrorCode.BAD_REQUEST, "过期时间必须晚于当前时间");
        }

        String targetType = request.getTargetType().toUpperCase();
        VoteBlacklist blacklist = new VoteBlacklist();
        blacklist.setTargetType(targetType);
        blacklist.setTargetValue(request.getTargetValue());
        blacklist.setReason(request.getReason());
        blacklist.setExpireTime(expireTime);
        voteBlacklistMapper.insert(blacklist);

        // 同步写入 Redis，供 Lua 脚本校验
        String key = RedisKeys.BLACKLIST + targetType + ":" + request.getTargetValue();
        if (expireTime != null) {
            long ttlSeconds = Duration.between(LocalDateTime.now(), expireTime).getSeconds();
            stringRedisTemplate.opsForValue().set(key, "1", ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            stringRedisTemplate.opsForValue().set(key, "1");
        }
        log.info("已添加黑名单: type={}, value={}, expireTime={}", targetType, request.getTargetValue(), expireTime);
        return Result.success(true);
    }

    /** 删除黑名单 */
    @Operation(summary = "删除黑名单")
    @DeleteMapping("/blacklist/{id}")
    public Result<Boolean> deleteBlacklist(@PathVariable Long id) {
        VoteBlacklist blacklist = voteBlacklistMapper.selectById(id);
        if (blacklist != null) {
            stringRedisTemplate.delete(RedisKeys.BLACKLIST + blacklist.getTargetType() + ":" + blacklist.getTargetValue());
            voteBlacklistMapper.deleteById(id);
        }
        return Result.success(true);
    }

    /** 活动列表 */
    @Operation(summary = "活动列表")
    @GetMapping("/activity/list")
    public Result<List<VoteActivity>> listActivities() {
        return Result.success(voteActivityMapper.selectList(
                new LambdaQueryWrapper<VoteActivity>().orderByDesc(VoteActivity::getId)));
    }

    /** 活动排行榜（完整） */
    @Operation(summary = "活动完整排行榜")
    @GetMapping("/activity/{activityId}/ranking")
    public Result<List<Map<String, Object>>> ranking(@PathVariable Long activityId) {
        return Result.success(voteRankService.getActivityRanking(activityId, 0, 99));
    }

    /** 黑名单列表 */
    @Operation(summary = "黑名单列表")
    @GetMapping("/blacklist/list")
    public Result<List<VoteBlacklist>> listBlacklist() {
        return Result.success(voteBlacklistMapper.selectList(
                new LambdaQueryWrapper<VoteBlacklist>().orderByDesc(VoteBlacklist::getId)));
    }

    /** 活动目标列表（含实时票数与排名） */
    @Operation(summary = "活动目标列表")
    @GetMapping("/activity/{activityId}/targets")
    public Result<List<Map<String, Object>>> listTargets(@PathVariable Long activityId) {
        List<VoteTarget> targets = voteTargetMapper.selectList(
                new LambdaQueryWrapper<VoteTarget>()
                        .eq(VoteTarget::getActivityId, activityId)
                        .orderByAsc(VoteTarget::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (VoteTarget t : targets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("targetName", t.getTargetName());
            m.put("targetDesc", t.getTargetDesc());
            m.put("voteCount", voteRankService.getVoteCount(activityId, t.getId()));
            m.put("rank", voteRankService.getRank(activityId, t.getId()));
            result.add(m);
        }
        // 按实时票数降序
        result.sort((a, b) -> Long.compare((Long) b.get("voteCount"), (Long) a.get("voteCount")));
        return Result.success(result);
    }

    /** 控制台仪表盘统计（聚合多个数据源，限流收紧以防被反复刷新打爆） */
    @Operation(summary = "控制台仪表盘统计")
    @GetMapping("/stats/dashboard")
    @RateLimit(limit = 10, timeWindow = 1000, message = "刷新过于频繁，请稍后再试")
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("now", LocalDateTime.now().toString());

        // Redis 连通性
        boolean redisConnected = false;
        try {
            redisConnected = "PONG".equalsIgnoreCase(String.valueOf(
                    stringRedisTemplate.getConnectionFactory().getConnection().ping()));
        } catch (Exception e) {
            log.warn("Redis ping 失败", e);
        }
        data.put("redisConnected", redisConnected);

        // RabbitMQ 队列状态
        long mqQueueDepth = -1, mqConsumers = 0;
        boolean mqConnected = false;
        try {
            var info = rabbitAdmin.getQueueInfo(com.vote.config.RabbitMQConfig.VOTE_MAIN_QUEUE);
            if (info != null) {
                mqConnected = true;
                mqQueueDepth = info.getMessageCount();
                mqConsumers = info.getConsumerCount();
            }
        } catch (Exception e) {
            log.warn("RabbitMQ 队列信息获取失败", e);
        }
        data.put("mqConnected", mqConnected);
        data.put("mqQueueDepth", mqQueueDepth);
        data.put("mqConsumers", mqConsumers);

        // 数据规模
        Long activityCount = voteActivityMapper.selectCount(null);
        Long targetCount = voteTargetMapper.selectCount(null);
        Long blacklistCount = voteBlacklistMapper.selectCount(null);
        data.put("activityCount", activityCount == null ? 0 : activityCount);
        data.put("targetCount", targetCount == null ? 0 : targetCount);
        data.put("blacklistCount", blacklistCount == null ? 0 : blacklistCount);

        // 实时总票数（Redis 各活动 total_votes 求和）
        long totalVotes = 0;
        List<VoteActivity> activities = voteActivityMapper.selectList(null);
        for (VoteActivity a : activities) {
            totalVotes += voteRankService.getActivityTotal(a.getId());
        }
        data.put("totalVotes", totalVotes);

        // 今日入库票数（DB）
        Long todayVotesDb = voteRecordMapper.selectCount(new LambdaQueryWrapper<VoteRecord>()
                .ge(VoteRecord::getVoteTime, LocalDate.now().atStartOfDay()));
        data.put("todayVotesDb", todayVotesDb == null ? 0 : todayVotesDb);

        // 今日防刷拦截统计
        data.put("stats", voteStatsService.getTodayStats());
        return Result.success(data);
    }
}
