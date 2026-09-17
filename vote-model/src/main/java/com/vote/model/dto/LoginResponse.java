package com.vote.model.dto;

import lombok.Data;

/**
 * 登录成功响应
 *
 * @author hzp
 * @since 2026-9-16
 */
@Data
public class LoginResponse {

    /** 访问令牌，客户端需在后续请求中通过 {@code Authorization: Bearer <token>} 携带 */
    private String token;

    /** 令牌有效期（秒），客户端可据此提前刷新或重新登录 */
    private Long expiresIn;

    /** 用户信息 */
    private UserProfile user;

    public LoginResponse(String token, Long expiresIn, UserProfile user) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.user = user;
    }
}
