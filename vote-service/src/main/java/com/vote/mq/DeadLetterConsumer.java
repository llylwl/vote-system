package com.vote.mq;

import com.rabbitmq.client.Channel;
import com.vote.config.RabbitMQConfig;
import com.vote.model.entity.VoteDeadLetter;
import com.vote.model.mapper.VoteDeadLetterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 死信队列消费者
 * <p>
 * 重试耗尽仍无法落库的投票消息在此<b>落库留存</b>，供人工排查与重放，
 * 而不是只打一条日志就丢弃 —— 后者会让消息彻底消失，与「不掉票」目标矛盾。
 * <p>
 * 使用与主消费者相同的手动 ACK 容器工厂；若改回默认的自动 ACK 容器，
 * 本类中的 {@code channel.basicAck} 会因 delivery tag 已被自动确认而报错。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final VoteDeadLetterMapper voteDeadLetterMapper;

    @RabbitListener(queues = RabbitMQConfig.VOTE_DL_QUEUE,
            containerFactory = "voteRabbitListenerContainerFactory")
    public void consumeDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String reason = readHeader(message, VoteMessageConsumer.REASON_HEADER);
        int retryCount = parseRetryCount(message);

        try {
            VoteDeadLetter entity = new VoteDeadLetter();
            entity.setMessageBody(body);
            entity.setReason(reason);
            entity.setRetryCount(retryCount);
            entity.setStatus(VoteDeadLetter.STATUS_PENDING);
            entity.setCreateTime(LocalDateTime.now());
            voteDeadLetterMapper.insert(entity);

            log.error("【死信告警】投票消息已落库待人工处理: id={}, retryCount={}, reason={}, body={}",
                    entity.getId(), retryCount, reason, body);
        } catch (Exception e) {
            // 落库失败时不能只是不 ACK：手动 ACK 模式下不确认会让消息在 channel 关闭后重新入队，
            // 而死信队列没有消费重试机制，会形成死循环。
            // 因此这里把完整消息体以 ERROR 级别打出，保证至少可从日志中恢复，然后 ACK 掉。
            log.error("【严重】死信落库失败，以下是完整消息体，请人工保存并补录: reason={}, body={}",
                    reason, body, e);
        }

        channel.basicAck(deliveryTag, false);
    }

    private String readHeader(Message message, String key) {
        Object value = message.getMessageProperties().getHeaders().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int parseRetryCount(Message message) {
        String value = readHeader(message, VoteMessageConsumer.RETRY_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
