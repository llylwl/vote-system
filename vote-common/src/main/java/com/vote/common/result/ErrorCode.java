package com.vote.common.result;

import lombok.Getter;

/**
 * 统一错误码
 * <p>
 * 原先错误码是散落在各处的魔法数字（Controller 里的 4001/4002/4003/5000、
 * 异常类里的 429、全局处理器里的 500），没有唯一来源，前后端约定必然漂移。
 * 这里收敛为唯一出处。
 * <p>
 * 同时带上 {@code httpStatus}：原实现所有异常都返回 HTTP 200、只在 body 里放业务码，
 * 导致网关、WAF、客户端重试库都无法按状态码做退避与熔断，只能解析业务体。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Getter
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, 200, "success"),

    // ---------- 通用 ----------
    /** 参数校验失败 */
    BAD_REQUEST(400, 400, "参数不完整或格式不正确"),
    /** 资源不存在 */
    NOT_FOUND(404, 404, "资源不存在"),
    /** 请求过于频繁（被限流） */
    RATE_LIMITED(429, 429, "请求过于频繁，请稍后再试"),
    /** 未认证 */
    UNAUTHORIZED(401, 401, "请先登录"),
    /** 无权限 */
    FORBIDDEN(403, 403, "没有操作权限"),
    /** 系统内部错误 */
    INTERNAL_ERROR(500, 500, "系统繁忙，请稍后再试"),

    // ---------- 投票业务 ----------
    /** 活动未开始或已结束 */
    ACTIVITY_NOT_VOTABLE(4001, 400, "活动未开始或已结束"),
    /** 命中黑名单 */
    BLACKLISTED(4002, 403, "您已被限制投票"),
    /** 今日已投过票 */
    DUPLICATE_VOTE(4003, 409, "您今日已投过票"),
    /** 投票系统繁忙（Lua 执行异常等） */
    VOTE_SYSTEM_BUSY(5000, 500, "投票系统繁忙，请稍后再试"),

    // ---------- 用户与认证 ----------
    /** 用户名已被占用 */
    USERNAME_TAKEN(4004, 409, "用户名已被占用"),
    /** 用户名或密码错误 */
    BAD_CREDENTIALS(4005, 401, "用户名或密码错误"),
    /** 账号被封禁 */
    USER_BANNED(4006, 403, "账号已被封禁，请联系管理员"),
    /** 微信登录失败 */
    WECHAT_AUTH_FAILED(4007, 401, "微信登录失败，请重试"),
    /** 令牌无效或已失效 */
    TOKEN_INVALID(4008, 401, "登录已失效，请重新登录"),
    /** 连续登录失败，账号被临时锁定 */
    LOGIN_LOCKED(4009, 429, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    ;

    /** 业务码，返回体中的 {@code code} 字段 */
    private final int code;

    /** 对应的 HTTP 状态码 */
    private final int httpStatus;

    /** 默认提示文案 */
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /**
     * 由业务码反查枚举，用于把 Lua 脚本的返回值映射为统一错误码
     *
     * @param code 业务码
     * @return 匹配的枚举；找不到返回 null
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
