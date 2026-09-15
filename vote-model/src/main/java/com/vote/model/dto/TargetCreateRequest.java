package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 添加投票目标请求参数
 *
 * @author hzp
 * @since 2026-9-15
 */
@Data
public class TargetCreateRequest {

    @NotBlank(message = "目标名称必填")
    @Size(max = 255, message = "目标名称长度不能超过 255")
    private String targetName;

    @Size(max = 500, message = "目标描述长度不能超过 500")
    private String targetDesc;
}
