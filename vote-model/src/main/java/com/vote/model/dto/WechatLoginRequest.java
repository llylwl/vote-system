package com.vote.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 微信小程序登录请求参数
 *
 * @author hzp
 * @since 2026-9-16
 */
@Data
public class WechatLoginRequest {

    /**
     * wx.login() 返回的临时登录凭证 code。
     * <p>
     * 该 code 只能用一次、5 分钟过期，服务端拿它换 openid。
     */
    @NotBlank(message = "code 必填")
    @Size(max = 128, message = "code 长度不合法")
    private String code;

    /** 昵称（可选）。小程序新规下用户信息需通过 getUserProfile 单独获取后回传 */
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String avatarUrl;
}
