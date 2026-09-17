package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数
 *
 * @author hzp
 * @since 2026-9-17
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名必填")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$",
            message = "用户名只能包含字母、数字、下划线，长度 3~32 位")
    private String username;

    /**
     * 明文密码。
     * <p>
     * 上限 64 位是有意为之：BCrypt 只使用前 72 字节，超出部分会被静默忽略，
     * 限制长度可避免"密码很长但其实只有前 72 字节有效"的认知偏差。
     */
    @NotBlank(message = "密码必填")
    @Size(min = 8, max = 64, message = "密码长度需为 8~64 位")
    private String password;

    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;
}
