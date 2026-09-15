package com.vote.service.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.model.entity.VoteActivity;
import com.vote.model.mapper.VoteActivityMapper;
import com.vote.service.reconcile.VoteReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动预热：把进行中活动的缓存补齐
 * <p>
 * 为什么需要在启动时做：
 * <ul>
 *   <li>活动 Hash 缺失时 Lua 对所有投票返回 -1，活动完全不可投。若只依赖每分钟执行一次的
 *       定时任务，新部署或 Redis 重启后最多有一分钟的空窗期，期间投票全部失败；</li>
 *   <li>Redis 被清空（容器重建、FLUSHALL）后同样需要立即重建，而不是等下一次状态跳变。</li>
 * </ul>
 * 本操作是安全的：缓存已存在时不会覆盖任何数据，只补齐缺失的部分。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmUpRunner implements ApplicationRunner {

    private final VoteActivityMapper voteActivityMapper;
    private final VoteReconcileService voteReconcileService;

    @Override
    public void run(ApplicationArguments args) {
        List<VoteActivity> activities = voteActivityMapper.selectList(
                new LambdaQueryWrapper<VoteActivity>().eq(VoteActivity::getStatus, 1));
        if (activities.isEmpty()) {
            log.info("启动预热：当前无进行中的活动");
            return;
        }
        for (VoteActivity activity : activities) {
            try {
                voteReconcileService.warmUp(activity.getId());
            } catch (Exception e) {
                // 单个活动预热失败不应阻止应用启动：定时任务随后会重试
                log.error("启动预热失败，稍后由定时任务重试: activityId={}", activity.getId(), e);
            }
        }
        log.info("启动预热完成，共处理 {} 个进行中的活动", activities.size());
    }
}
