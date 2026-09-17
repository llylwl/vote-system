package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投票目标表（选手/候选）
 * @author hzp
 * @since 2026-9-13
 */
@Data
@TableName("vote_target")
public class VoteTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动ID */
    private Long activityId;

    /** 目标名称 */
    private String targetName;

    /** 目标描述 */
    private String targetDesc;

    private LocalDateTime createTime;
}
