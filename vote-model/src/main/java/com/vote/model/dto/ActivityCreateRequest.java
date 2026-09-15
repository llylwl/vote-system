package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建活动请求参数
 * <p>
 * 不直接复用 {@code VoteActivity} 实体作为入参：实体字段与数据库列一一对应，
 * 缺少长度约束（{@code activity_desc} 是 TEXT 类型，可被塞入 MB 级内容撑爆存储与响应）。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Data
public class ActivityCreateRequest {

    @NotBlank(message = "活动名称必填")
    @Size(max = 255, message = "活动名称长度不能超过 255")
    private String activityName;

    @Size(max = 2000, message = "活动描述长度不能超过 2000")
    private String activityDesc;

    @NotNull(message = "开始时间必填")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间必填")
    private LocalDateTime endTime;
}
