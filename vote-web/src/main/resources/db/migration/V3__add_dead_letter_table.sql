-- ============================================================
-- V3 投票死信表
--
-- 背景：重试耗尽的消息此前只被 DeadLetterConsumer 打一条日志就 ACK 丢弃，
-- 消息彻底消失、无法重放，与「不掉票」的设计目标矛盾。
-- 这里把死信落库留存，配合 status 字段支持人工重放。
-- ============================================================

CREATE TABLE IF NOT EXISTS vote_dead_letter (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    message_body TEXT         NOT NULL COMMENT '原始消息体（投票明细 JSON）',
    reason       VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    retry_count  INT          DEFAULT 0 COMMENT '进入死信前已重试次数',
    status       TINYINT      DEFAULT 0 COMMENT '处理状态：0-待处理，1-已重放，2-已忽略',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '进入死信时间',
    handle_time  DATETIME     DEFAULT NULL COMMENT '人工处理时间',
    PRIMARY KEY (id),
    KEY idx_status_time (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投票死信表';
