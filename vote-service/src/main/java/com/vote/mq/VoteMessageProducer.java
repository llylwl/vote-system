package com.vote.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 投票消息生产者
 * 将投票信息封装为消息并投递到 RabbitMQ（消息持久化，防止 MQ 重启丢失）
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoteMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 发送投票消息到队列 */
    public void sendVoteMessage(Map<String, Object> voteInfo) {
        try {
            String jsonContent = objectMapper.writeValueAsString(voteInfo);
            Message message = MessageBuilder.withBody(jsonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.VOTE_EXCHANGE,
                    RabbitMQConfig.VOTE_ROUTING_KEY,
                    message
            );
            log.info("投票消息发送成功, voteInfo: {}", voteInfo);
        } catch (Exception e) {
            log.error("投票消息发送失败, voteInfo: {}", voteInfo, e);
            throw new RuntimeException("投票消息发送失败", e);
        }
    }
}
