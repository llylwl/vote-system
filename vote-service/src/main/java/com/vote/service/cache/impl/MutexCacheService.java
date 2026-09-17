package com.vote.service.cache.impl;

import com.vote.common.config.CacheConfig;
import com.vote.common.constant.RedisKeys;
import com.vote.service.cache.ICacheService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于互斥锁的缓存防击穿实现
 * <p>
 * 缓存未命中时，用分布式锁保证只有一个线程回源，其余线程短暂等待后取用回写结果。
 * <p>
 * <b>相比早期实现修正的三处问题：</b>
 * <ol>
 *   <li><b>锁误删</b>：原实现用 {@code setIfAbsent(lockKey, "1", 10s)} 加无条件 {@code delete}，
 *       没有持锁者标识。回源慢于锁超时时锁会自动过期，线程 A 返回后删掉的是线程 B 的锁，
 *       互斥随即失效。现改用 Redisson {@code RLock}（看门狗自动续期 +
 *       {@code isHeldByCurrentThread()} 校验）彻底消除该问题；</li>
 *   <li><b>缓存穿透</b>：原实现只在 {@code data != null} 时回写，查不到的数据永远不缓存，
 *       恶意构造大量不存在的 ID 即可让请求全部打到数据库。现在空结果也会写一份短 TTL 占位；</li>
 *   <li><b>无限递归</b>：原实现抢锁失败就 {@code sleep(50ms)} 后递归调用自身，没有次数上限。
 *       并发查同一个不存在的 Key 时，除一个线程外全部卡在递归里，最终耗尽 Tomcat 线程池，
 *       连投票接口一起被拖死。现在改为在锁上阻塞等待固定时长，超时直接回源兜底。</li>
 * </ol>
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Service("mutexCacheService")
public class MutexCacheService implements ICacheService {

    /** 空值占位符：把「数据库确实查不到」这一事实也缓存下来，用于防止缓存穿透 */
    private static final String NULL_HOLDER = "__NULL__";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    public MutexCacheService(RedisTemplate<String, Object> redisTemplate, RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getWithProtection(String key, Supplier<T> dbFallback) {
        // 1. 先查缓存
        Object cached = readCache(key);
        if (cached != null) {
            // NULL_HOLDER 代表「数据库查不到」，直接返回 null，不再回源
            return NULL_HOLDER.equals(cached) ? null : (T) cached;
        }

        // 2. 未命中：在分布式锁保护下回源
        RLock lock = redissonClient.getLock(RedisKeys.MUTEX_LOCK_PREFIX + key);
        boolean locked = false;
        try {
            // 刻意不传 leaseTime：启用看门狗自动续期，回源耗时超过锁超时也不会提前释放
            locked = lock.tryLock(CacheConfig.MUTEX_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待缓存重建锁被中断, key={}", key);
        }

        if (!locked) {
            // 等待超时：降级为直连回源。有界，不会无限自旋
            log.warn("获取缓存重建锁超时，降级为直接回源, key={}", key);
            return dbFallback.get();
        }

        try {
            // 3. 双重检查：等锁期间可能已有其它线程完成回写
            Object again = readCache(key);
            if (again != null) {
                return NULL_HOLDER.equals(again) ? null : (T) again;
            }

            // 4. 回源并回写
            T data = dbFallback.get();
            writeCache(key, data);
            return data;
        } finally {
            // 只有仍由当前线程持有时才释放，避免误解锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void put(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入缓存失败, key={}", key, e);
        }
    }

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除缓存失败, key={}", key, e);
        }
    }

    /** 回写缓存；空结果写短 TTL 占位，防止穿透 */
    private void writeCache(String key, Object value) {
        try {
            if (value == null) {
                redisTemplate.opsForValue().set(key, NULL_HOLDER,
                        CacheConfig.ttlWithJitter(CacheConfig.NULL_VALUE_TTL_SECONDS), TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value,
                        CacheConfig.ttlWithJitter(CacheConfig.DEFAULT_TTL_SECONDS), TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // 写缓存失败不影响本次返回，仅记录日志
            log.warn("写入缓存失败, key={}", key, e);
        }
    }

    /** 读缓存；Redis 不可用时降级为「未命中」由回源逻辑接管 */
    private Object readCache(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取缓存失败，降级为直接回源, key={}", key, e);
            return null;
        }
    }
}
