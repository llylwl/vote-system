package com.vote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.common.util.DayUtils;
import com.vote.model.entity.VoteRecord;
import com.vote.model.mapper.VoteRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 投票持久化服务
 * <p>
 * 幂等由两层保证，且有明确的优先级：
 * <ol>
 *   <li><b>数据库唯一索引</b>（{@code uk_activity_user_date}）—— 真正的兜底，不依赖任何外部组件；</li>
 *   <li>Redis 幂等标记 —— 仅为减轻数据库压力的快速路径。</li>
 * </ol>
 * <p>
 * <b>写入顺序很关键</b>：先落库、成功才算数。若落库失败必须撤销幂等标记并把异常抛出，
 * 让 MQ 重试。早期实现先写标记再落库、且落库异常只打日志不抛出，消息会被 ACK，
 * 而 Redis 侧已经计过票 —— 结果是永久丢票且无人察觉。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VotePersistService {

    private final StringRedisTemplate redisTemplate;
    private final VoteRecordMapper voteRecordMapper;
    private final ObjectMapper objectMapper;

    /** 幂等标记在自然日 TTL 基础上多留的缓冲（秒），覆盖跨零点延迟到达的消息 */
    private static final long IDEMPOTENT_TTL_BUFFER_SECONDS = 3600L;

    /** 单条消息的落库结果，决定消费者是 ACK、重试还是转死信 */
    public enum PersistResult {
        /** 落库成功 */
        INSERTED,
        /** 幂等命中（重复投票），视为成功 */
        DUPLICATE,
        /** 消息体无法解析，重试无意义，应直接进死信队列 */
        MALFORMED
    }

    /**
     * 创建单条投票记录（含幂等校验）
     * <p>
     * 同一用户、同一活动、同一<b>自然日</b>只能投一次。
     *
     * @return 落库结果；遇到瞬时故障时抛出异常，由调用方决定重试
     */
    public PersistResult createVoteRecord(String payloadJson) {
        VoteRecord record = parsePayload(payloadJson);
        if (record == null) {
            log.error("投票消息解析失败，将转入死信队列: {}", payloadJson);
            return PersistResult.MALFORMED;
        }

        // 幂等 Key 的日期取自【投票时刻】而非【消费时刻】：
        // 消息可能因 MQ 积压或重试延迟到次日才被消费，若用消费时刻，
        // Redis 幂等键会记在 Day2 而数据库唯一索引算在 Day1，两层校验不在同一天上。
        String dateStr = DayUtils.isoOf(record.getVoteTime());
        String idempotentKey = String.format(RedisKeys.IDEMPOTENT + "%s:%s:%s",
                record.getActivityId(), record.getUserId(), dateStr);

        // 1. 幂等预检：快速路径，避免重复消息打到数据库
        if (Boolean.FALSE.equals(tryMarkIdempotent(idempotentKey))) {
            log.debug("幂等预检拦截重复消息: userId={}, activityId={}, date={}",
                    record.getUserId(), record.getActivityId(), dateStr);
            return PersistResult.DUPLICATE;
        }

        // 2. 落库
        try {
            voteRecordMapper.insert(record);
            log.info("投票记录落库成功: userId={}, activityId={}, targetId={}, voteTime={}",
                    record.getUserId(), record.getActivityId(), record.getTargetId(), record.getVoteTime());
            return PersistResult.INSERTED;
        } catch (DuplicateKeyException e) {
            // 数据库唯一索引兜底命中：同一天已有该用户的投票，属于幂等成功而非失败
            log.info("数据库唯一索引拦截重复投票: userId={}, activityId={}, date={}",
                    record.getUserId(), record.getActivityId(), dateStr);
            return PersistResult.DUPLICATE;
        } catch (Exception e) {
            // 瞬时故障（连接池耗尽 / 死锁 / 主从切换 / 字段超长之外的异常）：
            // 必须撤销幂等标记并抛出，让 MQ 重试；否则这一票永久丢失。
            log.error("投票记录落库失败，撤销幂等标记以便重试: userId={}, activityId={}",
                    record.getUserId(), record.getActivityId(), e);
            rollbackIdempotentMark(idempotentKey);
            throw e;
        }
    }

    /**
     * 批量创建投票记录
     * <p>
     * 注意：此处刻意不加 {@code @Transactional}。每条记录之间相互独立，
     * 一条失败不应回滚其余记录；且内部调用 {@link #createVoteRecord} 属于自调用，
     * 即使加上事务注解也不会生效（不经过 Spring 代理）。
     *
     * @return 成功落库（含幂等命中）的条数
     */
    public int batchCreateVoteRecords(List<String> voteInfoList) {
        if (voteInfoList == null || voteInfoList.isEmpty()) {
            return 0;
        }
        log.info("批量落库投票记录，数量：{}", voteInfoList.size());
        int ok = 0;
        for (String json : voteInfoList) {
            try {
                createVoteRecord(json);
                ok++;
            } catch (Exception e) {
                log.error("批量落库中单条失败，继续处理其余记录", e);
            }
        }
        return ok;
    }

    /**
     * 写入幂等标记
     *
     * @return true-标记成功或 Redis 不可用（降级由数据库唯一索引兜底）；
     *         false-该标记已存在，属重复消息
     */
    private Boolean tryMarkIdempotent(String key) {
        try {
            long ttl = DayUtils.secondsUntilNextMidnight() + IDEMPOTENT_TTL_BUFFER_SECONDS;
            return redisTemplate.opsForValue().setIfAbsent(key, "1", ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 不可用时不能直接放行重复消息，但也不能让正常投票失败：
            // 返回 true 继续走落库流程，由数据库唯一索引做最终判重。
            log.warn("Redis 幂等预检不可用，降级为数据库唯一索引兜底", e);
            return Boolean.TRUE;
        }
    }

    /** 撤销幂等标记；本身失败不影响主流程（数据库唯一索引仍是兜底） */
    private void rollbackIdempotentMark(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("撤销幂等标记失败，将由数据库唯一索引兜底: key={}", key, e);
        }
    }

    private VoteRecord parsePayload(String payloadJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(payloadJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

            Object activityId = map.get("activityId");
            Object targetId = map.get("targetId");
            Object userId = map.get("userId");
            if (activityId == null || targetId == null || userId == null) {
                log.error("投票消息缺少必填字段 activityId/targetId/userId: {}", payloadJson);
                return null;
            }

            VoteRecord record = new VoteRecord();
            record.setActivityId(Long.valueOf(String.valueOf(activityId)));
            record.setTargetId(Long.valueOf(String.valueOf(targetId)));
            record.setUserId(Long.valueOf(String.valueOf(userId)));

            Object userIp = map.get("userIp");
            // String.valueOf(null) 会得到字符串 "null"，这里显式判空
            record.setUserIp(userIp == null ? "" : String.valueOf(userIp));

            Object deviceFingerprint = map.get("deviceFingerprint");
            record.setDeviceFingerprint(deviceFingerprint == null ? null : String.valueOf(deviceFingerprint));

            Object voteTime = map.get("voteTime");
            record.setVoteTime(voteTime == null
                    ? LocalDateTime.now()
                    : DayUtils.toLocalDateTime(Long.parseLong(String.valueOf(voteTime))));

            record.setStatus(1);
            return record;
        } catch (Exception e) {
            log.error("解析投票消息失败", e);
            return null;
        }
    }
}
