package com.vote.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vote.common.constant.RedisKeys;
import com.vote.common.exception.BusinessException;
import com.vote.common.result.ErrorCode;
import com.vote.model.dto.LoginRequest;
import com.vote.model.dto.LoginResponse;
import com.vote.model.dto.RegisterRequest;
import com.vote.model.dto.UserProfile;
import com.vote.model.dto.WechatLoginRequest;
import com.vote.model.entity.User;
import com.vote.model.mapper.UserMapper;
import com.vote.security.JwtService;
import com.vote.security.TokenStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务：注册、登录、登出与令牌签发
 *
 * @author hzp
 * @since 2026-9-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final WechatAuthService wechatAuthService;
    private final StringRedisTemplate redisTemplate;

    /** 连续登录失败多少次后锁定账号 */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** 失败计数保留与锁定时长（分钟） */
    private static final long LOGIN_FAIL_WINDOW_MINUTES = 15L;

    /**
     * 用于抵消「用户不存在」与「密码错误」的响应时间差
     * <p>
     * 若用户不存在时直接返回，攻击者可以通过响应耗时（是否执行了 BCrypt 校验）
     * 判断某个用户名是否存在，进而枚举出有效账号。这里对不存在的用户也做一次
     * 等价的哈希计算，把两条路径的耗时拉平。
     */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-equalization");
    }

    // ------------------------------------------------------------
    // 注册与登录
    // ------------------------------------------------------------

    /** 账号密码注册 */
    public LoginResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();

        if (findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? username : request.getNickname().trim());
        user.setRole(User.ROLE_USER);
        user.setStatus(User.STATUS_NORMAL);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名时由数据库唯一索引兜底，视为用户名已占用
            log.info("并发注册同名用户被唯一索引拦截: username={}", username);
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }

        log.info("用户注册成功: id={}, username={}", user.getId(), username);
        return issueToken(user);
    }

    /**
     * 账号密码登录
     * <p>
     * 两层防护：接口层按 IP 限流（见 {@code @RateLimit}），这里再做一层<b>账号级</b>计数。
     * 只有 IP 限流是不够的 —— 攻击者用分布式代理池撞库时，每个 IP 都只请求几次，
     * 永远不会触发限流，但同一个账号会被反复尝试。
     * <p>
     * <b>已知取舍：</b>账号级锁定可以被用来恶意锁定他人账号（攻击者故意用错误密码反复尝试）。
     * 这是"防撞库"与"防锁死"之间的固有矛盾。当前选择 15 分钟自动解锁作为折中；
     * 若业务上更怕锁死，可改为「按 账号+IP 组合」计数，或对同一 IP 的失败不做计数。
     */
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();

        if (isLocked(username)) {
            log.warn("账号连续登录失败次数已达上限，拒绝登录: username={}", username);
            throw new BusinessException(ErrorCode.LOGIN_LOCKED);
        }

        User user = findByUsername(username);

        // 统一的错误提示：不区分「用户不存在」与「密码错误」，避免账号枚举
        boolean matched;
        if (user == null || user.getPasswordHash() == null) {
            // 对不存在的用户也执行一次等价开销的哈希校验，拉平两条路径的响应时间
            passwordEncoder.matches(request.getPassword(), dummyHash);
            matched = false;
        } else {
            matched = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        }

        if (!matched) {
            recordLoginFailure(username);
            log.info("登录失败（凭据不匹配）: username={}", username);
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        clearLoginFailures(username);
        ensureActive(user);
        log.info("用户登录成功: id={}, username={}", user.getId(), username);
        return issueToken(user);
    }

    /**
     * 微信小程序登录
     * <p>
     * openid 已存在则直接登录，否则自动注册 —— 小程序场景下没有独立的「注册」步骤。
     */
    public LoginResponse wechatLogin(WechatLoginRequest request) {
        WechatAuthService.WechatSession session = wechatAuthService.code2Session(request.getCode());
        User user = findByOpenid(session.openid());

        if (user == null) {
            user = new User();
            user.setWechatOpenid(session.openid());
            user.setWechatUnionid(session.unionid());
            user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                    ? defaultNickname(session.openid()) : request.getNickname().trim());
            user.setAvatarUrl(request.getAvatarUrl());
            user.setRole(User.ROLE_USER);
            user.setStatus(User.STATUS_NORMAL);
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                // 同一用户并发首登：另一个请求已插入，重新查出来即可
                log.info("并发微信首登被唯一索引拦截，改为读取已有用户: openid={}", session.openid());
                user = findByOpenid(session.openid());
                if (user == null) {
                    throw new BusinessException(ErrorCode.WECHAT_AUTH_FAILED);
                }
            }
            log.info("微信用户自动注册: id={}, openid={}", user.getId(), session.openid());
        } else {
            ensureActive(user);
            // 昵称/头像以客户端最新上报为准（用户可能刚在微信侧改过）
            if (request.getNickname() != null || request.getAvatarUrl() != null) {
                User update = new User();
                update.setId(user.getId());
                if (request.getNickname() != null && !request.getNickname().isBlank()) {
                    update.setNickname(request.getNickname().trim());
                }
                if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
                    update.setAvatarUrl(request.getAvatarUrl());
                }
                userMapper.updateById(update);
            }
        }

        return issueToken(user);
    }

    /** 登出当前设备 */
    public void logout(Long userId, String jti) {
        tokenStore.revoke(jti, userId);
        log.info("用户登出: userId={}", userId);
    }

    /** 登出所有设备 */
    public int logoutAll(Long userId) {
        return tokenStore.revokeAll(userId);
    }

    /**
     * 修改密码
     * <p>
     * 修改成功后会撤销该用户的全部令牌 —— 这样"改密码把其它设备踢下线"才成立，
     * 否则旧令牌在有效期内依然可用。
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "原密码不正确");
        }

        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);

        int revoked = tokenStore.revokeAll(userId);
        log.info("用户修改密码，已撤销其全部令牌: userId={}, revokedTokens={}", userId, revoked);
    }

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    public User findById(Long userId) {
        return userId == null ? null : userMapper.selectById(userId);
    }

    public User findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
    }

    public User findByOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getWechatOpenid, openid));
    }

    public UserProfile getProfile(Long userId) {
        return UserProfile.from(findById(userId));
    }

    // ------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------

    /** 签发令牌并登记白名单 */
    private LoginResponse issueToken(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user.getId(), user.getRole());
        tokenStore.register(issued.jti(), user.getId(), issued.expiresIn());

        User update = new User();
        update.setId(user.getId());
        update.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(update);

        return new LoginResponse(issued.token(), issued.expiresIn(), UserProfile.from(user));
    }

    /** 账号状态校验 */
    private void ensureActive(User user) {
        if (user.getStatus() == null || user.getStatus() != User.STATUS_NORMAL) {
            log.info("已封禁账号尝试登录: userId={}", user.getId());
            throw new BusinessException(ErrorCode.USER_BANNED);
        }
    }

    /** 微信用户默认昵称：不暴露完整 openid */
    private String defaultNickname(String openid) {
        String suffix = openid.length() > 6 ? openid.substring(openid.length() - 6) : openid;
        return "用户" + suffix;
    }

    /** 账号是否已被临时锁定 */
    private boolean isLocked(String username) {
        try {
            String value = redisTemplate.opsForValue().get(RedisKeys.AUTH_LOGIN_FAIL + username);
            return value != null && Long.parseLong(value) >= MAX_LOGIN_FAILURES;
        } catch (NumberFormatException e) {
            return false;
        } catch (Exception e) {
            // Redis 不可用时按未锁定处理：宁可放宽校验，也不能让所有人都登不进来
            log.warn("查询登录失败计数失败，按未锁定处理: username={}", username, e);
            return false;
        }
    }

    /** 记录一次登录失败 */
    private void recordLoginFailure(String username) {
        try {
            String key = RedisKeys.AUTH_LOGIN_FAIL + username;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 只在首次创建时设置 TTL，避免每次失败都重置窗口导致计数永不过期
                redisTemplate.expire(key, LOGIN_FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);
            }
            if (count != null && count >= MAX_LOGIN_FAILURES) {
                log.warn("账号连续登录失败 {} 次，已临时锁定 {} 分钟: username={}",
                        count, LOGIN_FAIL_WINDOW_MINUTES, username);
            }
        } catch (Exception e) {
            log.warn("记录登录失败次数失败: username={}", username, e);
        }
    }

    /** 登录成功后清零失败计数 */
    private void clearLoginFailures(String username) {
        try {
            redisTemplate.delete(RedisKeys.AUTH_LOGIN_FAIL + username);
        } catch (Exception e) {
            log.warn("清除登录失败计数失败: username={}", username, e);
        }
    }
}
