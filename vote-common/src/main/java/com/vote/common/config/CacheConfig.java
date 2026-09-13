package com.vote.common.config;

/**
 * 缓存配置常量
 * @author hzp
 * @since 2026-9-15
 */
public final class CacheConfig {

    /** 默认缓存过期时间（秒） */
    public static final long DEFAULT_TTL_SECONDS = 3600L;

    /** 互斥锁超时时间（秒） */
    public static final long MUTEX_LOCK_TIMEOUT_SECONDS = 10L;

    /** 逻辑过期提前量（秒），逻辑过期时间比物理过期时间提前的秒数 */
    public static final long LOGICAL_EXPIRE_ADVANCE_SECONDS = 300L;

    /** 延迟双删延迟时间（毫秒） */
    public static final long DELAY_DOUBLE_DELETE_MS = 500L;

    private CacheConfig() {
    }
}
