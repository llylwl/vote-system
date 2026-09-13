package com.vote.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vote.model.entity.VoteRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author hzp
 * @since 2026-9-15
 */
@Mapper
public interface VoteRecordMapper extends BaseMapper<VoteRecord> {
}
