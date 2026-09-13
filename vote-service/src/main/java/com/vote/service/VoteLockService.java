package com.vote.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁服务
 * 支持 Watchdog 自动续期，防止业务执行时间过长导致锁提前释放
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteLockService {

    private final RedissonClient redissonClient;

    /**
     * 尝试获取锁并执行业务逻辑
     *
     * @param lockKey  锁的 Key
     * @param waitTime 等待获取锁的最大时间（毫秒）
     * @param supplier 业务逻辑
     * @return 业务执行结果
     */
    public <T> T executeWithLock(String lockKey, long waitTime, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            // 不设置 leaseTime 以启用 Watchdog 自动续期
            acquired = lock.tryLock(waitTime, TimeUnit.MILLISECONDS);
            if (acquired) {
                return supplier.get();
            }
            log.warn("获取分布式锁超时: lockKey={}", lockKey);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取分布式锁被中断: lockKey={}", lockKey, e);
            return null;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
