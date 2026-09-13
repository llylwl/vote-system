package com.vote.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vote.common.constant.RedisKeys;
import com.vote.service.rank.VoteRankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存预热服务
 * 在活动开始前将热点投票数据加载到 Redis ZSet 中，避免冷启动时的缓存击穿
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheWarmUpService {

    private final StringRedisTemplate stringRedisTemplate;
    private final VoteRankService voteRankService;
    private final ObjectMapper objectMapper;

    /** 预热指定活动的排行榜数据（目标ID -> 初始票数） */
    public void warmUpRanking(Long activityId, Map<Long, Long> targetVotes) {
        if (targetVotes == null || targetVotes.isEmpty()) {
            log.warn("预热活动[{}]排行榜失败: 无投票数据", activityId);
            return;
        }
        String rankKey = RedisKeys.RANK + activityId;
        int count = 0;
        for (Map.Entry<Long, Long> entry : targetVotes.entrySet()) {
            stringRedisTemplate.opsForZSet().add(rankKey, String.valueOf(entry.getKey()), entry.getValue());
            count++;
        }
        log.info("预热活动[{}]排行榜完成，共加载 {} 个目标", activityId, count);
    }

    /** 预热 TopN 缓存 */
    public void warmUpTopNCache(Long activityId, int n) {
        try {
            List<Map<String, Object>> topN = voteRankService.getTopN(activityId, n);
            String cacheKey = RedisKeys.TOPN_CACHE + activityId + ":" + n;
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(topN),
                    30, TimeUnit.SECONDS);
            log.info("预热活动[{}] TopN 缓存完成, n={}", activityId, n);
        } catch (Exception e) {
            log.error("预热 TopN 缓存失败, activityId={}", activityId, e);
        }
    }
}
