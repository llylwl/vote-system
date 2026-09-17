package com.vote.security;

import com.vote.model.entity.User;

/**
 * 当前登录用户上下文
 * <p>
 * 由认证拦截器在请求进入时写入、请求结束时清理。
 * <p>
 * <b>务必成对使用 {@link #set} 与 {@link #clear}。</b>
 * Tomcat 的线程是复用的，若请求结束时不清理，下一个请求（可能是完全不同的用户）
 * 会读到上一个用户的数据 —— 这是 ThreadLocal 最典型的串号事故。
 *
 * @author hzp
 * @since 2026-9-17
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 绑定当前用户；传 null 等同于清理 */
    public static void set(CurrentUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /** 获取当前用户，未登录返回 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 是否已登录 */
    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    /** 当前登录用户 ID，未登录返回 null */
    public static Long currentUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /** 当前用户是否为管理员 */
    public static boolean isAdmin() {
        CurrentUser user = HOLDER.get();
        return user != null && User.ROLE_ADMIN.equals(user.role());
    }

    /** 清理，必须在请求结束时调用 */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前登录用户
     *
     * @param userId 用户 ID
     * @param role   角色
     * @param jti    当前令牌 ID（登出时需要）
     */
    public record CurrentUser(Long userId, String role, String jti) {

        public boolean isAdmin() {
            return User.ROLE_ADMIN.equals(role);
        }
    }
}
