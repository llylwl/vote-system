-- ============================================================
-- V2 补 vote_record.vote_time 索引
--
-- 背景：控制台「今日入库票数」等统计按 vote_time 做范围查询
--   SELECT COUNT(*) FROM vote_record WHERE vote_time >= 今日0点
-- 而原有索引为：
--   uk_activity_user_date (activity_id, user_id, (DATE(vote_time)))
--   idx_activity_target   (activity_id, target_id)
-- 前者把 DATE(vote_time) 放在第三列，无法服务 vote_time 的范围扫描，
-- 导致上述查询全表扫描。数据量增长后管理面板每次刷新都会拖垮数据库。
--
-- MySQL 不支持 CREATE INDEX IF NOT EXISTS，故用 information_schema 判断后动态执行。
-- ============================================================

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'vote_record'
      AND index_name = 'idx_vote_time'
);

SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_vote_time ON vote_record (vote_time)',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
