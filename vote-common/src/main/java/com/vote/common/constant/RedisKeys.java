package com.vote.common.constant;

/**
 * Redis Key 常量
 * @author hzp
 * @since 2026-9-13
 */
public final class RedisKeys {

    /** 活动信息 Hash：vote:activity:info:{activityId} */
    public static final String ACTIVITY_INFO = "vote:activity:info:";

    /**
     * 用户今日投票标记：vote:user:today:{activityId}:{yyyyMMdd}:{userId}
     * <p>
     * Key 内含自然日，TTL 到当天 24 点为止，与数据库唯一索引
     * {@code uk_activity_user_date (activity_id, user_id, DATE(vote_time))} 口径一致。
     * 早期实现用固定 24 小时 TTL 且 Key 不含日期，等于「距上次投票 24 小时一票」，
     * 会导致 23:59 投过票的用户次日整天无法投票。
     */
    public static final String USER_TODAY = "vote:user:today:";

    /** 黑名单：vote:blacklist:{USER|IP|DEVICE}:{value} */
    public static final String BLACKLIST = "vote:blacklist:";

    /** Outbox 队列（Redis List）：vote:outbox:queue */
    public static final String OUTBOX_QUEUE = "vote:outbox:queue";

    /**
     * Outbox 处理中队列：vote:outbox:processing
     * <p>
     * Publisher 用 RPOPLPUSH 把消息原子地从待投递队列搬到此处，投递并收到 Broker 确认后再移除。
     * 若进程在投递途中崩溃，消息仍留在此队列，下次启动会被搬回待投递队列，不会丢。
     */
    public static final String OUTBOX_PROCESSING_QUEUE = "vote:outbox:processing";

    /** 排行榜 ZSet：vote:rank:{activityId} */
    public static final String RANK = "vote:rank:";

    /** TopN 缓存：vote:topn:{activityId}:{n} */
    public static final String TOPN_CACHE = "vote:topn:";

    /** 落库幂等：vote:idempotent:{activityId}:{userId}:{yyyy-MM-dd} */
    public static final String IDEMPOTENT = "vote:idempotent:";

    /** 缓存互斥锁：vote:mutex:lock:{key} */
    public static final String MUTEX_LOCK_PREFIX = "vote:mutex:lock:";

    /** 逻辑过期重建锁：vote:logical:lock:{key} */
    public static final String LOGICAL_LOCK_PREFIX = "vote:logical:lock:";

    /** 限流 Key 前缀：rate_limit:{维度} */
    public static final String RATE_LIMIT = "rate_limit:";

    /** 活动详情缓存：vote:activity:detail:{activityId} */
    public static final String ACTIVITY_DETAIL = "vote:activity:detail:";

    /** 活动状态定时任务的分布式锁：vote:lock:activity:status:task */
    public static final String ACTIVITY_STATUS_TASK_LOCK = "vote:lock:activity:status:task";

    /** 票数对账定时任务的分布式锁：vote:lock:reconcile:task */
    public static final String RECONCILE_TASK_LOCK = "vote:lock:reconcile:task";

    // ------------------------------------------------------------
    // 认证相关
    // ------------------------------------------------------------

    /**
     * 访问令牌白名单：vote:auth:token:{jti} → userId
     * <p>
     * JWT 本身是无状态的，签发后无法提前失效。这里在 Redis 中保留一份白名单，
     * 让「退出登录」「强制下线」「改密码踢人」能够真正生效 ——
     * 校验时既验签名，也确认该 jti 仍在白名单内。
     */
    public static final String AUTH_TOKEN = "vote:auth:token:";

    /**
     * 用户令牌反向索引：vote:auth:user-tokens:{userId} → Set&lt;jti&gt;
     * <p>
     * 用于「退出所有设备」：取出该用户全部 jti 后逐一从白名单移除。
     * 没有它就只能遍历 keys，在生产环境是不可接受的。
     */
    public static final String AUTH_USER_TOKENS = "vote:auth:user-tokens:";

    /**
     * 登录失败计数：vote:auth:fail:{username} → 次数
     * <p>
     * 用于账号级锁定。IP 限流挡不住分布在大量 IP 上的撞库攻击，
     * 必须再有一层以「账号」为维度的计数。
     */
    public static final String AUTH_LOGIN_FAIL = "vote:auth:fail:";

    /**
     * 用户最新状态缓存：vote:auth:user-state:{userId} → "role|active"
     * <p>
     * 令牌里的 role 是签发那一刻的快照。若管理员的角色被降级或账号被封禁，
     * 旧令牌在有效期内（默认 7 天）仍会带着旧权限通过校验 —— 这是必须堵上的漏洞。
     * 因此每次校验都读一次这里的「当前状态」，用短 TTL 缓存避免每请求都查库。
     */
    public static final String AUTH_USER_STATE = "vote:auth:user-state:";

    private RedisKeys() {
    }
}
