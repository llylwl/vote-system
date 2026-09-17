package com.vote.limiter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis ZSet + Lua 的滑动窗口限流器
 * 原子地移除过期数据 + 统计当前窗口请求数 + 添加新请求，避免固定窗口的临界突刺
 * @author hzp
 * @since 2026-9-13
 */
@Component
@RequiredArgsConstructor
public class SlidingWindowLimiter {

    private final StringRedisTemplate redisTemplate;

    /** Lua：移除过期数据 + 统计窗口内请求数 + 未超限则写入（member 带随机后缀保证唯一） */
    private static final String LUA_SCRIPT =
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) " +
            "local count = redis.call('ZCARD', KEYS[1]) " +
            "if count < tonumber(ARGV[3]) then " +
            "    redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2] .. ':' .. math.random()) " +
            "    redis.call('PEXPIRE', KEYS[1], ARGV[4]) " +
            "    return 1 " +
            "end " +
            "return 0";

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setScriptText(LUA_SCRIPT);
        SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取限流令牌
     *
     * @param key          限流维度 Key
     * @param limit        窗口内允许的最大请求数
     * @param timeWindowMs 时间窗口大小（毫秒）
     * @return true 允许通过，false 被限流
     */
    public boolean tryAcquire(String key, int limit, long timeWindowMs) {
        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - timeWindowMs;
        List<String> keys = Collections.singletonList(key);
        String[] args = new String[]{
                String.valueOf(windowStart),   // ARGV[1]: 窗口起始时间
                String.valueOf(currentTime),   // ARGV[2]: 当前时间（score）
                String.valueOf(limit),         // ARGV[3]: 限制数量
                String.valueOf(timeWindowMs)   // ARGV[4]: 过期时间（毫秒）
        };
        Long result = redisTemplate.execute(SCRIPT, keys, args);
        return result != null && result == 1L;
    }
}
