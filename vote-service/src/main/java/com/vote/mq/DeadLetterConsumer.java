package com.vote.mq;

import com.rabbitmq.client.Channel;
import com.vote.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 死信队列消费者
 * 记录错误日志、触发告警，ACK 后丢弃，防止无限循环
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = RabbitMQConfig.VOTE_DL_QUEUE)
    public void consumeDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.error("【死信告警】收到无法处理的投票消息, deliveryTag={}, body={}", deliveryTag, body);
        // TODO: 在此处接入告警系统（钉钉、邮件、短信等）
        // 确认死信消息，防止无限循环
        channel.basicAck(deliveryTag, false);
    }
}
