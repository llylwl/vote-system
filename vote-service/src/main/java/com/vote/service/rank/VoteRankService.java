package com.vote.service.rank;

import com.vote.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
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
 * @author hzp
 * @since 2026-9-15
 */
@Service
@RequiredArgsConstructor
public class VoteRankService {

    private final StringRedisTemplate stringRedisTemplate;

    private String getRankKey(Long activityId) {
        return RedisKeys.RANK + activityId;
    }

    /** 投票计数 +1（ZINCRBY） */
    public Double voteIncrement(Long activityId, Long targetId) {
        return stringRedisTemplate.opsForZSet()
                .incrementScore(getRankKey(activityId), String.valueOf(targetId), 1);
    }

    /** 获取 TopN 排行榜（按票数降序） */
    public List<Map<String, Object>> getTopN(Long activityId, int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(getRankKey(activityId), 0, n - 1);
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

    /** 获取指定范围的排名列表 */
    public List<Map<String, Object>> getActivityRanking(Long activityId, long start, long end) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(getRankKey(activityId), start, end);
        return toRankList(tuples, start + 1);
    }

    /** 活动总票数（读取活动 Hash 中 Lua 实时累加的 total_votes） */
    public Long getActivityTotal(Long activityId) {
        Object v = stringRedisTemplate.opsForHash().get(RedisKeys.ACTIVITY_INFO + activityId, "total_votes");
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }

    private List<Map<String, Object>> toRankList(Set<ZSetOperations.TypedTuple<String>> tuples, long startRank) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples != null) {
            long rank = startRank;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", rank++);
                item.put("targetId", Long.valueOf(tuple.getValue()));
                item.put("voteCount", tuple.getScore() != null ? tuple.getScore().longValue() : 0L);
                result.add(item);
            }
        }
        return result;
    }
}
