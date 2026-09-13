package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投票记录表
 * 记录每一次投票行为，用于防刷校验和审计
 * @author hzp
 * @since 2026-9-15
 */
@Data
@TableName("vote_record")
public class VoteRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动ID */
    private Long activityId;

    /** 投票目标ID */
    private Long targetId;

    /** 用户ID */
    private Long userId;

    /** 用户IP */
    private String userIp;

    /** 设备指纹 */
    private String deviceFingerprint;

    /** 投票时间 */
    private LocalDateTime voteTime;

    /** 状态：1-有效，0-无效/被拦截 */
    private Integer status;
}
