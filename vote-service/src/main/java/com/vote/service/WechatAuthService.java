package com.vote.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vote.common.config.WechatProperties;
import com.vote.common.exception.BusinessException;
import com.vote.common.result.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 微信小程序登录服务
 * <p>
 * 流程：小程序端调用 {@code wx.login()} 拿到临时 code → 传给服务端 →
 * 服务端用 AppID/AppSecret 调 {@code jscode2session} 换取 openid。
 * <p>
 * <b>为什么 code 必须由服务端换取 openid：</b>AppSecret 绝不能下发到客户端，
 * 且 code 只能使用一次、5 分钟过期，由服务端换取才能保证可信。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Slf4j
@Service
public class WechatAuthService {

    private final WechatProperties wechatProperties;
    private final Environment environment;
    private final RestClient restClient;

    public WechatAuthService(WechatProperties wechatProperties, Environment environment) {
        this.wechatProperties = wechatProperties;
        this.environment = environment;

        // 微信接口偶发慢响应，必须设超时，否则会占着 Tomcat 线程不放
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(wechatProperties.getTimeoutMs());
        factory.setReadTimeout(wechatProperties.getTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 启动校验：mock 模式绝不允许出现在生产环境
     * <p>
     * mock 模式下 openid 由 code 直接推导，不经过微信校验 ——
     * 一旦在生产开启，任何人都能凭空构造 code 登录任意账号。
     */
    @PostConstruct
    void validateConfig() {
        if (wechatProperties.isMockEnabled() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "生产环境禁止开启 app.wechat.mock-enabled："
                            + "该模式不校验 code，任何人都能伪造登录。请配置真实的 AppID/AppSecret。");
        }
        if (wechatProperties.isMockEnabled()) {
            log.warn("微信登录处于 mock 模式：openid 由 code 直接推导，不请求微信接口。"
                    + "仅限本地开发使用。");
        } else if (!wechatProperties.isConfigured()) {
            log.warn("未配置微信 AppID/AppSecret，微信登录接口将不可用（账号密码登录不受影响）。"
                    + "本地开发可开启 app.wechat.mock-enabled 跳过微信校验。");
        }
    }

    /**
     * 用 code 换取微信会话
     *
     * @param code wx.login() 返回的临时凭证
     * @return 会话信息（至少包含 openid）
     * @throws BusinessException 微信返回错误或调用失败时抛出
     */
    public WechatSession code2Session(String code) {
        if (wechatProperties.isMockEnabled()) {
            return mockSession(code);
        }
        if (!wechatProperties.isConfigured()) {
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED,
                    "微信登录未配置，请联系管理员");
        }

        WechatSessionResponse response;
        try {
            response = restClient.get()
                    .uri(wechatProperties.getCodeToSessionUrl()
                                    + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                            wechatProperties.getAppId(), wechatProperties.getAppSecret(), code)
                    .retrieve()
                    .body(WechatSessionResponse.class);
        } catch (Exception e) {
            // 网络异常不回显内部细节，避免把接口地址、超时信息暴露给客户端
            log.error("调用微信 jscode2session 失败", e);
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED, "微信服务暂时不可用，请稍后重试");
        }

        if (response == null) {
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED);
        }
        if (response.errcode() != null && response.errcode() != 0) {
            // 常见错误码：40029-code 无效，45011-频率限制，40226-风险用户
            log.warn("微信返回错误: errcode={}, errmsg={}", response.errcode(), response.errmsg());
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED, "微信登录失败，请重试");
        }
        if (response.openid() == null || response.openid().isBlank()) {
            log.warn("微信未返回 openid: {}", response);
            throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED);
        }
        return new WechatSession(response.openid(), response.unionid());
    }

    /**
     * mock 会话：由 code 推导出一个稳定的假 openid
     * <p>
     * 同一个 code 永远得到同一个 openid，便于本地反复测试同一个"微信用户"。
     */
    private WechatSession mockSession(String code) {
        String openid = "mock_" + md5Hex(code).substring(0, 24);
        log.debug("微信登录 mock 模式: code={} → openid={}", code, openid);
        return new WechatSession(openid, null);
    }

    private String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必备算法，不可能走到这里
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /** 微信会话 */
    public record WechatSession(String openid, String unionid) {
    }

    /** jscode2session 响应；微信成功与失败返回的是同一层级的不同字段 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WechatSessionResponse(
            String openid,
            @JsonProperty("session_key") String sessionKey,
            String unionid,
            Integer errcode,
            String errmsg) {
    }
}
