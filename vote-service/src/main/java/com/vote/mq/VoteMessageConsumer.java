package com.vote.mq;

import com.rabbitmq.client.Channel;
import com.vote.config.RabbitMQConfig;
import com.vote.service.VotePersistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 投票消息消费者
 * 监听投票主队列，手动 ACK，处理投票记录落库；
 * 失败重试，超过最大重试次数后拒绝并路由至死信队列（DLX）
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteMessageConsumer {

    private final VotePersistService votePersistService;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    @RabbitListener(queues = RabbitMQConfig.VOTE_MAIN_QUEUE,
            containerFactory = "voteRabbitListenerContainerFactory")
    public void consumeVoteMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("收到投票消息, deliveryTag={}, body={}", deliveryTag, body);
        try {
            // 投票记录落库（内部含幂等校验）
            votePersistService.createVoteRecord(body);
            // 处理成功，手动 ACK
            channel.basicAck(deliveryTag, false);
            log.info("投票消息处理成功, deliveryTag={}", deliveryTag);
        } catch (Exception e) {
            log.error("投票消息处理失败, deliveryTag={}, body={}", deliveryTag, body, e);
            // 获取重试次数，超过最大重试次数则拒绝并路由至死信队列
            Integer retryCount = (Integer) message.getMessageProperties().getHeaders().get("retryCount");
            int currentRetry = (retryCount == null) ? 1 : retryCount + 1;
            if (currentRetry > MAX_RETRY_COUNT) {
                log.error("投票消息重试超过{}次, 拒绝并路由至死信队列, deliveryTag={}",
                        MAX_RETRY_COUNT, deliveryTag);
                // requeue=false，消息将被路由到死信队列
                channel.basicNack(deliveryTag, false, false);
            } else {
                log.warn("投票消息处理失败, 准备第{}次重试, deliveryTag={}", currentRetry, deliveryTag);
                // 重新入队，并更新重试次数头信息
                message.getMessageProperties().getHeaders().put("retryCount", currentRetry);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }
}
