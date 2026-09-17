package com.vote.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vote.model.entity.VoteRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * @author hzp
 * @since 2026-9-13
 */
@Mapper
public interface VoteRecordMapper extends BaseMapper<VoteRecord> {

    /**
     * 按投票目标聚合真实票数
     * <p>
     * 这是票数的<b>权威来源</b>：Redis 中的 ZSet 与 total_votes 都是它的缓存，
     * 一旦 Redis 丢数据或计数出错，都应当能由本查询重建出来。
     *
     * @param activityId 活动 ID
     * @return 每行包含 targetId 与 voteCount
     */
    @Select("""
            SELECT target_id AS targetId, COUNT(*) AS voteCount
            FROM vote_record
            WHERE activity_id = #{activityId} AND status = 1
            GROUP BY target_id
            """)
    List<Map<String, Object>> countVotesGroupByTarget(@Param("activityId") Long activityId);

    /**
     * 统计某活动的有效票总数
     *
     * @param activityId 活动 ID
     * @return 总票数
     */
    @Select("SELECT COUNT(*) FROM vote_record WHERE activity_id = #{activityId} AND status = 1")
    long countValidVotes(@Param("activityId") Long activityId);
}
