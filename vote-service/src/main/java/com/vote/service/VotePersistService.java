package com.vote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.VoteRecord;
import com.vote.model.mapper.VoteRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 投票持久化服务
 * 负责将投票记录写入数据库，并进行幂等校验（Redis setIfAbsent + 数据库联合唯一索引双重兜底）
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VotePersistService {

    private final StringRedisTemplate redisTemplate;
    private final VoteRecordMapper voteRecordMapper;
    private final ObjectMapper objectMapper;

    /** 创建单条投票记录（含幂等校验），同一用户、同一活动、同一天只能投一次 */
    @Transactional(rollbackFor = Exception.class)
    public void createVoteRecord(String payloadJson) {
        VoteRecord record = parsePayload(payloadJson);
        if (record == null) {
            log.error("投票消息解析失败，消息无法处理: {}", payloadJson);
            return; // 消息格式错误，重试无意义，直接确认丢弃
        }

        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String idempotentKey = String.format(RedisKeys.IDEMPOTENT + "%s:%s:%s",
                record.getActivityId(), record.getUserId(), dateStr);

        // 1. Redis setIfAbsent 幂等校验（25 小时，覆盖当天+缓冲）
        Boolean isNewVote = redisTemplate.opsForValue().setIfAbsent(idempotentKey, "1", 25, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNewVote)) {
            log.warn("重复投票请求已拦截，userId={}, activityId={}", record.getUserId(), record.getActivityId());
            return;
        }

        // 2. 写入数据库（数据库联合唯一索引做最终兜底）
        try {
            voteRecordMapper.insert(record);
            log.info("投票记录落库成功，userId={}, activityId={}, targetId={}",
                    record.getUserId(), record.getActivityId(), record.getTargetId());
        } catch (Exception e) {
            // 数据库唯一索引兜底：重复投票直接忽略，不抛异常（避免无谓重试/死信）
            log.warn("投票记录写入失败（可能重复），userId={}, activityId={}, err={}",
                    record.getUserId(), record.getActivityId(), e.getMessage());
        }
    }

    /** 批量创建投票记录 */
    @Transactional(rollbackFor = Exception.class)
    public void batchCreateVoteRecords(List<String> voteInfoList) {
        log.info("批量落库投票记录，数量：{}", voteInfoList.size());
        for (String json : voteInfoList) {
            createVoteRecord(json);
        }
    }

    private VoteRecord parsePayload(String payloadJson) {
        try {
            Map<String, Object> map = objectMapper.readValue(payloadJson,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            VoteRecord record = new VoteRecord();
            record.setActivityId(Long.valueOf(String.valueOf(map.get("activityId"))));
            record.setTargetId(Long.valueOf(String.valueOf(map.get("targetId"))));
            record.setUserId(Long.valueOf(String.valueOf(map.get("userId"))));
            record.setUserIp(String.valueOf(map.get("userIp")));
            record.setDeviceFingerprint(map.get("deviceFingerprint") == null ? null
                    : String.valueOf(map.get("deviceFingerprint")));
            Object voteTime = map.get("voteTime");
            if (voteTime != null) {
                record.setVoteTime(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(String.valueOf(voteTime))), ZoneId.systemDefault()));
            } else {
                record.setVoteTime(LocalDateTime.now());
            }
            record.setStatus(1);
            return record;
        } catch (Exception e) {
            log.error("解析投票消息失败", e);
            return null;
        }
    }
}
