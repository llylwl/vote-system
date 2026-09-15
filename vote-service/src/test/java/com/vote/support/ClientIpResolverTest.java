package com.vote.support;

import com.vote.common.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClientIpResolver} 单元测试
 * <p>
 * 这些用例守卫的是一条真实存在的安全缺陷：原实现无条件采信 {@code X-Forwarded-For}
 * 的最左值，而该值由客户端完全控制，攻击者只要伪造这个头就能同时绕过
 * IP 限流与 IP 黑名单 —— 对防刷系统而言这是致命缺陷。
 *
 * @author hzp
 * @since 2026-9-15
 */
class ClientIpResolverTest {

    private static final String HEADER_XFF = "X-Forwarded-For";

    private ClientIpResolver resolver(List<String> trustedProxies) {
        SecurityProperties properties = new SecurityProperties();
        properties.setTrustedProxies(trustedProxies);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        resolver.logTrustedProxyConfig();
        return resolver;
    }

    private HttpServletRequest request(String remoteAddr, String xff) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader(HEADER_XFF)).thenReturn(xff);
        return request;
    }

    @Test
    @DisplayName("未配置可信代理时完全忽略 X-Forwarded-For：伪造头无法生效")
    void 不信任任何代理时忽略转发头() {
        ClientIpResolver resolver = resolver(List.of());
        HttpServletRequest request = request("203.0.113.9", "1.2.3.4");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("请求不来自可信代理时，即使带了 XFF 也不采信")
    void 非可信来源忽略转发头() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("203.0.113.9", "1.2.3.4");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("来自可信代理时，从 XFF 最右侧向左跳过可信地址")
    void 从右向左跳过可信代理() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("10.0.0.5", "1.2.3.4, 10.0.0.7");
        assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("XFF 最右侧就是不可信地址时取最右侧")
    void 最右侧不可信时取最右侧() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("10.0.0.5", "1.2.3.4, 5.6.7.8");
        assertThat(resolver.resolve(request)).isEqualTo("5.6.7.8");
    }

    @Test
    @DisplayName("关键安全性质：客户端伪造的 XFF 前缀不会成为判定结果")
    void 伪造的前缀不影响结果() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        // 客户端自带 XFF: 8.8.8.8，可信代理转发时追加真实对端，形成 "8.8.8.8, 5.6.7.8"
        HttpServletRequest request = request("10.0.0.5", "8.8.8.8, 5.6.7.8");
        // 正确结果是代理观察到的 5.6.7.8，而非客户端自称的 8.8.8.8。
        // 若按最左值取值（原实现），攻击者即可用任意假 IP 逃避黑名单。
        assertThat(resolver.resolve(request)).isEqualTo("5.6.7.8");
    }

    @Test
    @DisplayName("CIDR 网段匹配")
    void CIDR匹配() {
        ClientIpResolver resolver = resolver(List.of("192.168.0.0/16"));
        assertThat(resolver.isTrustedProxy("192.168.5.5")).isTrue();
        assertThat(resolver.isTrustedProxy("192.169.0.1")).isFalse();
    }

    @Test
    @DisplayName("单个 IP 精确匹配")
    void 单个IP精确匹配() {
        ClientIpResolver resolver = resolver(List.of("203.0.113.7"));
        assertThat(resolver.isTrustedProxy("203.0.113.7")).isTrue();
        assertThat(resolver.isTrustedProxy("203.0.113.8")).isFalse();
    }

    @Test
    @DisplayName("IPv6 地址不会被 IPv4 网段误判为可信")
    void IPv6不按IPv4网段匹配() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        assertThat(resolver.isTrustedProxy("0:0:0:0:0:0:0:1")).isFalse();
    }

    @Test
    @DisplayName("空配置或非法配置安全降级为不信任，且不抛异常")
    void 空配置安全降级() {
        assertThat(resolver(List.of()).isTrustedProxy("10.0.0.1")).isFalse();
        assertThat(resolver(List.of("   ")).isTrustedProxy("10.0.0.1")).isFalse();
        assertThat(resolver(List.of("10.0.0.0/99")).isTrustedProxy("10.0.0.1")).isFalse();
        assertThat(resolver(List.of("not-an-ip")).isTrustedProxy("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("整条 XFF 链都是可信代理时，退化为取最左侧地址")
    void 全可信链路取最左() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        HttpServletRequest request = request("10.0.0.5", "10.0.0.9, 10.0.0.7");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.9");
    }
}
