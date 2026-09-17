package com.vote.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vote.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author hzp
 * @since 2026-9-17
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
