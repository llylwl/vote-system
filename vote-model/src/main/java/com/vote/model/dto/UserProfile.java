package com.vote.model.dto;

import com.vote.model.entity.User;
import lombok.Data;

/**
 * 用户对外信息
 * <p>
 * <b>刻意不直接返回 {@code User} 实体</b>：实体上带有 {@code passwordHash} 字段，
 * 一旦被序列化出去就是密码哈希泄露。所有对外接口一律走本类。
 *
 * @author hzp
 * @since 2026-9-17
 */
@Data
public class UserProfile {

    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String role;

    public static UserProfile from(User user) {
        if (user == null) {
            return null;
        }
        UserProfile profile = new UserProfile();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setNickname(user.getNickname());
        profile.setAvatarUrl(user.getAvatarUrl());
        profile.setRole(user.getRole());
        return profile;
    }
}
