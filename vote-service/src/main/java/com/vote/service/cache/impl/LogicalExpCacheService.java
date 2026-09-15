package com.vote.service.cache.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vote.common.config.CacheConfig;
import com.vote.common.constant.RedisKeys;
import com.vote.service.cache.ICacheService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 基于逻辑过期的缓存防击穿实现
 * <p>
 * 物理过期时间长、逻辑过期时间短；逻辑过期后异步重建，当前请求直接返回旧值以保高可用。
 * 适合「可容忍短暂旧值」的读多写少场景（如活动详情）。
 * <p>
 * <b>相比早期实现修正的问题：</b>
 * <ol>
 *   <li><b>空值路径完全没有互斥</b>：原实现只在「非空且已逻辑过期」这条分支上加锁，
 *       而缓存不存在时（首次加载、被 evict 之后）直接同步回源，N 个并发请求会一起打到数据库 ——
 *       这恰恰是防击穿最该保护的主场景。现在空值路径同样走分布式锁保护；</li>
 *   <li><b>重建线程池打满会阻塞请求线程</b>：原实现用 {@code CallerRunsPolicy}，
 *       队列满时由请求线程同步执行重建，延迟尖刺，把「高可用」的初衷抵消掉。
 *       现在改为丢弃策略：队列满就跳过本次重建（旧值仍可正常返回），只记录告警；</li>
 *   <li><b>TTL 下界未校验</b>：原实现直接算 {@code ttl - 提前量}，调用方传的 TTL 小于提前量时
 *       会得到过去的逻辑过期时间，导致每次读都触发重建、缓存形同虚设；</li>
 *   <li><b>静态线程池永不关闭</b>：改用实例字段并由 {@link PreDestroy} 关闭，线程设为守护线程，
 *       避免阻止 JVM 退出；</li>
 *   <li><b>兼容分支会抛 ClassCastException</b>：缓存里若是别的组件写入的非包装类型，
 *       原实现直接强转返回，会在用户请求上抛类型转换异常。现在按未命中处理并覆盖。</li>
 * </ol>
 *
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service("logicalExpCacheService")
public class LogicalExpCacheService implements ICacheService {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    /** 异步重建线程池：守护线程 + 有界队列；队列满时丢弃本次重建（旧值仍可返回） */
    private final ExecutorService rebuildExecutor;

    public LogicalExpCacheService(RedisTemplate<String, Object> redisTemplate, RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.rebuildExecutor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "cache-rebuild-" + THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
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
        Object cached = readCache(key);

        CacheWrapper wrapper = asWrapper(key, cached);
        if (wrapper == null) {
            // 缓存不存在（或格式异常按未命中处理）：此路径同样必须加锁，
            // 否则并发首次加载会同时穿透到数据库
            return loadWithLock(key, dbFallback);
        }

        boolean logicallyExpired = wrapper.getExpireTime() != null
                && System.currentTimeMillis() > wrapper.getExpireTime();
        if (!logicallyExpired) {
            return (T) wrapper.getData();
        }

        // 已逻辑过期：异步重建，当前请求仍返回旧值
        triggerAsyncRebuild(key, dbFallback);
        return (T) wrapper.getData();
    }

    /** 缓存缺失时的加锁回源（与互斥锁策略同样的有界等待 + 超时降级） */
    @SuppressWarnings("unchecked")
    private <T> T loadWithLock(String key, Supplier<T> dbFallback) {
        RLock lock = redissonClient.getLock(RedisKeys.LOGICAL_LOCK_PREFIX + key);
        boolean locked = false;
        try {
            locked = lock.tryLock(CacheConfig.MUTEX_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待逻辑过期重建锁被中断, key={}", key);
        }

        if (!locked) {
            log.warn("获取逻辑过期重建锁超时，降级为直接回源, key={}", key);
            return dbFallback.get();
        }

        try {
            // 双重检查
            CacheWrapper again = asWrapper(key, readCache(key));
            if (again != null) {
                return (T) again.getData();
            }
            T data = dbFallback.get();
            if (data != null) {
                put(key, data, CacheConfig.DEFAULT_TTL_SECONDS);
            }
            return data;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 提交异步重建任务；线程池满时跳过（旧值仍可返回），不影响请求 */
    private <T> void triggerAsyncRebuild(String key, Supplier<T> dbFallback) {
        RLock lock = redissonClient.getLock(RedisKeys.LOGICAL_LOCK_PREFIX + key);
        boolean locked = false;
        try {
            // 重建锁不需要等待：抢不到说明已有线程在重建，直接返回旧值即可
            locked = lock.tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("获取逻辑过期重建锁异常, key={}", key, e);
            return;
        }
        if (!locked) {
            return;
        }

        try {
            rebuildExecutor.execute(() -> {
                try {
                    T newData = dbFallback.get();
                    if (newData != null) {
                        put(key, newData, CacheConfig.DEFAULT_TTL_SECONDS);
                    }
                } catch (Exception e) {
                    log.error("异步重建缓存失败, key={}", key, e);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // 重建线程池已满：跳过本次重建。旧值仍在缓存中，本次请求不受影响
            log.warn("缓存重建线程池已满，跳过本次异步重建, key={}", key);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        // 逻辑过期时间 = 当前时间 + 逻辑 TTL；逻辑 TTL 至少为正值，
        // 否则调用方传入小于提前量的 TTL 时会写出一个「一写入就过期」的缓存
        long logicalTtlSeconds = Math.max(
                ttlSeconds - CacheConfig.LOGICAL_EXPIRE_ADVANCE_SECONDS,
                CacheConfig.MIN_LOGICAL_TTL_SECONDS);
        long logicalExpireTime = System.currentTimeMillis() + logicalTtlSeconds * 1000;

        CacheWrapper wrapper = new CacheWrapper(value, logicalExpireTime);
        // 物理过期时间比逻辑过期时间长，保证异步重建期间旧值不丢
        long physicalTtl = CacheConfig.ttlWithJitter(
                logicalTtlSeconds + CacheConfig.LOGICAL_EXPIRE_ADVANCE_SECONDS);
        try {
            redisTemplate.opsForValue().set(key, wrapper, physicalTtl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入逻辑过期缓存失败, key={}", key, e);
        }
    }

    /**
     * 删除缓存。
     * <p>
     * 这里保持「直接删除」语义（Cache-Aside 的标准做法）：
     * 由于 {@link #loadWithLock} 已经为「缓存不存在」这条路径加了互斥保护，
     * 删除后不会引发并发穿透，无需写回一个「已过期」的包装值。
     */
    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除缓存失败, key={}", key, e);
        }
    }

    /** 把缓存值解析为包装类型；非包装类型按未命中处理，避免强转抛 ClassCastException */
    private CacheWrapper asWrapper(String key, Object cached) {
        if (cached == null) {
            return null;
        }
        if (cached instanceof CacheWrapper wrapper) {
            return wrapper;
        }
        log.warn("缓存值与逻辑过期格式不匹配，按未命中处理并覆盖, key={}, type={}",
                key, cached.getClass().getName());
        return null;
    }

    private Object readCache(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取缓存失败，降级为直接回源, key={}", key, e);
            return null;
        }
    }
}
