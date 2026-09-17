package com.vote.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置
 * <p>
 * 对应 {@code app.jwt.*}。签名密钥在生产环境必须由环境变量注入，
 * 且不能使用开发默认值 —— 密钥一旦泄露，任何人都能伪造任意用户的令牌。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HS256 要求密钥长度不低于 256 位（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    /** 签名密钥 */
    private String secret;

    /** 令牌有效期（秒），默认 7 天 */
    private long expireSeconds = 7 * 24 * 3600L;

    /** 签发者标识 */
    private String issuer = "vote-system";

    /**
     * 启动时校验密钥强度
     * <p>
     * 刻意在启动阶段就失败，而不是等到第一次登录调用签名方法时才报错 ——
     * 后者意味着带着一个不可用的配置跑到了线上才暴露。
     */
    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 签名密钥未配置。请设置 app.jwt.secret 或环境变量 JWT_SECRET。");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT 签名密钥过短（当前 %d 字节，HS256 要求至少 %d 字节）。"
                            + "请更换为足够长的随机字符串，例如 openssl rand -base64 48 的输出。",
                    length, MIN_SECRET_BYTES));
        }
        if (expireSeconds <= 0) {
            throw new IllegalStateException("app.jwt.expire-seconds 必须为正数");
        }
    }
}
