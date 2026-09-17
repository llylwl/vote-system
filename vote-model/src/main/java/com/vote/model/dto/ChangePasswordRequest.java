package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求参数
 *
 * @author hzp
 * @since 2026-9-16
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码必填")
    @Size(max = 64, message = "原密码长度不能超过 64")
    private String oldPassword;

    @NotBlank(message = "新密码必填")
    @Size(min = 8, max = 64, message = "新密码长度需为 8~64 位")
    private String newPassword;
}
