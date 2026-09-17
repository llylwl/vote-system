# 更新日志

本文件记录项目的所有重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [3.0.0] - 2026-09-17

**主题：用户模块与认证。** 补上此前完全缺失的身份层，使系统具备接入微信小程序的基础。
设计取舍、小程序对接指南与安全加固清单见 [docs/V3-AUTH-NOTES.md](docs/V3-AUTH-NOTES.md)。

### 新增

- **用户模块**：注册、登录、登出、改密，支持 `USER` / `ADMIN` 两级角色与封禁状态
- **三种登录方式**：
  - 微信小程序登录（`wx.login` → `code2session` → openid，首次自动注册）
  - 账号密码登录（BCrypt 密码哈希）
  - 两者共用一张表，可绑定到同一账号
- **认证体系**：JWT + Redis 白名单，支持单设备登出、全设备登出、改密踢人
- **权限控制**：`@RequireLogin` / `@RequireAdmin` 注解 + 认证拦截器，`/admin/**` 全量保护
- **微信 mock 模式**：未申请 AppID 时可用 code 直接推导 openid，本地开发不被阻塞
- **控制台登录界面**：`panel.html` 增加登录/注册界面，请求自动携带令牌，401 自动回到登录页
- 新增接口：`/api/auth/register`、`/login`、`/wechat-login`、`/logout`、`/logout-all`、`/me`、`/change-password`
- Flyway `V4` 用户表迁移

### 安全加固

- **投票身份绑定由服务端决定**：已登录时一律使用令牌中的用户 ID，
  请求体中的 `userId` 被忽略。修复前任何人都能填别人的 ID 冒名投票，
  导致"每天一票"、黑名单、投票记录全部算到他人头上
- **降权与封禁立即生效**：JWT 中的 `role` 是签发时的快照，
  修复前管理员被降权或账号被封禁后，旧令牌在有效期内（默认 7 天）仍具备原权限。
  现在每次鉴权读取数据库当前状态（60 秒缓存），窗口从 7 天压缩到 60 秒
- **登录防爆破**：接口层按 IP 限流 + 账号级锁定（连续 5 次失败锁 15 分钟）
- **防账号枚举**：不区分"用户不存在"与"密码错误"
- **防时序攻击**：用户不存在时也执行等价开销的 BCrypt 校验
- **JWT 密钥强度校验**：启动时校验长度 ≥ 32 字节，不满足直接启动失败
- **mock 模式防误上生产**：prod profile 下检测到 mock 直接启动失败

### 变更（不兼容）

- **投票在默认配置下需要登录**。生产环境（`application-prod.yml`）
  `app.security.require-login-to-vote` 为 `true`，未登录投票返回 401。
  dev 环境为 `false`，可继续匿名调试与压测
- **`/admin/**` 全部需要 `ADMIN` 角色**。现有管理员账号需手工设置：
  `UPDATE vote_user SET role='ADMIN' WHERE username='...'`
- **`VoteRequest.userId` 不再是必填项**，其含义变为"仅匿名投票时使用"
- 新增必填环境变量（prod）：`JWT_SECRET`；可选：`WECHAT_APP_ID` / `WECHAT_APP_SECRET`

### 已知限制

- 无刷新令牌机制，令牌过期（默认 7 天）后需重新登录
- 账号级锁定可被用于恶意锁死他人账号（防撞库与防锁死的固有矛盾）
- 角色变更最长有 60 秒生效延迟（由状态缓存 TTL 决定）

---

## [2.0.0] - 2026-09-15

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

## [1.0.0] - 2026-09-13

### 新增

- 基于 Redis Lua 的原子投票链路（活动状态校验 → 黑名单校验 → 每日重复校验 →
  票数累加 → 排行榜更新 → 写 Outbox）
- Transactional Outbox + RabbitMQ 削峰，手动 ACK、死信队列
- 基于 ZSet 的实时排行榜（TopN / 指定排名 / 分页）
- 多维度防刷：每日一票、用户/IP/设备黑名单、滑动窗口限流、落库幂等
- 两种缓存防击穿策略（互斥锁 / 逻辑过期）与活动预热
- 单文件可视化控制台 `panel.html`（KPI、投票模拟器、实时榜单、黑名单管理）
- SpringDoc OpenAPI 接口文档
