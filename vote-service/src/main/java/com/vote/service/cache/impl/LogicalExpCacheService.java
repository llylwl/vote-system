package com.vote.service.cache.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vote.common.config.CacheConfig;
import com.vote.common.constant.RedisKeys;
import com.vote.service.cache.ICacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于逻辑过期的缓存防击穿实现
 * 物理过期时间长，逻辑过期时间短；逻辑过期后异步重建缓存，当前线程返回旧数据（高可用）
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service("logicalExpCacheService")
public class LogicalExpCacheService implements ICacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 异步重建线程池 */
    private static final ExecutorService REBUILD_EXECUTOR = new ThreadPoolExecutor(
            2, 4, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public LogicalExpCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 缓存包装类：业务数据 + 逻辑过期时间戳 */
    public static class CacheWrapper {
        @JsonProperty("data")
        private Object data;
        @JsonProperty("expireTime")
        private Long expireTime;

        public CacheWrapper() {
        }

        public CacheWrapper(Object data, Long expireTime) {
            this.data = data;
            this.expireTime = expireTime;
        }

        public Object getData() { return data; }

        public void setData(Object data) { this.data = data; }

        public Long getExpireTime() { return expireTime; }

        public void setExpireTime(Long expireTime) { this.expireTime = expireTime; }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getWithProtection(String key, Supplier<T> dbFallback) {
        // 1. 查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            // 缓存完全不存在，同步查库并初始化（首次加载）
            T data = dbFallback.get();
            if (data != null) {
                put(key, data, CacheConfig.DEFAULT_TTL_SECONDS);
            }
            return data;
        }

        // 2. 解析逻辑过期时间
        CacheWrapper wrapper;
        if (cached instanceof CacheWrapper) {
            wrapper = (CacheWrapper) cached;
        } else {
            // 兼容旧格式数据
            return (T) cached;
        }

        // 3. 判断是否逻辑过期
        boolean isExpired = wrapper.getExpireTime() != null
                && System.currentTimeMillis() > wrapper.getExpireTime();
        if (!isExpired) {
            return (T) wrapper.getData();
        }

        // 4. 已逻辑过期，尝试获取异步重建锁
        String lockKey = RedisKeys.LOGICAL_LOCK_PREFIX + key;
        boolean locked = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, "1",
                        CacheConfig.MUTEX_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (locked) {
            // 5. 获取锁成功，异步重建缓存
            REBUILD_EXECUTOR.execute(() -> {
                try {
                    T newData = dbFallback.get();
                    if (newData != null) {
                        put(key, newData, CacheConfig.DEFAULT_TTL_SECONDS);
                    }
                } catch (Exception e) {
                    log.error("异步重建缓存失败, key={}", key, e);
                } finally {
                    redisTemplate.delete(lockKey);
                }
            });
        }

        // 6. 无论是否获取到锁，都返回旧数据（保证高可用）
        return (T) wrapper.getData();
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        // 逻辑过期时间 = 当前时间 + (物理TTL - 提前量)
        long logicalExpireTime = System.currentTimeMillis()
                + (ttlSeconds - CacheConfig.LOGICAL_EXPIRE_ADVANCE_SECONDS) * 1000;
        CacheWrapper wrapper = new CacheWrapper(value, logicalExpireTime);
        // 物理过期时间设置得比逻辑过期时间长，保证异步重建期间缓存不丢失
        long physicalTtl = ttlSeconds + CacheConfig.LOGICAL_EXPIRE_ADVANCE_SECONDS;
        redisTemplate.opsForValue().set(key, wrapper, physicalTtl, TimeUnit.SECONDS);
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }
}
