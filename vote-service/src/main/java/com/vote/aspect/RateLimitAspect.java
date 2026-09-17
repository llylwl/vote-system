package com.vote.aspect;

import com.vote.annotation.RateLimit;
import com.vote.common.config.SecurityProperties;
import com.vote.common.constant.RedisKeys;
import com.vote.common.exception.RateLimitException;
import com.vote.limiter.SlidingWindowLimiter;
import com.vote.service.VoteStatsService;
import com.vote.support.ClientIpResolver;
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
 * <p>
 * 拦截带有 {@link RateLimit} 注解的方法，执行滑动窗口限流。
 * <p>
 * <b>相比早期实现修正的两处关键问题：</b>
 * <ol>
 *   <li><b>限流 Key 可被伪造</b>：原实现优先使用请求头 {@code X-User-Id}。
 *       该头由客户端完全控制，攻击者每次请求换一个随机值就是一个全新的限流桶，
 *       {@code limit=20} 的约束形同虚设。现在只在显式开启（仅限本地调试）时才采信，
 *       否则一律回退到经 {@link ClientIpResolver} 解析的可信 IP；</li>
 *   <li><b>所有接口共用一个桶</b>：原实现的 Key 只包含身份维度，同一用户的
 *       「查排行榜」与「投票」会共享同一个配额，一个高频读接口即可把投票额度耗尽。
 *       现在 Key 中带上方法名，每个接口独立计数。</li>
 * </ol>
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final SlidingWindowLimiter slidingWindowLimiter;
    private final VoteStatsService voteStatsService;
    private final ClientIpResolver clientIpResolver;
    private final SecurityProperties securityProperties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);
        boolean acquired = slidingWindowLimiter.tryAcquire(key, rateLimit.limit(), rateLimit.timeWindow());
        if (!acquired) {
            log.warn("接口触发限流, key={}, limit={}, timeWindow={}ms",
                    key, rateLimit.limit(), rateLimit.timeWindow());
            voteStatsService.incr(VoteStatsService.TYPE_RATELIMIT);
            throw new RateLimitException(rateLimit.message());
        }
        return joinPoint.proceed();
    }

    /**
     * 构造限流 Key，格式：{@code rate_limit:{身份维度}:{类名.方法名}}
     */
    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String dimension;
        if (rateLimit.key() != null && !rateLimit.key().isEmpty()) {
            // 显式指定的静态 Key。注意其语义是「全站共用一个桶」，
            // 写上固定值意味着一个用户可以消耗掉所有人的配额，请谨慎使用。
            dimension = rateLimit.key();
        } else {
            dimension = resolveIdentityDimension();
        }
        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        return RedisKeys.RATE_LIMIT + dimension + ":" + method;
    }

    /**
     * 解析身份维度
     * <p>
     * 优先级：认证后的用户 ID（后续接入）→ 显式开启时的 {@code X-User-Id} → 可信客户端 IP。
     */
    private String resolveIdentityDimension() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "default";
        }
        HttpServletRequest request = attributes.getRequest();

        // 生产环境 trustClientUserId 为 false，此处必然走 IP 维度。
        // 该开关仅用于本地调试，避免开发时为了测限流还要先走一遍登录。
        if (securityProperties.isTrustClientUserId()) {
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isEmpty()) {
                return "user:" + userId;
            }
        }

        return "ip:" + clientIpResolver.resolve(request);
    }
}
