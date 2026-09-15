package com.vote.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.service.VoteLockService;
import com.vote.service.reconcile.VoteReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 票数对账定时任务
 * <p>
 * 定期比对数据库流水与 Redis 排行榜/计数的差异，发现漂移即告警。
 * 这是原项目<b>完全缺失</b>的一环：Redis 与数据库之间没有任何校验，一旦出现偏差
 * （Outbox 丢弃、落库异常、缓存被误清）就永久错误且无人察觉 ——
 * 实测数据中曾出现「MySQL 1044 票 / Redis 145 票」而系统毫无反应。
 * <p>
 * <b>关于自动修复：</b>默认关闭，只告警。开启后也只会在 Outbox 已排空时执行重建，
 * 因为重建是「以数据库为准覆盖 Redis」，若此时还有未落库的在途票，这些票会被抹掉。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteReconcileTask {

    private final VoteActivityMapper voteActivityMapper;
    private final VoteReconcileService voteReconcileService;
    private final VoteLockService voteLockService;

    /** 是否在发现漂移时自动重建缓存；默认关闭，需在配置中显式开启 */
    @Value("${app.reconcile.auto-repair:false}")
    private boolean autoRepair;

    /** 每 5 分钟对账一次进行中的活动 */
    @Scheduled(cron = "0 */5 * * * ?")
    public void reconcile() {
        // 多实例部署时只让一个实例执行，避免重复扫描
        voteLockService.executeWithLock(RedisKeys.RECONCILE_TASK_LOCK, 0L, () -> {
            doReconcile();
            return null;
        });
    }

    private void doReconcile() {
        List<VoteActivity> activities = voteActivityMapper.selectList(
                new LambdaQueryWrapper<VoteActivity>().eq(VoteActivity::getStatus, 1));
        if (activities.isEmpty()) {
            return;
        }
        for (VoteActivity activity : activities) {
            reconcileOne(activity.getId());
        }
    }

    private void reconcileOne(Long activityId) {
        try {
            VoteReconcileService.ReconcileReport report = voteReconcileService.check(activityId);
            if (report.consistent()) {
                log.debug("票数对账通过: {}", report.describe());
                return;
            }

            log.error("【对账告警】Redis 与数据库票数不一致，请排查: {}", report.describe());

            if (!autoRepair) {
                return;
            }
            long pending = voteReconcileService.pendingOutboxCount();
            if (pending > 0) {
                log.warn("对账自动修复已跳过：Outbox 中仍有 {} 条未落库消息，"
                        + "此时重建会丢掉这部分在途票，等其落库后下一轮对账会再次尝试", pending);
                return;
            }
            log.warn("对账自动修复：以数据库为准重建活动[{}]的缓存", activityId);
            voteReconcileService.forceRebuild(activityId);

        } catch (Exception e) {
            log.error("票数对账执行失败: activityId={}", activityId, e);
        }
    }
}
