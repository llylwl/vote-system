package com.vote.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证相关的基础 Bean
 * <p>
 * 只引入 {@code spring-security-crypto} 这一个轻量组件来获得 BCrypt 实现，
 * <b>不引入完整的 Spring Security</b>：后者会自动装配一整条过滤器链与默认登录页，
 * 与本项目基于拦截器的认证方式冲突，还要额外写配置去关掉它。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Configuration
public class AuthConfig {

    /**
     * 密码编码器
     * <p>
     * 使用 BCrypt：自带随机盐、可调计算强度，且每次加密同一密码结果都不同，
     * 因此不存在"彩虹表"与"同密码同哈希"的泄漏面。
     * 强度参数 10 是默认值，约 50~100ms/次，是安全性与登录延迟的常见平衡点。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
