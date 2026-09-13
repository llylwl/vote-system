package com.vote.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Outbox 发件箱消息实体
 * 用于 Redis 与 RabbitMQ 之间的可靠传递
 * @author hzp
 * @since 2026-9-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoteOutboxMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息唯一ID，用于幂等 */
    private String messageId;

    /** 目标队列名称 */
    private String queueName;

    /** 消息体 JSON */
    private String payload;

    /** 创建时间戳 */
    private Long createTime;

    /** 重试次数 */
    private Integer retryCount;
}
