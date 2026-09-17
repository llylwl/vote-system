package com.vote.web.controller;

import com.vote.annotation.RateLimit;
import com.vote.annotation.RequireLogin;
import com.vote.common.result.Result;
import com.vote.model.dto.ChangePasswordRequest;
import com.vote.model.dto.LoginRequest;
import com.vote.model.dto.LoginResponse;
import com.vote.model.dto.RegisterRequest;
import com.vote.model.dto.UserProfile;
import com.vote.model.dto.WechatLoginRequest;
import com.vote.security.UserContext;
import com.vote.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 * <p>
 * 登录类接口都加了限流：它们是暴力破解的首要目标。
 * 接口层按 IP 限流，账号维度的锁定在 {@link UserService#login} 中实现。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Slf4j
@Tag(name = "认证接口", description = "注册、登录、登出、当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "账号密码注册")
    @PostMapping("/register")
    @RateLimit(limit = 5, timeWindow = 60000, message = "注册过于频繁，请稍后再试")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    @RateLimit(limit = 10, timeWindow = 60000, message = "登录尝试过于频繁，请稍后再试")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    /**
     * 微信小程序登录
     * <p>
     * 小程序端流程：{@code wx.login()} 拿 code → 调用本接口 → 拿到 token 后存入 storage，
     * 后续请求在 {@code Authorization: Bearer <token>} 中携带。
     */
    @Operation(summary = "微信小程序登录（首次自动注册）")
    @PostMapping("/wechat-login")
    @RateLimit(limit = 10, timeWindow = 60000, message = "登录尝试过于频繁，请稍后再试")
    public Result<LoginResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        return Result.success(userService.wechatLogin(request));
    }

    @Operation(summary = "退出登录（仅当前设备）")
    @PostMapping("/logout")
    @RequireLogin
    public Result<Boolean> logout() {
        UserContext.CurrentUser currentUser = UserContext.get();
        userService.logout(currentUser.userId(), currentUser.jti());
        return Result.success(true);
    }

    @Operation(summary = "退出所有设备")
    @PostMapping("/logout-all")
    @RequireLogin
    public Result<Integer> logoutAll() {
        return Result.success(userService.logoutAll(UserContext.currentUserId()));
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    @RequireLogin
    public Result<UserProfile> me() {
        return Result.success(userService.getProfile(UserContext.currentUserId()));
    }

    @Operation(summary = "修改密码（会踢下所有设备）")
    @PostMapping("/change-password")
    @RequireLogin
    public Result<Boolean> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(
                UserContext.currentUserId(), request.getOldPassword(), request.getNewPassword());
        return Result.success(true);
    }
}
