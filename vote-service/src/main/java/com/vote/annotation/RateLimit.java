package com.vote.annotation;

import java.lang.annotation.*;

/**
 * 接口限流注解
 * 结合 AOP 切面基于滑动窗口算法实现声明式限流
 * @author hzp
 * @since 2026-9-13
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 窗口内允许的最大请求数 */
    int limit() default 10;

    /** 时间窗口大小（毫秒） */
    long timeWindow() default 1000L;

    /** 指定限流 Key（为空时按 X-User-Id 头，降级按客户端 IP） */
    String key() default "";

    /** 被限流时的提示信息 */
    String message() default "请求过于频繁，请稍后再试";
}
