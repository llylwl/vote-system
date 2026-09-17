package com.vote.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.service.ActivityWarmUpService;
import com.vote.service.VoteLockService;
import com.vote.service.reconcile.VoteReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动状态定时任务
 * 每分钟刷新活动状态（0-未开始 / 1-进行中 / 2-已结束），并确保进行中活动的缓存存在
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityStatusTask {

    private final VoteActivityMapper voteActivityMapper;
    private final ActivityWarmUpService activityWarmUpService;
    private final VoteReconcileService voteReconcileService;
    private final VoteLockService voteLockService;

    @Scheduled(cron = "0 * * * * ?")
    public void refreshActivityStatus() {
        // 多实例部署时用分布式锁保证同一分钟只有一个实例执行，
        // 否则 N 个实例会并发扫表、并发更新状态、并发预热
        voteLockService.executeWithLock(RedisKeys.ACTIVITY_STATUS_TASK_LOCK, 0L, () -> {
            doRefresh();
            return null;
        });
    }

    private void doRefresh() {
        List<VoteActivity> list = voteActivityMapper.selectList(
                new LambdaQueryWrapper<VoteActivity>()
                        .in(VoteActivity::getStatus, 0, 1));
        LocalDateTime now = LocalDateTime.now();
        for (VoteActivity activity : list) {
            int newStatus;
            if (now.isBefore(activity.getStartTime())) {
                newStatus = 0;
            } else if (now.isAfter(activity.getEndTime())) {
                newStatus = 2;
            } else {
                newStatus = 1;
            }

            boolean statusChanged = activity.getStatus() == null || activity.getStatus() != newStatus;
            if (statusChanged) {
                activity.setStatus(newStatus);
                voteActivityMapper.updateById(activity);
                log.info("活动状态刷新: id={}, status={}", activity.getId(), newStatus);
            }

            if (newStatus == 1) {
                if (statusChanged) {
                    // 状态刚变为进行中：刷新 Hash 中的状态与时间字段
                    activityWarmUpService.warmUpActivity(activity.getId());
                }
                // 自愈：Hash 或排行榜缺失时补齐，已存在则不覆盖任何数据。
                //
                // 这一步是必需的 —— 原实现只在状态发生「跳变」时预热，
                // 而种子数据把活动直接插成 status=1，首次启动时状态不会跳变，
                // 于是 Redis 中始终没有活动 Hash，Lua 对所有投票都返回 -1，
                // 整个活动完全无法投票，只能靠人工点「一键预热」才能恢复。
                voteReconcileService.warmUp(activity.getId());
            }
        }
    }
}
