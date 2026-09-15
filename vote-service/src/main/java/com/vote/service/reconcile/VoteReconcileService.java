package com.vote.service.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.VoteTarget;
import com.vote.model.mapper.VoteRecordMapper;
import com.vote.model.mapper.VoteTargetMapper;
import com.vote.service.ActivityWarmUpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 票数对账与缓存重建服务
 * <p>
 * <b>核心原则：数据库中的 {@code vote_record} 是票数的唯一真相，Redis 只是它的缓存。</b>
 * 早期实现把 Redis 当作真相、数据库当备份，且没有任何重建路径：
 * 一旦 Redis 丢数据或计数漂移，排行榜就永久错误且无人发现 ——
 * 实测数据中曾出现「MySQL 1044 票 / Redis 145 票」的撕裂状态。
 * <p>
 * 本服务提供三种操作，安全级别依次递增：
 * <ol>
 *   <li>{@link #check} —— 只读对账，比对差异并报告，不做任何修改；</li>
 *   <li>{@link #warmUp} —— 确保缓存存在。<b>只补齐缺失的部分，绝不覆盖已有数据</b>，
 *       可在任何时刻安全调用（包括活动进行中）；</li>
 *   <li>{@link #forceRebuild} —— 以数据库为准强制覆盖 Redis。<b>会丢弃尚未落库的在途票</b>，
 *       仅用于故障恢复，调用前需确认 Outbox 已排空。</li>
 * </ol>
 *
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteReconcileService {

    private final VoteRecordMapper voteRecordMapper;
    private final VoteTargetMapper voteTargetMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ActivityWarmUpService activityWarmUpService;

    /**
     * 对账报告
     *
     * @param activityId   活动 ID
     * @param dbTotal      数据库中该活动的有效票总数
     * @param redisTotal   Redis 活动 Hash 中的 total_votes
     * @param dbByTarget   数据库聚合出的各目标票数
     * @param redisByTarget Redis 排行榜中各目标的票数
     */
    public record ReconcileReport(
            Long activityId,
            long dbTotal,
            long redisTotal,
            Map<Long, Long> dbByTarget,
            Map<Long, Long> redisByTarget) {

        /** 计数是否一致 */
        public boolean consistent() {
            return dbTotal == redisTotal && Objects.equals(dbByTarget, redisByTarget);
        }

        /** 人类可读的差异描述 */
        public String describe() {
            if (consistent()) {
                return String.format("活动[%d] 票数一致，共 %d 票", activityId, dbTotal);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("活动[%d] 票数不一致：数据库总票数=%d，Redis总票数=%d", activityId, dbTotal, redisTotal));
            Set<Long> targets = new HashSet<>();
            targets.addAll(dbByTarget.keySet());
            targets.addAll(redisByTarget.keySet());
            for (Long targetId : targets) {
                long db = dbByTarget.getOrDefault(targetId, 0L);
                long redis = redisByTarget.getOrDefault(targetId, 0L);
                if (db != redis) {
                    sb.append(String.format("；目标[%d] 数据库=%d Redis=%d", targetId, db, redis));
                }
            }
            return sb.toString();
        }
    }

    /** 只读对账：比对 Redis 与数据库的票数差异，不修改任何数据 */
    public ReconcileReport check(Long activityId) {
        Map<Long, Long> dbByTarget = aggregateFromDb(activityId);
        long dbTotal = voteRecordMapper.countValidVotes(activityId);
        Map<Long, Long> redisByTarget = readRankFromRedis(activityId);
        long redisTotal = readRedisTotal(activityId);
        return new ReconcileReport(activityId, dbTotal, redisTotal, dbByTarget, redisByTarget);
    }

    /**
     * 确保缓存存在（安全：不覆盖任何已有数据）
     * <p>
     * 用于启动预热与「一键预热」接口。修复了两个问题：
     * <ul>
     *   <li>排行榜为空（Redis 被清空、全新环境）时，从数据库流水重建，而不是写成全 0；</li>
     *   <li>排行榜已有数据时原样保留，不再把真实票数清零。</li>
     * </ul>
     *
     * @return 是否执行了排行榜重建
     */
    public boolean warmUp(Long activityId) {
        // 活动 Hash 是 Lua 判断「能否投票」的唯一依据，缺失时必须补齐。
        // 已存在时不重查数据库，使本方法在定时任务中可低成本高频调用。
        if (!activityWarmUpService.isActivityCached(activityId)) {
            log.warn("活动[{}]的 Hash 不存在，从数据库装载（缺失会导致该活动完全无法投票）", activityId);
            activityWarmUpService.warmUpActivity(activityId);
        }

        String rankKey = RedisKeys.RANK + activityId;
        Long existing = stringRedisTemplate.opsForZSet().zCard(rankKey);
        if (existing != null && existing > 0) {
            log.debug("活动[{}]排行榜已存在 {} 个目标，跳过重建以免覆盖实时票数", activityId, existing);
            return false;
        }

        Map<Long, Long> dbByTarget = aggregateFromDb(activityId);
        writeRanking(activityId, dbByTarget);
        log.info("活动[{}]排行榜缺失，已按数据库流水重建，共 {} 个目标",
                activityId, dbByTarget.size());
        return true;
    }

    /**
     * 以数据库为准强制重建 Redis 的排行榜与总票数
     * <p>
     * <b>注意：这会丢弃尚未落库的在途票。</b>若 Outbox 中仍有未投递的消息，
     * 重建后这部分票数会从 Redis 中消失（数据库落库后需再次重建才会回来）。
     *
     * @return 重建前后的对账报告
     */
    public ReconcileReport forceRebuild(Long activityId) {
        long pending = pendingOutboxCount();
        if (pending > 0) {
            log.warn("活动[{}]强制重建时 Outbox 中仍有 {} 条未投递消息，"
                    + "这部分在途票将不在重建结果中，请在其落库后再次重建", activityId, pending);
        }

        ReconcileReport before = check(activityId);

        Map<Long, Long> dbByTarget = aggregateFromDb(activityId);
        writeRanking(activityId, dbByTarget);

        // 以数据库为准覆盖总票数。预热接口刻意不这么做，
        // 但强制重建的语义就是「修复漂移」，必须覆盖。
        stringRedisTemplate.opsForHash().put(
                RedisKeys.ACTIVITY_INFO + activityId, "total_votes", String.valueOf(before.dbTotal()));

        ReconcileReport after = check(activityId);
        log.warn("活动[{}]缓存已强制重建: 重建前[{}] 重建后[{}]",
                activityId, before.describe(), after.describe());
        return after;
    }

    private Map<Long, Long> aggregateFromDb(Long activityId) {
        Map<Long, Long> byTarget = new LinkedHashMap<>();
        // 先放入目标全集（含 0 票目标），保证榜单不因某目标暂时 0 票而消失
        List<VoteTarget> targets = voteTargetMapper.selectList(
                new LambdaQueryWrapper<VoteTarget>().eq(VoteTarget::getActivityId, activityId));
        for (VoteTarget t : targets) {
            byTarget.put(t.getId(), 0L);
        }
        List<Map<String, Object>> rows = voteRecordMapper.countVotesGroupByTarget(activityId);
        for (Map<String, Object> row : rows) {
            Long targetId = toLong(row.get("targetId"));
            Long count = toLong(row.get("voteCount"));
            if (targetId != null) {
                byTarget.put(targetId, count == null ? 0L : count);
            }
        }
        return byTarget;
    }

    private void writeRanking(Long activityId, Map<Long, Long> votesByTarget) {
        String rankKey = RedisKeys.RANK + activityId;
        // 先整体删除再写入：清掉已删除目标留下的历史成员，避免出现「幽灵选手」
        stringRedisTemplate.delete(rankKey);
        if (votesByTarget.isEmpty()) {
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (Map.Entry<Long, Long> entry : votesByTarget.entrySet()) {
            tuples.add(ZSetOperations.TypedTuple.of(
                    String.valueOf(entry.getKey()), entry.getValue().doubleValue()));
        }
        // 一次性写入，避免逐个 ZADD 产生 N 次网络往返
        stringRedisTemplate.opsForZSet().add(rankKey, tuples);
    }

    private Map<Long, Long> readRankFromRedis(Long activityId) {
        Map<Long, Long> result = new HashMap<>();
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .rangeWithScores(RedisKeys.RANK + activityId, 0, -1);
        if (tuples == null) {
            return result;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            try {
                Long targetId = Long.valueOf(String.valueOf(tuple.getValue()));
                result.put(targetId, tuple.getScore() == null ? 0L : tuple.getScore().longValue());
            } catch (NumberFormatException e) {
                log.warn("排行榜中存在非法成员，已跳过: activityId={}, member={}", activityId, tuple.getValue());
            }
        }
        return result;
    }

    private long readRedisTotal(Long activityId) {
        Object v = stringRedisTemplate.opsForHash().get(RedisKeys.ACTIVITY_INFO + activityId, "total_votes");
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Outbox 中待投递 + 投递中的消息数，用于判断是否存在未落库的在途票 */
    public long pendingOutboxCount() {
        try {
            Long pending = stringRedisTemplate.opsForList().size(RedisKeys.OUTBOX_QUEUE);
            Long processing = stringRedisTemplate.opsForList().size(RedisKeys.OUTBOX_PROCESSING_QUEUE);
            return (pending == null ? 0L : pending) + (processing == null ? 0L : processing);
        } catch (Exception e) {
            log.warn("读取 Outbox 积压数失败", e);
            return 0L;
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
