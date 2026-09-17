package com.vote.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口需要登录才能访问
 * <p>
 * 可标注在方法或类上。由 {@code AuthInterceptor} 统一校验，
 * 未登录时返回 HTTP 401。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireLogin {
}
