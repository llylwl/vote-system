package com.vote.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 防刷拦截统计服务
 * 以「天」为粒度在 Redis 中累计各类拦截次数，供控制台可视化展示
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteStatsService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 统计 Key 前缀：vote:stats:{type}:{yyyy-MM-dd} */
    private static final String STATS_PREFIX = "vote:stats:";

    /** 活动不可投（未开始/已结束） */
    public static final String TYPE_INVALID = "invalid";
    /** 黑名单拦截 */
    public static final String TYPE_BLACKLIST = "blacklist";
    /** 今日重复投票拦截 */
    public static final String TYPE_DUPLICATE = "duplicate";
    /** 限流拦截 */
    public static final String TYPE_RATELIMIT = "ratelimit";

    /** 拦截类型 +1 */
    public void incr(String type) {
        try {
            String key = STATS_PREFIX + type + ":" + LocalDate.now();
            stringRedisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.warn("统计计数失败 type={}", type, e);
        }
    }

    /** 获取今日各类拦截次数 */
    public Map<String, Long> getTodayStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put(TYPE_INVALID, getCount(TYPE_INVALID));
        stats.put(TYPE_BLACKLIST, getCount(TYPE_BLACKLIST));
        stats.put(TYPE_DUPLICATE, getCount(TYPE_DUPLICATE));
        stats.put(TYPE_RATELIMIT, getCount(TYPE_RATELIMIT));
        return stats;
    }

    private long getCount(String type) {
        try {
            String v = stringRedisTemplate.opsForValue().get(STATS_PREFIX + type + ":" + LocalDate.now());
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }
}
