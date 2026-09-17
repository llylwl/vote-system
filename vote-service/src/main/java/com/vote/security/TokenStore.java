package com.vote.security;

import com.vote.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 访问令牌白名单
 * <p>
 * JWT 是自包含的，签发后服务端无法单方面让它失效。
 * 本类在 Redis 中维护「哪些 jti 仍然有效」，使以下能力成为可能：
 * <ul>
 *   <li>用户主动退出登录</li>
 *   <li>用户改密码后踢掉其它设备</li>
 *   <li>管理员强制下线某个用户</li>
 * </ul>
 * <p>
 * <b>可用性取舍：</b>{@link #isActive} 在 Redis 不可用时返回 {@code false}（fail-closed）。
 * 这是刻意的选择 —— 若降级为放行，就等于"Redis 一挂，所有已登出的令牌全部复活"。
 * 对本项目影响有限：投票链路本身也依赖 Redis，Redis 挂掉时投票本来就不可用。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStore {

    private final StringRedisTemplate redisTemplate;

    /** 登录成功后写入白名单，并登记到该用户的反向索引 */
    public void register(String jti, Long userId, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.AUTH_TOKEN + jti, String.valueOf(userId), ttlSeconds, TimeUnit.SECONDS);

            String userTokensKey = RedisKeys.AUTH_USER_TOKENS + userId;
            redisTemplate.opsForSet().add(userTokensKey, jti);
            // 反向索引的 TTL 与令牌一致：最后一个令牌过期后，这个 Set 也会被回收
            redisTemplate.expire(userTokensKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 白名单写入失败不阻断登录：令牌本身仍然可用，只是无法主动失效
            log.error("写入令牌白名单失败，该令牌将无法被主动登出: jti={}", jti, e);
        }
    }

    /**
     * 校验令牌是否仍在白名单内
     *
     * @return true-有效；false-已登出或 Redis 不可用
     */
    public boolean isActive(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.AUTH_TOKEN + jti));
        } catch (Exception e) {
            log.error("查询令牌白名单失败，按无效处理（fail-closed）: jti={}", jti, e);
            return false;
        }
    }

    /** 登出单个令牌 */
    public void revoke(String jti, Long userId) {
        try {
            redisTemplate.delete(RedisKeys.AUTH_TOKEN + jti);
            if (userId != null) {
                redisTemplate.opsForSet().remove(RedisKeys.AUTH_USER_TOKENS + userId, jti);
            }
        } catch (Exception e) {
            log.error("登出令牌失败: jti={}", jti, e);
        }
    }

    /**
     * 撤销该用户的全部令牌（退出所有设备 / 改密码后踢人）
     *
     * @return 被撤销的令牌数量
     */
    public int revokeAll(Long userId) {
        String userTokensKey = RedisKeys.AUTH_USER_TOKENS + userId;
        try {
            Set<String> jtis = redisTemplate.opsForSet().members(userTokensKey);
            if (jtis == null || jtis.isEmpty()) {
                return 0;
            }
            List<String> tokenKeys = jtis.stream().map(jti -> RedisKeys.AUTH_TOKEN + jti).toList();
            redisTemplate.delete(tokenKeys);
            redisTemplate.delete(userTokensKey);
            log.info("已撤销用户 {} 的全部令牌，共 {} 个", userId, jtis.size());
            return jtis.size();

        } catch (Exception e) {
            log.error("撤销用户全部令牌失败: userId={}", userId, e);
            return 0;
        }
    }
}
