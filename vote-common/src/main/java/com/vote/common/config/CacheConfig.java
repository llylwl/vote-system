package com.vote.common.config;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存配置常量
 *
 * @author hzp
 * @since 2026-9-13
 */
public final class CacheConfig {

    /** 默认缓存过期时间（秒） */
    public static final long DEFAULT_TTL_SECONDS = 3600L;

    /**
     * 空值缓存过期时间（秒）
     * <p>
     * 数据库确实查不到的数据（如不存在的活动 ID）也要写一份占位缓存，TTL 取较短值。
     * 否则此类请求永远不命中缓存，会全部落到数据库；并发一大就把连接池和线程池占满。
     */
    public static final long NULL_VALUE_TTL_SECONDS = 60L;

    /**
     * 获取缓存重建锁的最长等待时间（毫秒）
     * <p>
     * 等待期间由 Redisson 负责阻塞与唤醒，超时后降级为直接回源。
     * 早期实现用「sleep 50ms 后无限递归」自旋，且没有次数上限：
     * 大量并发查一个不存在的 Key 时，所有 Web 线程都会卡在递归里，
     * 最终耗尽 Tomcat 线程池，连投票接口一起被拖死。
     */
    public static final long MUTEX_LOCK_WAIT_MS = 3000L;

    /** TTL 抖动比例（±10%），避免同批写入的缓存同时过期引发缓存雪崩 */
    private static final double TTL_JITTER_RATIO = 0.1D;

    /** 逻辑过期提前量（秒），逻辑过期时间比物理过期时间提前的秒数 */
    public static final long LOGICAL_EXPIRE_ADVANCE_SECONDS = 300L;

    /**
     * 逻辑过期重建的最小逻辑 TTL（秒）
     * <p>
     * 调用方传入的 TTL 若小于 {@link #LOGICAL_EXPIRE_ADVANCE_SECONDS}，
     * 直接相减会得到负数，写进去就是「已逻辑过期」，导致每次读都触发重建、缓存形同虚设。
     * 这里保证逻辑 TTL 至少为正值。
     */
    public static final long MIN_LOGICAL_TTL_SECONDS = 1L;

    private CacheConfig() {
    }

    /**
     * 在基础 TTL 上叠加 ±10% 的随机抖动
     *
     * @param baseTtlSeconds 基础过期时间（秒）
     * @return 抖动后的过期时间，最小不低于 1 秒
     */
    public static long ttlWithJitter(long baseTtlSeconds) {
        long jitter = (long) (baseTtlSeconds * TTL_JITTER_RATIO);
        if (jitter <= 0) {
            return Math.max(baseTtlSeconds, 1L);
        }
        long delta = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Math.max(baseTtlSeconds + delta, 1L);
    }
}
