package com.vote.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.config.RabbitMQConfig;
import com.vote.model.dto.VoteOutboxMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 消息同步服务
 * 高频轮询 Redis 的 vote:outbox:queue，将投票消息可靠投递到 RabbitMQ；
 * 投递失败则放回队列尾部并累计重试次数，超过阈值转入死信处理（TODO: 落库/告警）
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteOutboxPublisher {

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 5;

    /** 每 20 毫秒轮询一次 outbox 队列，保证消息的及时投递 */
    @Scheduled(fixedDelay = 20)
    public void pollAndPublish() {
        try {
            // RPOP：配合 Lua 中的 LPUSH 实现 FIFO
            String json = redisTemplate.opsForList().rightPop(RedisKeys.OUTBOX_QUEUE);
            if (json == null) {
                return;
            }
            VoteOutboxMessage message = objectMapper.readValue(json, VoteOutboxMessage.class);
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.VOTE_EXCHANGE,
                        RabbitMQConfig.VOTE_ROUTING_KEY,
                        message.getPayload(),
                        m -> {
                            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return m;
                        });
                log.debug("Outbox消息投递成功, messageId={}", message.getMessageId());
            } catch (Exception e) {
                log.error("MQ投递失败, 消息重回队列, messageId={}", message.getMessageId(), e);
                message.setRetryCount(message.getRetryCount() == null ? 1 : message.getRetryCount() + 1);
                if (message.getRetryCount() > MAX_RETRY_COUNT) {
                    log.error("消息重试超过{}次, 转入死信处理, messageId={}", MAX_RETRY_COUNT, message.getMessageId());
                    // TODO: 记录到数据库或告警
                    return;
                }
                // 放回队列头部，等待下次轮询重试
                redisTemplate.opsForList().leftPush(RedisKeys.OUTBOX_QUEUE,
                        objectMapper.writeValueAsString(message));
            }
        } catch (Exception e) {
            log.error("Outbox轮询处理异常", e);
        }
    }
}
