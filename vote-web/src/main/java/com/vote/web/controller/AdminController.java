package com.vote.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.common.result.Result;
import com.vote.model.entity.VoteActivity;
import com.vote.model.entity.VoteBlacklist;
import com.vote.model.entity.VoteRecord;
import com.vote.model.entity.VoteTarget;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.model.mapper.VoteBlacklistMapper;
import com.vote.model.mapper.VoteRecordMapper;
import com.vote.model.mapper.VoteTargetMapper;
import com.vote.service.ActivityWarmUpService;
import com.vote.service.VoteStatsService;
import com.vote.service.cache.CacheWarmUpService;
import com.vote.service.rank.VoteRankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口：活动管理、目标管理、预热、黑名单、控制台统计
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Tag(name = "管理接口", description = "活动/目标/预热/黑名单/控制台统计")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final VoteActivityMapper voteActivityMapper;
    private final VoteTargetMapper voteTargetMapper;
    private final VoteBlacklistMapper voteBlacklistMapper;
    private final VoteRecordMapper voteRecordMapper;
    private final ActivityWarmUpService activityWarmUpService;
    private final CacheWarmUpService cacheWarmUpService;
    private final VoteRankService voteRankService;
    private final VoteStatsService voteStatsService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitAdmin rabbitAdmin;

    /** 创建活动 */
    @Operation(summary = "创建活动")
    @PostMapping("/activity")
    public Result<Long> createActivity(@RequestBody VoteActivity activity) {
        if (activity.getActivityName() == null || activity.getStartTime() == null || activity.getEndTime() == null) {
            return Result.error(400, "活动名称 / 开始时间 / 结束时间必填");
        }
        activity.setStatus(0);
        activity.setTotalVotes(0L);
        activity.setRemainVotes(0L);
        voteActivityMapper.insert(activity);
        return Result.success(activity.getId());
    }

    /** 添加投票目标 */
    @Operation(summary = "添加投票目标")
    @PostMapping("/activity/{activityId}/target")
    public Result<Boolean> addTarget(@PathVariable Long activityId, @RequestBody VoteTarget target) {
        target.setActivityId(activityId);
        voteTargetMapper.insert(target);
        return Result.success(true);
    }

    /** 预热活动（活动信息 + 排行榜 + TopN 缓存） */
    @Operation(summary = "预热活动")
    @PostMapping("/activity/{activityId}/warmup")
    public Result<Boolean> warmUp(@PathVariable Long activityId) {
        activityWarmUpService.warmUpActivity(activityId);
        // 预热排行榜：以目标列表初始化（0 票）
        List<VoteTarget> targets = voteTargetMapper.selectList(
                new LambdaQueryWrapper<VoteTarget>().eq(VoteTarget::getActivityId, activityId));
        Map<Long, Long> initVotes = new HashMap<>();
        targets.forEach(t -> initVotes.put(t.getId(), 0L));
        cacheWarmUpService.warmUpRanking(activityId, initVotes);
        cacheWarmUpService.warmUpTopNCache(activityId, 10);
        return Result.success(true);
    }

    /** 添加黑名单（USER / IP / DEVICE），同步写入 Redis */
    @Operation(summary = "添加黑名单")
    @PostMapping("/blacklist")
    public Result<Boolean> addBlacklist(@RequestBody VoteBlacklist blacklist) {
        if (blacklist.getTargetType() == null || blacklist.getTargetValue() == null) {
            return Result.error(400, "targetType / targetValue 必填");
        }
        blacklist.setTargetType(blacklist.getTargetType().toUpperCase());
        voteBlacklistMapper.insert(blacklist);
        // 同步写入 Redis，供 Lua 脚本校验
        String key = RedisKeys.BLACKLIST + blacklist.getTargetType() + ":" + blacklist.getTargetValue();
        if (blacklist.getExpireTime() != null) {
            long ttlSeconds = Duration.between(LocalDateTime.now(), blacklist.getExpireTime()).getSeconds();
            if (ttlSeconds > 0) {
                stringRedisTemplate.opsForValue().set(key, "1", ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
            }
        } else {
            stringRedisTemplate.opsForValue().set(key, "1");
        }
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

    /** 控制台仪表盘统计 */
    @Operation(summary = "控制台仪表盘统计")
    @GetMapping("/stats/dashboard")
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
