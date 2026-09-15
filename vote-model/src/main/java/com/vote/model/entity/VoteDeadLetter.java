package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投票死信表
 * 重试耗尽仍无法落库的消息在此留存，支持人工排查与重放
 * @author hzp
 * @since 2026-9-15
 */
@Data
@TableName("vote_dead_letter")
public class VoteDeadLetter {

    /** 处理状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 处理状态：已重放 */
    public static final int STATUS_REPLAYED = 1;
    /** 处理状态：已忽略 */
    public static final int STATUS_IGNORED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始消息体（投票明细 JSON） */
    private String messageBody;

    /** 失败原因 */
    private String reason;

    /** 进入死信前已重试次数 */
    private Integer retryCount;

    /** 处理状态：0-待处理，1-已重放，2-已忽略 */
    private Integer status;

    /** 进入死信时间 */
    private LocalDateTime createTime;

    /** 人工处理时间 */
    private LocalDateTime handleTime;
}
