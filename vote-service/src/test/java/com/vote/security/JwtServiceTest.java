package com.vote.security;

import com.vote.common.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtService} 单元测试
 * <p>
 * 重点覆盖"令牌必须无法被伪造"这一条底线，以及签发/解析的往返一致性。
 *
 * @author hzp
 * @since 2026-9-16
 */
class JwtServiceTest {

    private static final String SECRET_A =
            "test-secret-key-for-unit-test-A-0123456789-abcdefghijklmnop";
    private static final String SECRET_B =
            "test-secret-key-for-unit-test-B-0123456789-abcdefghijklmnop";

    private JwtService serviceWith(String secret, long expireSeconds) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpireSeconds(expireSeconds);
        properties.setIssuer("vote-system");
        JwtService service = new JwtService(properties);
        service.init();
        return service;
    }

    @Test
    @DisplayName("签发后能正确解析出用户 ID、角色与 jti")
    void 签发与解析往返一致() {
        JwtService service = serviceWith(SECRET_A, 3600);

        JwtService.IssuedToken issued = service.issue(42L, "ADMIN");
        JwtService.ParsedToken parsed = service.parse(issued.token());

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.role()).isEqualTo("ADMIN");
        assertThat(parsed.jti()).isEqualTo(issued.jti());
        assertThat(issued.expiresIn()).isEqualTo(3600);
    }

    @Test
    @DisplayName("每次签发的 jti 都不同（退出登录依赖 jti 唯一定位令牌）")
    void jti必须唯一() {
        JwtService service = serviceWith(SECRET_A, 3600);

        Set<String> jtis = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            jtis.add(service.issue(1L, "USER").jti());
        }
        assertThat(jtis).hasSize(200);
    }

    @Test
    @DisplayName("换一个密钥签发的令牌无法通过校验 —— 伪造令牌的核心防线")
    void 异密钥签发的令牌不可用() {
        JwtService issuer = serviceWith(SECRET_A, 3600);
        JwtService verifier = serviceWith(SECRET_B, 3600);

        String forged = issuer.issue(1L, "ADMIN").token();

        assertThat(verifier.parse(forged)).isNull();
    }

    @Test
    @DisplayName("篡改签名后无法通过校验")
    void 篡改签名不可用() {
        JwtService service = serviceWith(SECRET_A, 3600);

        String token = service.issue(1L, "USER").token();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(service.parse(tampered)).isNull();
    }

    @Test
    @DisplayName("已过期的令牌无法通过校验")
    void 过期令牌不可用() {
        // expireSeconds 为负 → 签发出来的令牌在签发瞬间就已过期
        JwtService service = serviceWith(SECRET_A, -60);

        String expired = service.issue(1L, "USER").token();

        assertThat(service.parse(expired)).isNull();
    }

    @Test
    @DisplayName("格式非法或为空的令牌返回 null，而不是抛异常")
    void 非法输入不抛异常() {
        JwtService service = serviceWith(SECRET_A, 3600);

        assertThat(service.parse(null)).isNull();
        assertThat(service.parse("")).isNull();
        assertThat(service.parse("   ")).isNull();
        assertThat(service.parse("not-a-jwt")).isNull();
        assertThat(service.parse("a.b.c")).isNull();
    }

    @Test
    @DisplayName("签发者不匹配的令牌被拒绝")
    void 签发者不匹配不可用() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET_A);
        properties.setExpireSeconds(3600);
        properties.setIssuer("someone-else");
        JwtService otherIssuer = new JwtService(properties);
        otherIssuer.init();

        String token = otherIssuer.issue(1L, "USER").token();

        JwtService service = serviceWith(SECRET_A, 3600);
        assertThat(service.parse(token)).isNull();
    }

    @Test
    @DisplayName("32 字节密钥签发的令牌可以正常解析（HS256 下限）")
    void 密钥长度下限仍可正常工作() {
        String exactly32Bytes = "0123456789abcdef0123456789abcdef";
        assertThat(exactly32Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(32);

        JwtService service = serviceWith(exactly32Bytes, 3600);
        JwtService.ParsedToken parsed = service.parse(service.issue(7L, "USER").token());

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(7L);
    }
}
