-- ============================================================
-- 开发环境演示数据（仅在 dev profile 下执行）
--
-- 表结构由 Flyway 管理（db/migration），本文件只负责灌入演示数据。
-- 通过 application-dev.yml 中的 spring.sql.init.data-locations 指定，
-- 生产环境的 spring.sql.init.mode 默认为 embedded，不会执行本文件。
--
-- 刻意保留显式 ID：README 与 panel.html 中的示例请求都使用活动 1、目标 1~5。
-- 若改用应用层插入，MyBatis-Plus 的 IdType.AUTO 会忽略显式 ID 改由数据库自增，
-- 示例请求就需要跟着改，不利于上手。
--
-- INSERT IGNORE 保证重复启动幂等。
-- ============================================================

INSERT IGNORE INTO vote_activity (id, activity_name, activity_desc, start_time, end_time, total_votes, remain_votes, status)
VALUES (1, '2026年度最佳开发者评选',
        'Demo活动：验证实时投票排行榜与防刷系统（Redis Lua原子投票 + Outbox + RabbitMQ削峰 + ZSet排行榜）',
        DATE_SUB(NOW(), INTERVAL 1 DAY),
        DATE_ADD(NOW(), INTERVAL 7 DAY),
        0, 0, 1);

INSERT IGNORE INTO vote_target (id, activity_id, target_name, target_desc) VALUES
(1, 1, '选手A', '后端开发'),
(2, 1, '选手B', '前端开发'),
(3, 1, '选手C', '算法工程师'),
(4, 1, '选手D', '测试开发'),
(5, 1, '选手E', '运维开发');
