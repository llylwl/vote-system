-- ============================================================
-- V4 用户表
--
-- 支持两种登录通道：
--   1. 微信小程序：wx.login → code2session → openid
--   2. 账号密码：username + BCrypt 密码哈希
--
-- 两种通道共用一张表，未使用的字段留 NULL。
-- MySQL 的 UNIQUE 索引允许多个 NULL，因此两个唯一键可以共存：
--   纯微信用户 username 为 NULL，纯密码用户 wechat_openid 为 NULL。
-- ============================================================

CREATE TABLE IF NOT EXISTS vote_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    username        VARCHAR(64)  DEFAULT NULL COMMENT '用户名（账号密码登录）',
    password_hash   VARCHAR(100) DEFAULT NULL COMMENT 'BCrypt 密码哈希（60 字符，预留余量）',
    nickname        VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar_url      VARCHAR(512) DEFAULT NULL COMMENT '头像地址',
    wechat_openid   VARCHAR(64)  DEFAULT NULL COMMENT '微信小程序 openid（同一小程序内唯一）',
    wechat_unionid  VARCHAR(64)  DEFAULT NULL COMMENT '微信开放平台 unionid（多应用互通时使用）',
    phone           VARCHAR(20)  DEFAULT NULL COMMENT '手机号（预留）',
    role            VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER-普通用户，ADMIN-管理员',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-正常，0-封禁',
    last_login_time DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_wechat_openid (wechat_openid),
    KEY idx_wechat_unionid (wechat_unionid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ------------------------------------------------------------
-- 说明：vote_record.user_id 的历史遗留问题
--
-- 引入用户体系之前，vote_record.user_id 存的是【客户端提交的任意数字】；
-- 引入之后，登录用户投票时它存的是 vote_user.id。两者语义不同但混在同一列，
-- 因为历史数据（压测产生的 888001、10001 等）无法追溯映射到真实用户。
--
-- 这里刻意【不】加外键约束：一是有历史数据无对应用户，二是加了之后
-- 后续要拆表/归档会非常麻烦。若将来需要严格关联，建议新增
-- user_id_source 字段（0-匿名，1-注册用户）并回填历史数据。
-- ------------------------------------------------------------
