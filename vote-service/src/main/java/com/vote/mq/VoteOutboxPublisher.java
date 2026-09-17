package com.vote.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.config.RabbitMQConfig;
import com.vote.model.dto.VoteOutboxMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox 消息投递服务
 * <p>
 * 轮询 Redis 的 Outbox 队列，把投票消息可靠投递到 RabbitMQ。
 * <p>
 * <b>可靠性要点：</b>
 * <ol>
 *   <li>用 {@code RPOPLPUSH} 原子地把消息从「待投递」搬到「处理中」队列，
 *       而非 {@code RPOP} 直接删除 —— 后者在弹出后、投递前若进程崩溃，消息就永久消失了；</li>
 *   <li>投递时携带 {@link CorrelationData} 并<b>等待 Broker 确认</b>，
 *       确认成功才从「处理中」队列移除。原实现不等确认，Publisher Confirm 回调只打日志、不参与决策；</li>
 *   <li>启动时把「处理中」队列里的残留消息搬回，恢复崩溃前未完成的投递。</li>
 * </ol>
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteOutboxPublisher {

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 最大重试次数，超过后转入死信 */
    private static final int MAX_RETRY_COUNT = 5;

    /** 单次轮询最多处理的消息条数（原实现每次只处理 1 条，吞吐被锁死在 50 msg/s） */
    private static final int BATCH_SIZE = 500;

    /** 等待 Broker 确认的超时时间（秒） */
    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    /** 启动恢复的防御上限，避免异常数据导致启动卡死 */
    private static final int RECOVER_LIMIT = 100_000;

    /**
     * 启动时恢复：把上次进程崩溃残留在「处理中」队列的消息搬回「待投递」队列。
     * 这些消息尚未收到 Broker 确认，必须重新投递。
     */
    @PostConstruct
    public void recoverUnconfirmedMessages() {
        long moved = 0;
        try {
            while (moved < RECOVER_LIMIT) {
                String json = redisTemplate.opsForList().rightPopAndLeftPush(
                        RedisKeys.OUTBOX_PROCESSING_QUEUE, RedisKeys.OUTBOX_QUEUE);
                if (json == null) {
                    break;
                }
                moved++;
            }
            if (moved > 0) {
                log.warn("Outbox 启动恢复：将 {} 条未确认消息搬回待投递队列", moved);
            }
            if (moved >= RECOVER_LIMIT) {
                log.error("Outbox 启动恢复达到上限 {}，仍有消息留在处理中队列，请人工检查",
                        RECOVER_LIMIT);
            }
        } catch (Exception e) {
            log.error("Outbox 启动恢复失败，未确认消息仍留在处理中队列，稍后重启可再次恢复", e);
        }
    }

    /** 高频轮询：一次尽量把待投递队列排空，而不是每 20ms 只处理一条 */
    @Scheduled(fixedDelay = 20)
    public void pollAndPublish() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            if (!publishOne()) {
                break;
            }
        }
    }

    /**
     * 投递一条消息
     *
     * @return true-已处理一条（队列可能还有下一条）；false-队列已空或发生异常，应结束本轮
     */
    private boolean publishOne() {
        String json = null;
        try {
            // 原子搬迁：消息现在同时「安全地」存在于处理中队列，进程崩溃也不会丢
            json = redisTemplate.opsForList().rightPopAndLeftPush(
                    RedisKeys.OUTBOX_QUEUE, RedisKeys.OUTBOX_PROCESSING_QUEUE);
            if (json == null) {
                return false;
            }

            VoteOutboxMessage message = objectMapper.readValue(json, VoteOutboxMessage.class);

            if (sendAndAwaitConfirm(message)) {
                // 已确认到达 Broker，才从处理中队列移除
                redisTemplate.opsForList().remove(RedisKeys.OUTBOX_PROCESSING_QUEUE, 1, json);
                log.debug("Outbox 消息投递成功, messageId={}", message.getMessageId());
            } else {
                handleFailure(json, message);
            }
            return true;

        } catch (Exception e) {
            log.error("Outbox 投递异常", e);
            if (json != null) {
                // 解析失败或其它异常：把消息从处理中队列搬回待投递队列，避免变成孤儿
                requeue(json);
            }
            // 返回 false 结束本轮，避免持续异常时死循环刷日志
            return false;
        }
    }

    /**
     * 发送并等待 Broker 确认
     * <p>
     * 仅当 Broker 确认已接收（ack）且消息未被退回（可路由到队列）时才算成功。
     *
     * @return true-投递成功
     */
    private boolean sendAndAwaitConfirm(VoteOutboxMessage message) {
        CorrelationData correlationData = new CorrelationData(message.getMessageId());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.VOTE_EXCHANGE,
                    RabbitMQConfig.VOTE_ROUTING_KEY,
                    message.getPayload(),
                    m -> {
                        m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return m;
                    },
                    correlationData);

            CorrelationData.Confirm confirm = correlationData
                    .getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                log.error("Broker 未确认消息, messageId={}, cause={}",
                        message.getMessageId(), confirm.getReason());
                return false;
            }

            // 消息到达交换机但无法路由到任何队列时会被退回（需要 setMandatory(true)）。
            // 若只看 confirm.isAck()，这种情况会被误判为成功，消息从处理中队列移除后即永久丢失。
            if (correlationData.getReturned() != null) {
                log.error("消息无法路由到队列, messageId={}, replyText={}",
                        message.getMessageId(), correlationData.getReturned().getReplyText());
                return false;
            }
            return true;

        } catch (Exception e) {
            log.error("等待 Broker 确认失败, messageId={}", message.getMessageId(), e);
            return false;
        }
    }

    /** 投递失败：累计重试次数，超限转死信，否则放回待投递队列 */
    private void handleFailure(String json, VoteOutboxMessage message) {
        redisTemplate.opsForList().remove(RedisKeys.OUTBOX_PROCESSING_QUEUE, 1, json);

        int retryCount = message.getRetryCount() == null ? 1 : message.getRetryCount() + 1;
        if (retryCount > MAX_RETRY_COUNT) {
            // 超限：转入死信队列，由 DeadLetterConsumer 落库留存，不再静默丢弃
            log.error("Outbox 消息重试超过 {} 次，转入死信队列, messageId={}, payload={}",
                    MAX_RETRY_COUNT, message.getMessageId(), json);
            routeToDeadLetter(json, retryCount);
            return;
        }

        message.setRetryCount(retryCount);
        try {
            redisTemplate.opsForList().leftPush(RedisKeys.OUTBOX_QUEUE,
                    objectMapper.writeValueAsString(message));
            log.warn("Outbox 投递失败，消息已放回待投递队列, messageId={}, retryCount={}",
                    message.getMessageId(), retryCount);
        } catch (Exception e) {
            log.error("放回 Outbox 队列失败，消息留在处理中队列等待下次启动恢复, messageId={}",
                    message.getMessageId(), e);
        }
    }

    /** 把消息搬到死信队列留存（复用消费端的死信通道，统一由 DeadLetterConsumer 落库） */
    private void routeToDeadLetter(String json, int retryCount) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.VOTE_DL_EXCHANGE,
                    RabbitMQConfig.VOTE_DL_ROUTING_KEY,
                    json,
                    m -> {
                        m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        m.getMessageProperties().setHeader(VoteMessageConsumer.RETRY_HEADER, retryCount);
                        m.getMessageProperties().setHeader(VoteMessageConsumer.REASON_HEADER,
                                "Outbox 投递 MQ 失败，重试超过 " + MAX_RETRY_COUNT + " 次");
                        return m;
                    });
        } catch (Exception e) {
            // 连死信都发不出去（通常意味着 MQ 整体不可用）：搬回待投递队列，等 MQ 恢复后重试
            log.error("转入死信队列失败，消息搬回待投递队列, retryCount={}", retryCount, e);
            requeue(json);
        }
    }

    /** 把消息从处理中队列搬回待投递队列 */
    private void requeue(String json) {
        try {
            redisTemplate.opsForList().remove(RedisKeys.OUTBOX_PROCESSING_QUEUE, 1, json);
            redisTemplate.opsForList().leftPush(RedisKeys.OUTBOX_QUEUE, json);
        } catch (Exception e) {
            log.error("搬回待投递队列失败，消息留在处理中队列等待下次启动恢复", e);
        }
    }
}
