package com.vote.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.service.ActivityWarmUpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动状态定时任务
 * 每分钟刷新活动状态（0-未开始 / 1-进行中 / 2-已结束），进入进行中的活动自动预热
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityStatusTask {

    private final VoteActivityMapper voteActivityMapper;
    private final ActivityWarmUpService activityWarmUpService;

    @Scheduled(cron = "0 * * * * ?")
    public void refreshActivityStatus() {
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
            if (activity.getStatus() == null || activity.getStatus() != newStatus) {
                activity.setStatus(newStatus);
                voteActivityMapper.updateById(activity);
                log.info("活动状态刷新: id={}, status={}", activity.getId(), newStatus);
                if (newStatus == 1) {
                    // 进入进行中，预热活动数据到 Redis
                    activityWarmUpService.warmUpActivity(activity.getId());
                }
            }
        }
    }
}
