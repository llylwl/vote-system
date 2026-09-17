package com.vote.mq;

import com.rabbitmq.client.Channel;
import com.vote.config.RabbitMQConfig;
import com.vote.service.VotePersistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 投票消息消费者
 * <p>
 * 监听投票主队列，手动 ACK，处理投票记录落库；失败时按「重试队列延迟重投 → 最终死信」策略处理。
 * <p>
 * <b>重试实现要点：</b>不再使用 {@code basicNack(requeue=true)}。该方式由 Broker 重投原始消息，
 * 消费者在本地对象上修改的 {@code retryCount} 头不会被保存回 Broker，导致计数恒为 1、
 * 永远到不了上限、死信队列一条都收不到，同时形成无退避的热重投循环。
 * 现在的做法是：<b>重发一条带自增计数的新消息，然后 ACK 原消息</b>。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteMessageConsumer {

    private final VotePersistService votePersistService;
    private final RabbitTemplate rabbitTemplate;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 重试计数 header */
    public static final String RETRY_HEADER = "retryCount";

    /** 失败原因 header（随死信一起落库，便于排查） */
    public static final String REASON_HEADER = "failureReason";

    @RabbitListener(queues = RabbitMQConfig.VOTE_MAIN_QUEUE,
            containerFactory = "voteRabbitListenerContainerFactory")
    public void consumeVoteMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        int retryCount = readRetryCount(message);

        try {
            VotePersistService.PersistResult result = votePersistService.createVoteRecord(body);

            if (result == VotePersistService.PersistResult.MALFORMED) {
                // 消息体本身不可解析，重试多少次都一样，直接进死信队列等待人工处理
                routeToDeadLetter(body, retryCount, "消息体无法解析");
            }
            // 落库成功 / 幂等命中 / 已转死信，均视为本条消息处理完毕
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("投票消息处理失败, deliveryTag={}, 当前重试次数={}", deliveryTag, retryCount, e);
            if (retryCount >= MAX_RETRY_COUNT) {
                routeToDeadLetter(body, retryCount, e.getClass().getSimpleName() + ": " + e.getMessage());
            } else {
                routeToRetry(body, retryCount + 1);
            }
            // 关键：无论转重试还是转死信，都必须 ACK 原消息。
            // 重试是「发新消息」实现的，若这里 requeue 会导致同一条消息被处理两遍。
            channel.basicAck(deliveryTag, false);
        }
    }

    /** 重发到重试队列，延迟后自动回流主队列 */
    private void routeToRetry(String body, int nextRetryCount) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.VOTE_EXCHANGE,
                RabbitMQConfig.VOTE_RETRY_ROUTING_KEY,
                body,
                m -> {
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    m.getMessageProperties().setHeader(RETRY_HEADER, nextRetryCount);
                    return m;
                });
        log.warn("投票消息已转入重试队列，第 {} 次重试将在 {}ms 后重新投递",
                nextRetryCount, RabbitMQConfig.VOTE_RETRY_TTL_MS);
    }

    /** 重发到最终死信队列，由 DeadLetterConsumer 落库留存 */
    private void routeToDeadLetter(String body, int retryCount, String reason) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.VOTE_DL_EXCHANGE,
                RabbitMQConfig.VOTE_DL_ROUTING_KEY,
                body,
                m -> {
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    m.getMessageProperties().setHeader(RETRY_HEADER, retryCount);
                    m.getMessageProperties().setHeader(REASON_HEADER, reason);
                    return m;
                });
        log.error("投票消息转入死信队列，需人工介入: retryCount={}, reason={}, body={}",
                retryCount, reason, body);
    }

    /** 读取重试计数；计数随重发的新消息持久化在 Broker 上，重启不丢 */
    private int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("重试计数 header 格式非法，按 0 处理: {}", value);
            return 0;
        }
    }
}
