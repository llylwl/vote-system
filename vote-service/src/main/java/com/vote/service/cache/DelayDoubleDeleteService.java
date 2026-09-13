package com.vote.service.cache;

import com.vote.common.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 延迟双删工具类
 * Cache-Aside Pattern + 延迟双删，保证缓存与数据库的最终一致性
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayDoubleDeleteService {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 延迟删除调度线程池 */
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);

    /** 执行延迟双删（更新数据库前先调用） */
    public void deleteWithDelay(String key) {
        // 第一次删除：立即删除缓存
        redisTemplate.delete(key);
        // 第二次删除：延迟后删除，清除更新数据库期间被其他线程写入的脏数据
        SCHEDULER.schedule(() -> {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.error("延迟双删失败, key={}", key, e);
            }
        }, CacheConfig.DELAY_DOUBLE_DELETE_MS, TimeUnit.MILLISECONDS);
    }
}
