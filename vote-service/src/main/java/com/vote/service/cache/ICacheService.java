package com.vote.service.cache;

import java.util.function.Supplier;

/**
 * 缓存服务接口
 * 定义通用的缓存操作方法，支持防击穿策略
 * @author hzp
 * @since 2026-9-15
 */
public interface ICacheService {

    /**
     * 带防击穿保护的缓存获取
     *
     * @param key        缓存 Key
     * @param dbFallback 缓存未命中时的数据库回查逻辑
     */
    <T> T getWithProtection(String key, Supplier<T> dbFallback);

    /** 写入缓存 */
    void put(String key, Object value, long ttlSeconds);

    /** 删除缓存 */
    void evict(String key);
}
