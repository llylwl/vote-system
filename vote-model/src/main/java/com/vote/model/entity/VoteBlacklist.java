package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 黑名单表
 * 记录被封禁的用户 / IP / 设备
 * @author hzp
 * @since 2026-9-13
 */
@Data
@TableName("vote_blacklist")
public class VoteBlacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标类型：USER / IP / DEVICE */
    private String targetType;

    /** 目标值 */
    private String targetValue;

    /** 封禁原因 */
    private String reason;

    /** 过期时间，NULL 表示永久 */
    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
