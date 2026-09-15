package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 添加黑名单请求参数
 *
 * @author hzp
 * @since 2026-9-15
 */
@Data
public class BlacklistCreateRequest {

    @NotBlank(message = "targetType 必填")
    @Pattern(regexp = "(?i)^(USER|IP|DEVICE)$", message = "targetType 只能是 USER / IP / DEVICE")
    private String targetType;

    /** 目标值；数据库列为 VARCHAR(64)，超长会导致落库失败 */
    @NotBlank(message = "targetValue 必填")
    @Size(max = 64, message = "targetValue 长度不能超过 64")
    private String targetValue;

    @Size(max = 255, message = "封禁原因长度不能超过 255")
    private String reason;

    /** 过期时间，null 表示永久 */
    private LocalDateTime expireTime;
}
