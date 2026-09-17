package com.vote.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitTemplate 确认与退回配置
 * 开启 Publisher Confirm + Return 机制，确保消息可靠到达交换机与队列
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Configuration
public class RabbitConfirmConfig implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    private final RabbitTemplate rabbitTemplate;

    public RabbitConfirmConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        // 开启发送确认
        rabbitTemplate.setConfirmCallback(this);
        // 开启消息退回
        rabbitTemplate.setReturnsCallback(this);
        // 只有消息没有路由到队列时，才会触发 returnsCallback
        rabbitTemplate.setMandatory(true);
    }

    /** 消息确认回调：是否成功到达交换机 */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            log.info("MQ消息确认到达Broker, messageId={}",
                    correlationData != null ? correlationData.getId() : null);
        } else {
            log.error("MQ消息未到达Broker, messageId={}, cause={}",
                    correlationData != null ? correlationData.getId() : null, cause);
        }
    }

    /** 消息退回回调：路由失败时触发 */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        log.error("MQ消息路由失败, replyCode={}, replyText={}, exchange={}, routingKey={}",
                returned.getReplyCode(), returned.getReplyText(),
                returned.getExchange(), returned.getRoutingKey());
    }
}
