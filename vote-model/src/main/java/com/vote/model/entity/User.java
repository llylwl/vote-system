package com.vote.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表
 * <p>
 * 同时支持微信小程序登录与账号密码登录，未使用的字段为 null。
 *
 * @author hzp
 * @since 2026-9-16
 */
@Data
@TableName("vote_user")
public class User {

    /** 角色：普通用户 */
    public static final String ROLE_USER = "USER";
    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 状态：封禁 */
    public static final int STATUS_BANNED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名（账号密码登录用） */
    private String username;

    /**
     * BCrypt 密码哈希
     * <p>
     * 注意：该字段不得出现在任何返回给客户端的对象里。
     * 对外一律使用 {@code UserProfile} 等专用 DTO，避免实体直接外泄。
     */
    private String passwordHash;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatarUrl;

    /** 微信小程序 openid */
    private String wechatOpenid;

    /** 微信开放平台 unionid */
    private String wechatUnionid;

    /** 手机号（预留） */
    private String phone;

    /** 角色：USER / ADMIN */
    private String role;

    /** 状态：1-正常，0-封禁 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否为管理员 */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
