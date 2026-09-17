package com.vote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动预热服务
 * 将 MySQL 中的活动数据加载到 Redis，避免高并发下穿透到数据库
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityWarmUpService {

    private final VoteActivityMapper voteActivityMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /** 预热指定活动 */
    public void warmUpActivity(Long activityId) {
        VoteActivity activity = voteActivityMapper.selectById(activityId);
        if (activity == null) {
            log.warn("预热失败，活动不存在：activityId={}", activityId);
            return;
        }
        String key = RedisKeys.ACTIVITY_INFO + activityId;
        Map<String, String> map = new HashMap<>();
        map.put("id", String.valueOf(activity.getId()));
        map.put("activity_name", activity.getActivityName());
        map.put("start_time", String.valueOf(activity.getStartTime()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        map.put("end_time", String.valueOf(activity.getEndTime()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        map.put("status", String.valueOf(activity.getStatus()));

        // 状态/时间等每次都刷新；total_votes 只在首次写入（避免覆盖 Redis 中实时累加的票数）
        stringRedisTemplate.opsForHash().putAll(key, map);
        stringRedisTemplate.opsForHash().putIfAbsent(key, "total_votes",
                String.valueOf(activity.getTotalVotes() == null ? 0L : activity.getTotalVotes()));
        log.info("活动预热成功：activityId={}", activityId);
    }

    /** 预热所有进行中的活动 */
    public void warmUpAllActiveActivities() {
        LambdaQueryWrapper<VoteActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VoteActivity::getStatus, 1);
        List<VoteActivity> activities = voteActivityMapper.selectList(wrapper);
        activities.forEach(a -> warmUpActivity(a.getId()));
        log.info("批量预热完成，共 {} 个活动", activities.size());
    }

    /**
     * 活动 Hash 是否已存在于 Redis
     * <p>
     * Hash 是 Lua 脚本判断「活动能否投票」的唯一依据：Hash 缺失时脚本对
     * <b>所有</b>投票请求都返回 -1（活动未开始或已结束）。
     * 因此定时任务与启动流程都需要据此判断是否需要补预热。
     */
    public boolean isActivityCached(Long activityId) {
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(RedisKeys.ACTIVITY_INFO + activityId));
        } catch (Exception e) {
            log.warn("检查活动缓存是否存在失败: activityId={}", activityId, e);
            // 探测失败时保守地认为「已缓存」，避免因 Redis 抖动触发无谓的批量预热
            return true;
        }
    }
}
