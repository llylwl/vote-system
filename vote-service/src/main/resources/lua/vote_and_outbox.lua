-- 实时投票排行榜与防刷系统：原子投票脚本
-- 将「校验 + 计数 + 排行榜 + 写Outbox」合并为一个 Redis 原子操作
--
-- KEYS[1]  活动信息 Hash  (vote:activity:info:{activityId})
-- KEYS[2]  用户今日投票标记 (vote:user:today:{activityId}:{userId})
-- KEYS[3]  用户黑名单      (vote:blacklist:USER:{userId})
-- KEYS[4]  IP 黑名单       (vote:blacklist:IP:{ip})
-- KEYS[5]  设备黑名单      (vote:blacklist:DEVICE:{fingerprint})，空串表示不校验
-- KEYS[6]  排行榜 ZSet     (vote:rank:{activityId})
-- KEYS[7]  Outbox List    (vote:outbox:queue)
--
-- ARGV[1]  当前时间戳（毫秒）
-- ARGV[2]  userId
-- ARGV[3]  targetId
-- ARGV[4]  activityId
-- ARGV[5]  messageId
-- ARGV[6]  queueName
-- ARGV[7]  消息体 JSON
-- ARGV[8]  用户投票标记 TTL（秒）
--
-- 返回值：1-成功，-1-活动不可投，-2-黑名单拦截，-3-今日已投

-- 1. 活动状态校验
local status = redis.call('HGET', KEYS[1], 'status')
if not status or tonumber(status) ~= 1 then
    return -1
end
local startTime = tonumber(redis.call('HGET', KEYS[1], 'start_time'))
local endTime = tonumber(redis.call('HGET', KEYS[1], 'end_time'))
local now = tonumber(ARGV[1])
if not endTime or now > endTime then
    return -1
end
if startTime and now < startTime then
    return -1
end

-- 2. 黑名单校验（用户 / IP / 设备 任一命中即拦截）
if redis.call('EXISTS', KEYS[3]) == 1 then
    return -2
end
if redis.call('EXISTS', KEYS[4]) == 1 then
    return -2
end
if KEYS[5] ~= '' and redis.call('EXISTS', KEYS[5]) == 1 then
    return -2
end

-- 3. 今日重复投票校验
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -3
end

-- 4. 原子执行：活动总票数 +1、排行榜目标 +1、记录用户今日投票、写入 Outbox
redis.call('HINCRBY', KEYS[1], 'total_votes', 1)
redis.call('ZINCRBY', KEYS[6], 1, ARGV[3])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[8])
redis.call('LPUSH', KEYS[7], ARGV[7])

return 1
