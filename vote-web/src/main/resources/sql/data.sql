-- 种子数据（INSERT IGNORE，可重复启动）
-- Demo 活动：昨天开始，7 天后结束，状态为进行中

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
