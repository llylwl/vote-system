package com.vote.security;

import com.vote.common.constant.RedisKeys;
import com.vote.model.entity.User;
import com.vote.model.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 用户最新状态查询（角色 + 是否可用）
 * <p>
 * <b>为什么需要它：</b>JWT 中的 {@code role} 是签发那一刻的快照。
 * 如果管理员被降权、或账号被封禁，只要旧令牌还没过期，它依然会带着旧权限通过校验。
 * 把管理员降级成普通用户后他还能操作后台七天 —— 这是不可接受的。
 * <p>
 * 这里在每次鉴权时读一份「当前状态」，并用短 TTL 缓存避免每个请求都查数据库。
 * 代价是最长 {@value #CACHE_TTL_SECONDS} 秒的生效延迟，相比 7 天的窗口已经是巨大改善；
 * 需要立即生效时调用 {@link #invalidate} 主动失效缓存。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStateService {

    /** 状态缓存时长（秒）。越短越安全，越长越省数据库压力 */
    private static final long CACHE_TTL_SECONDS = 60L;

    /** 用户不存在时的占位值，避免缓存穿透把不存在的 ID 反复打到数据库 */
    private static final String NOT_FOUND = "NONE";

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 查询用户当前状态
     *
     * @return 用户状态；用户不存在时返回 null
     */
    public UserState get(Long userId) {
        if (userId == null) {
            return null;
        }
        String key = RedisKeys.AUTH_USER_STATE + userId;

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return NOT_FOUND.equals(cached) ? null : UserState.parse(cached);
            }
        } catch (Exception e) {
            // 缓存不可用时直接查库，不影响鉴权正确性
            log.warn("读取用户状态缓存失败，回退查询数据库: userId={}", userId, e);
        }

        User user = userMapper.selectById(userId);
        UserState state = null;
        if (user != null) {
            boolean active = user.getStatus() != null && user.getStatus() == User.STATUS_NORMAL;
            state = new UserState(user.getRole(), active);
        }

        try {
            redisTemplate.opsForValue().set(key,
                    state == null ? NOT_FOUND : state.serialize(),
                    CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入用户状态缓存失败: userId={}", userId, e);
        }
        return state;
    }

    /**
     * 主动失效缓存
     * <p>
     * 封禁用户、变更角色、删除账号后应当调用，让变更立即生效而不必等缓存自然过期。
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(RedisKeys.AUTH_USER_STATE + userId);
        } catch (Exception e) {
            log.warn("失效用户状态缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 用户当前状态
     *
     * @param role   当前角色
     * @param active 账号是否可用（未被封禁）
     */
    public record UserState(String role, boolean active) {

        public boolean isAdmin() {
            return User.ROLE_ADMIN.equals(role);
        }

        String serialize() {
            return role + "|" + active;
        }

        static UserState parse(String value) {
            int separator = value.lastIndexOf('|');
            if (separator <= 0) {
                return null;
            }
            return new UserState(
                    value.substring(0, separator),
                    Boolean.parseBoolean(value.substring(separator + 1)));
        }
    }
}
