package com.vote.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全相关配置
 * <p>
 * 对应 application.yml 中的 {@code app.security.*}，均可通过环境变量注入。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /**
     * 可信反向代理地址列表，支持单个 IP 或 IPv4 CIDR，例如：
     * {@code 10.0.0.0/8,172.16.0.0/12,192.168.0.1}
     * <p>
     * <b>只有来自这些地址的请求，其 {@code X-Forwarded-For} 才会被采信。</b>
     * 留空表示不信任任何代理，此时一律使用 TCP 对端地址 —— 这是安全的默认值。
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * 是否信任客户端提交的 {@code X-User-Id} 头
     * <p>
     * 该头由客户端完全控制，仅用于本地调试（dev 环境开启）。
     * <b>生产环境必须为 false</b>，否则攻击者每次请求换一个随机值即可获得无限配额，
     * 限流形同虚设。
     */
    private boolean trustClientUserId = false;
}
