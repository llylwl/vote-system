package com.vote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.config.RabbitMQConfig;
import com.vote.model.dto.VoteOutboxMessage;
import com.vote.model.dto.VoteRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 核心投票服务
 * 基于 Redis + Lua 实现原子性「校验 + 投票计数 + 排行榜 + Outbox 写消息」，
 * 将数据库压力降低 90% 以上，从机制上杜绝刷票与掉票
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final DefaultRedisScript<Long> VOTE_SCRIPT;

    static {
        VOTE_SCRIPT = new DefaultRedisScript<>();
        VOTE_SCRIPT.setLocation(new ClassPathResource("lua/vote_and_outbox.lua"));
        VOTE_SCRIPT.setResultType(Long.class);
    }

    /**
     * 执行原子投票
     *
     * @return 1-成功；-1-活动未开始/已结束；-2-黑名单拦截；-3-今日已投；-99-系统异常
     */
    public Long vote(VoteRequest request, String userIp) {
        long now = System.currentTimeMillis();

        String activityKey = RedisKeys.ACTIVITY_INFO + request.getActivityId();
        String userTodayKey = RedisKeys.USER_TODAY + request.getActivityId() + ":" + request.getUserId();
        String blacklistUserKey = RedisKeys.BLACKLIST + "USER:" + request.getUserId();
        String blacklistIpKey = RedisKeys.BLACKLIST + "IP:" + userIp;
        String blacklistDeviceKey = (request.getDeviceFingerprint() == null || request.getDeviceFingerprint().isEmpty())
                ? "" : RedisKeys.BLACKLIST + "DEVICE:" + request.getDeviceFingerprint();
        String rankKey = RedisKeys.RANK + request.getActivityId();
        String outboxKey = RedisKeys.OUTBOX_QUEUE;

        List<String> keys = Arrays.asList(
                activityKey, userTodayKey, blacklistUserKey,
                blacklistIpKey, blacklistDeviceKey, rankKey, outboxKey);

        String messageId = UUID.randomUUID().toString().replace("-", "");
        String payloadJson = buildPayloadJson(request, userIp, messageId);

        List<String> args = Arrays.asList(
                String.valueOf(now),
                String.valueOf(request.getUserId()),
                String.valueOf(request.getTargetId()),
                String.valueOf(request.getActivityId()),
                messageId,
                RabbitMQConfig.VOTE_MAIN_QUEUE,
                payloadJson,
                "86400"
        );

        try {
            Long result = stringRedisTemplate.execute(VOTE_SCRIPT, keys, args.toArray(new String[0]));
            log.info("投票结果: activityId={}, userId={}, targetId={}, result={}",
                    request.getActivityId(), request.getUserId(), request.getTargetId(), result);
            return result;
        } catch (Exception e) {
            log.error("Lua脚本执行异常: activityId={}, userId={}", request.getActivityId(), request.getUserId(), e);
            return -99L;
        }
    }

    /** 构建写入 Outbox 的消息体（内层 payload 为投票明细 JSON） */
    private String buildPayloadJson(VoteRequest request, String userIp, String messageId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", messageId);
            payload.put("activityId", request.getActivityId());
            payload.put("targetId", request.getTargetId());
            payload.put("userId", request.getUserId());
            payload.put("userIp", userIp);
            if (request.getDeviceFingerprint() != null && !request.getDeviceFingerprint().isEmpty()) {
                payload.put("deviceFingerprint", request.getDeviceFingerprint());
            }
            payload.put("voteTime", System.currentTimeMillis());
            String payloadJson = objectMapper.writeValueAsString(payload);

            VoteOutboxMessage message = VoteOutboxMessage.builder()
                    .messageId(messageId)
                    .queueName(RabbitMQConfig.VOTE_MAIN_QUEUE)
                    .payload(payloadJson)
                    .createTime(System.currentTimeMillis())
                    .retryCount(0)
                    .build();
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("构建Outbox消息失败", e);
            return "{}";
        }
    }
}
