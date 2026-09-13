package com.vote.aspect;

import com.vote.annotation.RateLimit;
import com.vote.common.constant.RedisKeys;
import com.vote.common.exception.RateLimitException;
import com.vote.limiter.SlidingWindowLimiter;
import com.vote.service.VoteStatsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 限流 AOP 切面
 * 拦截带有 @RateLimit 注解的方法，执行滑动窗口限流；
 * Key 优先使用注解指定，否则从 header 获取 userId，降级使用客户端 IP
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final SlidingWindowLimiter slidingWindowLimiter;
    private final VoteStatsService voteStatsService;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit);
        boolean acquired = slidingWindowLimiter.tryAcquire(key, rateLimit.limit(), rateLimit.timeWindow());
        if (!acquired) {
            log.warn("接口触发限流, key={}, limit={}, timeWindow={}ms",
                    key, rateLimit.limit(), rateLimit.timeWindow());
            voteStatsService.incr(VoteStatsService.TYPE_RATELIMIT);
            throw new RateLimitException(rateLimit.message());
        }
        return joinPoint.proceed();
    }

    private String buildKey(RateLimit rateLimit) {
        if (rateLimit.key() != null && !rateLimit.key().isEmpty()) {
            return RedisKeys.RATE_LIMIT + rateLimit.key();
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isEmpty()) {
                return RedisKeys.RATE_LIMIT + "user:" + userId;
            }
            // 降级使用客户端 IP
            return RedisKeys.RATE_LIMIT + "ip:" + getClientIp(request);
        }
        return RedisKeys.RATE_LIMIT + "default";
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
