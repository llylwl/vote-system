package com.vote.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 投票请求参数
 *
 * @author hzp
 * @since 2026-9-13
 */
@Data
public class VoteRequest {

    /** 活动ID */
    @NotNull(message = "activityId 必填")
    @Positive(message = "activityId 必须为正整数")
    private Long activityId;

    /** 投票目标ID */
    @NotNull(message = "targetId 必填")
    @Positive(message = "targetId 必须为正整数")
    private Long targetId;

    /**
     * 用户ID
     * <p>
     * <b>已登录时该字段被服务端忽略</b>，投票人身份一律取自令牌，防止冒名投票。
     * 仅在允许匿名投票的配置下（{@code app.security.require-login-to-vote=false}，
     * 开发环境默认）才会被采用，因此这里不做必填校验，由控制器按策略判断。
     */
    @Positive(message = "userId 必须为正整数")
    private Long userId;

    /** 设备指纹（未登录用户）；数据库列为 VARCHAR(64)，超长会导致落库失败 */
    @Size(max = 64, message = "deviceFingerprint 长度不能超过 64")
    private String deviceFingerprint;
}
