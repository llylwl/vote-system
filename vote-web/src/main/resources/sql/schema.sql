-- 实时投票排行榜与防刷系统 建表脚本（幂等，可重复执行）

CREATE TABLE IF NOT EXISTS vote_activity (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    activity_name VARCHAR(255) NOT NULL COMMENT '活动名称',
    activity_desc TEXT COMMENT '活动描述',
    start_time    DATETIME     NOT NULL COMMENT '开始时间',
    end_time      DATETIME     NOT NULL COMMENT '结束时间',
    total_votes   BIGINT       DEFAULT 0 COMMENT '总投票数',
    remain_votes  BIGINT       DEFAULT 0 COMMENT '剩余可投票数（可选，用于限购）',
    status        TINYINT      DEFAULT 0 COMMENT '状态：0-未开始，1-进行中，2-已结束',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time, end_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投票活动表';

CREATE TABLE IF NOT EXISTS vote_target (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    activity_id BIGINT       NOT NULL COMMENT '活动ID',
    target_name VARCHAR(255) NOT NULL COMMENT '目标名称',
    target_desc VARCHAR(500) DEFAULT NULL COMMENT '目标描述',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_activity (activity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投票目标表';

CREATE TABLE IF NOT EXISTS vote_record (
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    activity_id        BIGINT      NOT NULL COMMENT '活动ID',
    target_id          BIGINT      NOT NULL COMMENT '投票目标ID',
    user_id            BIGINT      NOT NULL COMMENT '用户ID',
    user_ip            VARCHAR(45) NOT NULL COMMENT '用户IP',
    device_fingerprint VARCHAR(64) DEFAULT NULL COMMENT '设备指纹',
    vote_time          DATETIME    NOT NULL COMMENT '投票时间',
    status             TINYINT     DEFAULT 1 COMMENT '状态：1-有效，0-无效/被拦截',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_user_date (activity_id, user_id, (DATE(vote_time))) COMMENT '联合唯一索引，防止同一天重复投票',
    KEY idx_activity_target (activity_id, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投票记录表';

CREATE TABLE IF NOT EXISTS vote_blacklist (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    target_type  VARCHAR(20)  NOT NULL COMMENT '目标类型：USER/IP/DEVICE',
    target_value VARCHAR(64)  NOT NULL COMMENT '目标值',
    reason       VARCHAR(255) DEFAULT NULL COMMENT '封禁原因',
    expire_time  DATETIME     DEFAULT NULL COMMENT '过期时间，NULL表示永久',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_target (target_type, target_value)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '黑名单表';
