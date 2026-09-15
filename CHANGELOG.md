# 更新日志

本文件记录项目的所有重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [2.0.0] - 2026-09-16

**主题：可靠性修复。** 本次不新增业务功能，专注于让已有的可靠性机制**真正生效**。
详细的问题分析、根因与验证方式见 [docs/V2-RELIABILITY-NOTES.md](docs/V2-RELIABILITY-NOTES.md)。

### 修复（严重）

- **「每天一票」跨天误拦**：Redis 每日标记的 TTL 由固定 `86400` 秒改为
  "到本地次日 00:00"，Key 增加自然日（`vote:user:today:{activityId}:{yyyyMMdd}:{userId}`），
  与数据库唯一索引 `DATE(vote_time)` 口径对齐。
  修复前，23:59 投过票的用户次日一整天都会被判为"今日已投"。
- **落库失败导致静默丢票**：幂等标记改为落库成功后才生效；只吞 `DuplicateKeyException`；
  瞬时故障撤销标记并抛出以触发重试。修复前，数据库抖动一次即永久丢票。
- **消费端重试死循环、死信队列永不触发**：弃用 `basicNack(requeue=true)`
  （重试计数写在本地对象上，Broker 重投原始消息时不会带回），
  改为重发一条带自增计数的新消息并 ACK 原消息，配合 TTL 重试队列做退避。
- **Outbox 先删后发导致丢消息**：`RPOP` 改为 `RPOPLPUSH` 原子搬到处理中队列，
  等待 Broker 确认后才移除；启动时恢复崩溃残留；批量处理将吞吐上限从 50 msg/s 提升约 3 个数量级。
- **全新环境活动完全无法投票**：新增启动预热（`CacheWarmUpRunner`），
  定时任务增加 Hash/排行榜缺失时的自愈，不再依赖活动状态"跳变"。
- **调用预热接口会把排行榜清零**：改用 `VoteReconcileService.warmUp`，
  只补齐缺失部分、绝不覆盖已有数据。
- **防刷体系可被请求头绕过**：新增可信代理白名单（`ClientIpResolver`），
  只采信来自可信代理的 `X-Forwarded-For` 且从最右侧解析；
  `X-User-Id` 仅在显式开启时采信（生产默认关闭）。
  修复前，伪造请求头即可同时绕过 IP 限流与 IP 黑名单。
- **缓存穿透 + 无限递归耗尽线程池**：空结果写短 TTL 占位；
  抢锁失败改为有界等待并降级回源，不再 `sleep` 后无限递归。

### 修复（一般）

- 活动详情缓存不再缓存实时票数（此前会被冻结 1 小时且全项目无缓存失效调用）
- 分页与 TopN 参数夹紧到上限，防止 `?end=99999999` 阻塞 Redis
- 黑名单过期时间已过去时直接拒绝（此前会入库但不生效，静默失效）
- 添加投票目标时校验活动存在（防止产生污染排行榜的孤儿目标）
- 修复 `NoResourceFoundException` 被兜底处理器转成 500 的问题，现正确返回 404
- 缓存重建锁改用 Redisson（看门狗续期 + `isHeldByCurrentThread`），消除锁误删

### 新增

- 票数对账能力：`GET /admin/activity/{id}/reconcile`（只读）
- 强制重建缓存：`POST /admin/activity/{id}/rebuild-cache`（故障恢复用）
- 死信落库表 `vote_dead_letter`，重试耗尽的消息不再丢弃，支持人工重放
- 统一错误码枚举 `ErrorCode`，业务码不再散落为魔法数字
- 请求参数校验（`@Valid`）与对应 DTO
- 健康检查与运行指标（Spring Boot Actuator）
- 优雅停机（`server.shutdown: graceful`）
- 定时任务分布式锁，支持多实例部署
- 单元测试 17 个（`DayUtilsTest` 7 个 + `ClientIpResolverTest` 10 个）

### 变更（不兼容）

- **异常现在返回真实的 HTTP 状态码**。此前所有异常一律返回 HTTP 200、
  仅在响应体放业务码；现在 400 / 403 / 404 / 409 / 429 / 500 如实反映在状态行上。
  客户端若依赖"HTTP 恒为 200"，需要相应调整。
- **配置拆分为多环境**：`application.yml`（公共）+ `application-dev.yml` + `application-prod.yml`。
  生产环境的所有凭据改为环境变量注入且**不提供默认值**，缺失时启动即失败。
- **表结构改由 Flyway 管理**：`vote-web/src/main/resources/sql/` 下的
  `schema.sql` / `data.sql` 已移除，迁移脚本位于 `db/migration/`。
  已有数据库会通过 `baseline-on-migrate` 自动基线化，无需手工介入。
- **演示数据仅在 dev profile 下灌入**，不再污染生产库。

### 移除

- `CacheWarmUpService`（其 `warmUpRanking` 会清零排行榜，能力已由 `VoteReconcileService` 取代）
- `DelayDoubleDeleteService`（全项目零调用，且"从调用时刻起算延迟"无法真正解决主从延迟）

### 已知问题

- Lua 脚本使用 7 个 key 且未使用 hash tag，**无法直接运行在 Redis Cluster 上**
  （单机部署不受影响）
- 排行榜与 Outbox 队列是全局单一 key，Redis 单线程下其写入速率即系统吞吐上限
- 管理端接口尚未鉴权（阶段二处理）

---

## [1.0.0] - 2026-09-15

### 新增

- 基于 Redis Lua 的原子投票链路（活动状态校验 → 黑名单校验 → 每日重复校验 →
  票数累加 → 排行榜更新 → 写 Outbox）
- Transactional Outbox + RabbitMQ 削峰，手动 ACK、死信队列
- 基于 ZSet 的实时排行榜（TopN / 指定排名 / 分页）
- 多维度防刷：每日一票、用户/IP/设备黑名单、滑动窗口限流、落库幂等
- 两种缓存防击穿策略（互斥锁 / 逻辑过期）与活动预热
- 单文件可视化控制台 `panel.html`（KPI、投票模拟器、实时榜单、黑名单管理）
- SpringDoc OpenAPI 接口文档
