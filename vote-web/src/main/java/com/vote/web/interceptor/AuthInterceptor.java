package com.vote.web.interceptor;

import com.vote.annotation.RequireAdmin;
import com.vote.annotation.RequireLogin;
import com.vote.common.exception.BusinessException;
import com.vote.common.result.ErrorCode;
import com.vote.security.JwtService;
import com.vote.security.TokenStore;
import com.vote.security.UserContext;
import com.vote.security.UserStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.annotation.Annotation;

/**
 * 认证拦截器
 * <p>
 * 在请求进入 Controller 之前完成三件事：
 * <ol>
 *   <li><b>解析令牌</b>：从 {@code Authorization: Bearer <token>} 取出并验签；</li>
 *   <li><b>校验白名单</b>：JWT 验签通过<b>不等于</b>令牌仍有效 —— 用户可能已经登出。
 *       必须再查一次 Redis 白名单，否则"退出登录"就只是客户端删了个字符串；</li>
 *   <li><b>权限判定</b>：按 {@link RequireLogin} / {@link RequireAdmin} 决定是否放行。</li>
 * </ol>
 * <p>
 * <b>采用「可选认证」设计</b>：即使接口不要求登录，只要请求带了合法令牌也会解析并注入
 * {@link UserContext}。这样投票接口可以做到「登录用户用账号身份、未登录用户用设备指纹」。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 认证请求头 */
    public static final String AUTH_HEADER = "Authorization";
    /** Bearer 前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final UserStateService userStateService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 静态资源、错误页等非 Controller 请求直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        UserContext.CurrentUser currentUser = resolveCurrentUser(request);
        UserContext.set(currentUser);

        boolean requireAdmin = hasAnnotation(handlerMethod, RequireAdmin.class);
        boolean requireLogin = requireAdmin || hasAnnotation(handlerMethod, RequireLogin.class);

        if (!requireLogin) {
            // 接口不要求登录：已认证就注入身份供业务使用，未认证也照常放行
            return true;
        }

        if (currentUser == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        // 关键：角色与封禁状态以【数据库当前值】为准，不能只信令牌里的快照。
        // 令牌中的 role 是签发那一刻的，若管理员被降权或账号被封禁，
        // 旧令牌在有效期内（默认 7 天）仍会带着旧权限通过校验。
        // 这里读一份带短 TTL 缓存的当前状态，把窗口从 7 天压缩到 60 秒。
        UserStateService.UserState state = userStateService.get(currentUser.userId());
        if (state == null) {
            // 用户已被删除
            log.warn("令牌对应的用户不存在: userId={}", currentUser.userId());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (!state.active()) {
            log.warn("已封禁账号的请求被拒绝: userId={}, uri={}",
                    currentUser.userId(), request.getRequestURI());
            throw new BusinessException(ErrorCode.USER_BANNED);
        }
        if (requireAdmin && !state.isAdmin()) {
            log.warn("非管理员尝试访问管理接口: userId={}, role={}, uri={}",
                    currentUser.userId(), state.role(), request.getRequestURI());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 角色已变更时用最新值刷新上下文，保证 UserContext.isAdmin() 判断准确
        if (!state.role().equals(currentUser.role())) {
            log.debug("用户角色已变更，按最新角色处理: userId={}, tokenRole={}, currentRole={}",
                    currentUser.userId(), currentUser.role(), state.role());
            UserContext.set(new UserContext.CurrentUser(
                    currentUser.userId(), state.role(), currentUser.jti()));
        }
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal
     * <p>
     * <b>这一步不能省。</b>Tomcat 的工作线程是复用的，若不清理，
     * 下一个请求（可能是另一个完全不同的用户，甚至是未登录用户）会读到上一个用户的身份。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /** 解析并验证令牌；未携带、验签失败或已被登出时返回 null */
    private UserContext.CurrentUser resolveCurrentUser(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return null;
        }

        JwtService.ParsedToken parsed = jwtService.parse(token);
        if (parsed == null) {
            return null;
        }

        // 验签通过只说明这枚令牌是我们签发的、且没过期。
        // 它可能已经被登出或被改密撤销，必须查一次白名单。
        if (!tokenStore.isActive(parsed.jti())) {
            log.debug("令牌已失效（不在白名单中，可能已登出）: userId={}, jti={}",
                    parsed.userId(), parsed.jti());
            return null;
        }

        return new UserContext.CurrentUser(parsed.userId(), parsed.role(), parsed.jti());
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        // 兼容不带 Bearer 前缀的写法，方便本地用 curl 调试
        if (header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return header.trim();
    }

    private boolean hasAnnotation(HandlerMethod handlerMethod, Class<? extends Annotation> type) {
        // 方法上的注解优先，其次看类（Controller）上的注解
        return handlerMethod.getMethodAnnotation(type) != null
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), type);
    }
}
