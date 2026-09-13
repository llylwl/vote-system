package com.vote.service.cache.impl;

import com.vote.common.config.CacheConfig;
import com.vote.common.constant.RedisKeys;
import com.vote.service.cache.ICacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于互斥锁的缓存防击穿实现
 * 缓存未命中时，通过 Redis 分布式锁保证只有一个线程查库回写，其余线程休眠重试
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service("mutexCacheService")
public class MutexCacheService implements ICacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    public MutexCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getWithProtection(String key, Supplier<T> dbFallback) {
        // 1. 先查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return (T) cached;
        }

        // 2. 缓存未命中，尝试获取互斥锁
        String lockKey = RedisKeys.MUTEX_LOCK_PREFIX + key;
        boolean locked = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, "1",
                        CacheConfig.MUTEX_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        if (locked) {
            try {
                // 3. 获取锁成功，双重检查缓存（防止其他线程已回写）
                cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return (T) cached;
                }
                // 4. 查库并回写缓存
                T data = dbFallback.get();
                if (data != null) {
                    redisTemplate.opsForValue().set(key, data, CacheConfig.DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
                }
                return data;
            } finally {
                // 5. 释放锁
                redisTemplate.delete(lockKey);
            }
        }

        // 6. 获取锁失败，休眠后重试（实际生产建议加最大重试次数）
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return getWithProtection(key, dbFallback);
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }
}
