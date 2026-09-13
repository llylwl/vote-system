package com.vote.common.constant;

/**
 * Redis Key 常量
 * @author hzp
 * @since 2026-9-15
 */
public final class RedisKeys {

    /** 活动信息 Hash：vote:activity:info:{activityId} */
    public static final String ACTIVITY_INFO = "vote:activity:info:";

    /** 用户今日投票标记：vote:user:today:{activityId}:{userId} */
    public static final String USER_TODAY = "vote:user:today:";

    /** 黑名单：vote:blacklist:{USER|IP|DEVICE}:{value} */
    public static final String BLACKLIST = "vote:blacklist:";

    /** Outbox 队列（Redis List）：vote:outbox:queue */
    public static final String OUTBOX_QUEUE = "vote:outbox:queue";

    /** 排行榜 ZSet：vote:rank:{activityId} */
    public static final String RANK = "vote:rank:";

    /** TopN 缓存：vote:topn:{activityId}:{n} */
    public static final String TOPN_CACHE = "vote:topn:";

    /** 落库幂等：vote:idempotent:{activityId}:{userId}:{date} */
    public static final String IDEMPOTENT = "vote:idempotent:";

    /** 缓存互斥锁：vote:mutex:lock:{key} */
    public static final String MUTEX_LOCK_PREFIX = "vote:mutex:lock:";

    /** 逻辑过期重建锁：vote:logical:lock:{key} */
    public static final String LOGICAL_LOCK_PREFIX = "vote:logical:lock:";

    /** 限流 Key 前缀：rate_limit:{维度} */
    public static final String RATE_LIMIT = "rate_limit:";

    /** 活动详情缓存：vote:activity:detail:{activityId} */
    public static final String ACTIVITY_DETAIL = "vote:activity:detail:";

    private RedisKeys() {
    }
}
