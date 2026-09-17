package com.vote.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序配置
 * <p>
 * 对应 {@code app.wechat.*}。AppID 与 AppSecret 走环境变量注入，不写死在代码或配置里。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.wechat")
public class WechatProperties {

    /** 小程序 AppID */
    private String appId;

    /** 小程序 AppSecret */
    private String appSecret;

    /**
     * 是否启用 mock 模式
     * <p>
     * 未申请到小程序 AppID 时，打开它可以让本地开发不被阻塞：
     * 服务端不请求微信接口，而是由 code 直接推导出一个稳定的假 openid。
     * <p>
     * <b>生产环境必须为 false</b>，否则任何人都能凭空构造 code 登录任意账号。
     */
    private boolean mockEnabled = false;

    /** code2session 接口地址 */
    private String codeToSessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    /** 调用微信接口的超时时间（毫秒） */
    private int timeoutMs = 5000;

    /** AppID 与 AppSecret 是否都已配置 */
    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
