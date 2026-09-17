package com.vote.service.rank;

import com.vote.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Redis ZSet 的实时排行榜服务
 * 提供投票计数、TopN 查询、排名查询等核心功能
 * <p>
 * 所有返回集合的查询都做了<b>结果条数上限</b>：Redis 是单线程执行命令的，
 * 客户端传入 {@code ?end=99999999} 会构造出一个上亿元素的响应，阻塞 Redis 上所有其它请求，
 * 应用侧还会把这个结果物化成 List 导致内存暴涨 —— 一个请求即可打挂整个投票系统。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteRankService {

    /** 单次查询允许返回的最大条目数 */
    public static final int MAX_PAGE_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;

    private String getRankKey(Long activityId) {
        return RedisKeys.RANK + activityId;
    }

    /** 投票计数 +1（ZINCRBY） */
    public Double voteIncrement(Long activityId, Long targetId) {
        return stringRedisTemplate.opsForZSet()
                .incrementScore(getRankKey(activityId), String.valueOf(targetId), 1);
    }

    /** 获取 TopN 排行榜（按票数降序）；n 会被夹紧到 1 ~ {@link #MAX_PAGE_SIZE} */
    public List<Map<String, Object>> getTopN(Long activityId, int n) {
        int safeN = clampSize(n);
        if (safeN != n) {
            log.warn("TopN 参数越界已夹紧: activityId={}, 请求 n={}, 实际 n={}", activityId, n, safeN);
        }
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(getRankKey(activityId), 0, safeN - 1);
        return toRankList(tuples, 1);
    }

    /** 获取指定目标的排名（1-based），不存在返回 null */
    public Long getRank(Long activityId, Long targetId) {
        Long rank = stringRedisTemplate.opsForZSet()
                .reverseRank(getRankKey(activityId), String.valueOf(targetId));
        return rank == null ? null : rank + 1;
    }

    /** 获取指定目标的票数 */
    public Long getVoteCount(Long activityId, Long targetId) {
        Double score = stringRedisTemplate.opsForZSet()
                .score(getRankKey(activityId), String.valueOf(targetId));
        return score != null ? score.longValue() : 0L;
    }

    /**
     * 获取指定范围的排名列表
     * <p>
     * {@code start}/{@code end} 会被夹紧：起始不小于 0，且单次返回条数不超过 {@link #MAX_PAGE_SIZE}。
     */
    public List<Map<String, Object>> getActivityRanking(Long activityId, long start, long end) {
        long safeStart = Math.max(start, 0L);
        long safeEnd = Math.min(end, safeStart + MAX_PAGE_SIZE - 1);
        if (safeStart != start || safeEnd != end) {
            log.warn("排行榜分页参数越界已夹紧: activityId={}, 请求 start={} end={}, 实际 start={} end={}",
                    activityId, start, end, safeStart, safeEnd);
        }
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(getRankKey(activityId), safeStart, safeEnd);
        return toRankList(tuples, safeStart + 1);
    }

    /** 活动总票数（读取活动 Hash 中 Lua 实时累加的 total_votes） */
    public Long getActivityTotal(Long activityId) {
        Object v = stringRedisTemplate.opsForHash().get(RedisKeys.ACTIVITY_INFO + activityId, "total_votes");
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            log.warn("活动 total_votes 非法，按 0 处理: activityId={}, value={}", activityId, v);
            return 0L;
        }
    }

    private int clampSize(int n) {
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_PAGE_SIZE);
    }

    private List<Map<String, Object>> toRankList(Set<ZSetOperations.TypedTuple<String>> tuples, long startRank) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples != null) {
            long rank = startRank;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", rank++);
                // 成员可能是历史脏数据（非数字），不能让它把整个接口打成 500
                String member = tuple.getValue();
                try {
                    item.put("targetId", member == null ? null : Long.valueOf(member));
                } catch (NumberFormatException e) {
                    log.warn("排行榜存在非法成员，已跳过: member={}", member);
                    rank--;
                    continue;
                }
                item.put("voteCount", tuple.getScore() != null ? tuple.getScore().longValue() : 0L);
                result.add(item);
            }
        }
        return result;
    }
}
