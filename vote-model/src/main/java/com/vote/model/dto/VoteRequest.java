package com.vote.model.dto;

import lombok.Data;

/**
 * 投票请求参数
 * @author hzp
 * @since 2026-9-15
 */
@Data
public class VoteRequest {

    /** 活动ID */
    private Long activityId;

    /** 投票目标ID */
    private Long targetId;

    /** 用户ID */
    private Long userId;

    /** 设备指纹（未登录用户） */
    private String deviceFingerprint;
}
