package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投票活动表
 * @author hzp
 * @since 2026-9-13
 */
@Data
@TableName("vote_activity")
public class VoteActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动名称 */
    private String activityName;

    /** 活动描述 */
    private String activityDesc;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 总投票数 */
    private Long totalVotes;

    /** 剩余可投票数（可选，用于限购） */
    private Long remainVotes;

    /** 状态：0-未开始，1-进行中，2-已结束 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
