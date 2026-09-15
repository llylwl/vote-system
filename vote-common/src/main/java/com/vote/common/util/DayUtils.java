package com.vote.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 「自然日」相关的时间工具
 * <p>
 * 业务语义「每天一票」必须以自然日为界（00:00 重置），而不是「距上次投票 24 小时」。
 * Redis 侧的每日标记与数据库唯一索引 {@code uk_activity_user_date} 都依赖这里的口径，
 * 两者必须一致，否则跨天投票会被误拦。
 *
 * @author hzp
 * @since 2026-9-15
 */
public final class DayUtils {

    /** 紧凑日期格式，用于拼 Redis Key：yyyyMMdd */
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** ISO 日期格式，用于拼幂等 Key：yyyy-MM-dd */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private DayUtils() {
    }

    /** 今天的紧凑日期串（yyyyMMdd），用作按天切分的 Key 片段 */
    public static String todayCompact() {
        return todayCompact(LocalDateTime.now());
    }

    /** 指定时刻所属的紧凑日期串（yyyyMMdd） */
    public static String todayCompact(LocalDateTime now) {
        return now.toLocalDate().format(COMPACT_DATE);
    }

    /** 指定时刻所属的紧凑日期串（yyyyMMdd） */
    public static String compactOf(LocalDateTime dateTime) {
        return dateTime.toLocalDate().format(COMPACT_DATE);
    }

    /** 指定时刻所属的 ISO 日期串（yyyy-MM-dd） */
    public static String isoOf(LocalDateTime dateTime) {
        return dateTime.toLocalDate().format(ISO_DATE);
    }

    /** 毫秒时间戳所属的 ISO 日期串（yyyy-MM-dd） */
    public static String isoOfEpochMilli(long epochMilli) {
        return toLocalDateTime(epochMilli).toLocalDate().format(ISO_DATE);
    }

    /** 毫秒时间戳转 LocalDateTime（系统时区） */
    public static LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    /**
     * 距离「本地次日 00:00」还剩多少秒。
     * <p>
     * 用作 Redis 每日投票标记的 TTL：标记在当天结束时自然失效，
     * 用户第二天 00:00 起即可重新投票。不足 1 秒时返回 1，避免 TTL 为 0。
     */
    public static long secondsUntilNextMidnight() {
        return secondsUntilNextMidnight(LocalDateTime.now());
    }

    /**
     * 距离「指定时刻的次日 00:00」还剩多少秒（便于测试跨天边界）
     *
     * @param now 参照时刻
     * @return 剩余秒数，最小为 1
     */
    public static long secondsUntilNextMidnight(LocalDateTime now) {
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, nextMidnight).getSeconds();
        return Math.max(seconds, 1L);
    }
}
