package com.vote.security;

import com.vote.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与校验
 * <p>
 * 令牌中携带 {@code jti}（唯一 ID）与 {@code role}。
 * <b>jti 是这套方案的关键</b>：JWT 本身无状态、签发后无法提前失效，
 * 配合 {@link TokenStore} 的 Redis 白名单后，"退出登录"和"强制下线"才能真正生效。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    /** 自定义声明：角色 */
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties jwtProperties;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        // JwtProperties 已在启动时校验过密钥长度，这里不会再失败
        this.secretKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发令牌
     *
     * @param userId 用户 ID，写入 subject
     * @param role   角色，写入自定义声明
     */
    public IssuedToken issue(Long userId, String role) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString().replace("-", "");

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.getExpireSeconds())))
                .claim(CLAIM_ROLE, role)
                .signWith(secretKey)
                .compact();

        return new IssuedToken(token, jti, jwtProperties.getExpireSeconds());
    }

    /**
     * 解析并验签令牌
     * <p>
     * 只负责密码学层面的校验（签名、签发者、有效期）。
     * <b>是否已被登出需要另行查询白名单</b>，见 {@link TokenStore#isActive}。
     *
     * @return 解析结果；签名错误、已过期或格式非法时返回 null
     */
    public ParsedToken parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(jwtProperties.getIssuer())
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();
            return new ParsedToken(
                    Long.valueOf(claims.getSubject()),
                    claims.getId(),
                    claims.get(CLAIM_ROLE, String.class));

        } catch (ExpiredJwtException e) {
            // 过期是最常见的正常情况，用 debug 级别避免刷日志
            log.debug("令牌已过期: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不匹配、格式非法等：可能是攻击尝试，值得记录
            log.warn("令牌校验失败: {}", e.getMessage());
        }
        return null;
    }

    public long getExpireSeconds() {
        return jwtProperties.getExpireSeconds();
    }

    /** 签发结果 */
    public record IssuedToken(String token, String jti, long expiresIn) {
    }

    /** 解析结果 */
    public record ParsedToken(Long userId, String jti, String role) {
    }
}
