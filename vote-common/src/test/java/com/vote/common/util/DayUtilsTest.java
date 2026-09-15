package com.vote.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DayUtils} 单元测试
 * <p>
 * 重点覆盖「每天一票」的跨天边界 —— 这是原实现中最严重的业务缺陷：
 * 用固定 86400 秒 TTL 且 Key 不含日期，23:59 投过票的用户次日整天都会被误拦。
 *
 * @author hzp
 * @since 2026-9-15
 */
class DayUtilsTest {

    @Test
    @DisplayName("当天最早的时刻：距次日 0 点应为 86400 秒")
    void 零时刻的剩余秒数为整天() {
        long seconds = DayUtils.secondsUntilNextMidnight(LocalDateTime.of(2026, 9, 16, 0, 0, 0));
        assertThat(seconds).isEqualTo(86400);
    }

    @Test
    @DisplayName("23:59:59 时距次日 0 点应为 1 秒（而非被夹紧为 0）")
    void 临界点至少为一秒() {
        long seconds = DayUtils.secondsUntilNextMidnight(LocalDateTime.of(2026, 9, 16, 23, 59, 59));
        assertThat(seconds).isEqualTo(1);
    }

    @Test
    @DisplayName("深夜投票时只剩当天剩余时间，而不是固定 86400")
    void 深夜投票只剩当天剩余时间() {
        // 真实发生过的场景：2026-09-15 23:19:28 投出的那一票。
        // 剩余 86400 - (23*3600 + 19*60 + 28) = 2432 秒，即当天 24 点整失效。
        // 原实现固定给 86400 秒，这个 Key 会一直活到次日 23:19，
        // 导致该用户次日一整天都被判为「今日已投」。
        long seconds = DayUtils.secondsUntilNextMidnight(LocalDateTime.of(2026, 9, 15, 23, 19, 28));
        assertThat(seconds).isEqualTo(2432);
        assertThat(seconds).isLessThan(86400);
    }

    @Test
    @DisplayName("剩余秒数始终落在 (0, 86400] 区间")
    void 任意时刻的剩余秒数都在合理区间() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 16, 0, 0, 0);
        for (int minute = 0; minute < 24 * 60; minute++) {
            long seconds = DayUtils.secondsUntilNextMidnight(base.plusMinutes(minute));
            assertThat(seconds).isGreaterThan(0).isLessThanOrEqualTo(86400);
        }
    }

    @Test
    @DisplayName("按天切分的 Key 片段格式为 yyyyMMdd，且跨天会变化")
    void 日期片段按自然日切换() {
        assertThat(DayUtils.todayCompact(LocalDateTime.of(2026, 9, 15, 23, 59, 59)))
                .isEqualTo("20260915");
        assertThat(DayUtils.todayCompact(LocalDateTime.of(2026, 9, 16, 0, 0, 0)))
                .isEqualTo("20260916");
    }

    @Test
    @DisplayName("ISO 日期串用于幂等 Key，与数据库唯一索引口径一致")
    void ISO日期串() {
        assertThat(DayUtils.isoOf(LocalDateTime.of(2026, 9, 15, 23, 59, 59)))
                .isEqualTo("2026-09-15");
    }

    @Test
    @DisplayName("毫秒时间戳按系统时区换算，跨天边界不能算错")
    void 毫秒时间戳换算() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 15, 23, 19, 30);
        long epochMilli = expected.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(DayUtils.isoOfEpochMilli(epochMilli)).isEqualTo("2026-09-15");
        assertThat(DayUtils.toLocalDateTime(epochMilli)).isEqualTo(expected);
    }
}
