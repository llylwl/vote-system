package com.vote.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtProperties} 启动校验的单元测试
 * <p>
 * 这些用例守护的是「配置错误必须启动即失败」这一原则：
 * 密钥缺失或过短的后果不是功能异常，而是<b>任何人都能伪造任意用户的令牌</b>。
 * 若等到第一次登录调用签名方法时才报错，意味着这个配置错误会被带到线上。
 *
 * @author hzp
 * @since 2026-9-16
 */
class JwtPropertiesTest {

    private static final String VALID_SECRET =
            "test-secret-key-for-unit-test-0123456789-abcdefghijklmnop";

    private JwtProperties properties(String secret, long expireSeconds) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpireSeconds(expireSeconds);
        return properties;
    }

    @Test
    @DisplayName("密钥未配置时启动失败，提示明确")
    void 密钥缺失时失败() {
        assertThatThrownBy(() -> properties(null, 3600).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");

        assertThatThrownBy(() -> properties("", 3600).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");

        assertThatThrownBy(() -> properties("   ", 3600).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    @DisplayName("密钥短于 256 位时启动失败（HS256 的硬性要求）")
    void 密钥过短时失败() {
        assertThatThrownBy(() -> properties("short-secret", 3600).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过短");

        // 31 字节，差一字节也不行
        String thirtyOneBytes = "0123456789abcdef0123456789abcde";
        assertThat(thirtyOneBytes.getBytes(StandardCharsets.UTF_8)).hasSize(31);
        assertThatThrownBy(() -> properties(thirtyOneBytes, 3600).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过短");
    }

    @Test
    @DisplayName("恰好 32 字节的密钥通过校验（边界值不能误杀）")
    void 密钥长度边界通过() {
        String exactly32Bytes = "0123456789abcdef0123456789abcdef";
        assertThat(exactly32Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(32);

        assertThatCode(() -> properties(exactly32Bytes, 3600).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("有效期必须为正数")
    void 有效期非法时失败() {
        assertThatThrownBy(() -> properties(VALID_SECRET, 0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expire-seconds");

        assertThatThrownBy(() -> properties(VALID_SECRET, -1).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expire-seconds");
    }

    @Test
    @DisplayName("合法配置通过校验")
    void 合法配置通过() {
        assertThatCode(() -> properties(VALID_SECRET, 604800).validate())
                .doesNotThrowAnyException();
    }
}
