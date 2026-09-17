package com.vote.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要管理员权限
 * <p>
 * 隐含要求登录。由 {@code AuthInterceptor} 统一校验，
 * 未登录返回 401，已登录但非管理员返回 403。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
