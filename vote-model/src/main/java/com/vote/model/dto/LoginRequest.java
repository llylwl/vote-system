package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账号密码登录请求参数
 *
 * @author hzp
 * @since 2026-9-16
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名必填")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    @NotBlank(message = "密码必填")
    @Size(max = 64, message = "密码长度不能超过 64")
    private String password;
}
