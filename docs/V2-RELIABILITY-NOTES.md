# v2.0.0 可靠性修复说明

> 本文记录 v2 修复的每一个问题：**现象 → 根因 → 改法 → 如何验证**。
> 建议按顺序阅读，它同时是一份"如何审查一个分布式投票系统"的清单。

---

## 一、背景：v1 的问题不是"功能缺失"，而是"机制不生效"

v1 的骨架设计是正确的：Lua 原子投票、Transactional Outbox、ZSet 排行榜、
数据库唯一索引兜底 —— 这些思路都对。但在一次针对运行时数据的实测中，发现了三个互相矛盾的数字：

| 数据源 | 票数（活动 1） |
|---|---|
| Redis 活动 Hash `total_votes` | 145 |
| Redis 排行榜 ZSet 各目标之和 | 145 |
| MySQL `vote_activity.total_votes` | 900 |
| MySQL `vote_record` 实际记录数 | **1044** |

**三份"真相"互相矛盾，而系统对此毫无察觉** —— 没有对账、没有告警、没有重建路径。

进一步排查发现，问题的共性是一句话：

> **代码里有这个机制 ≠ 机制在工作。**

重试计数写在了本地对象上、Publisher Confirm 没等结果、
数据库异常被 catch 后消息照样 ACK —— 每一条看起来都实现了，
实际运行中全都不生效。这才是 v2 要解决的核心。

---

## 二、修复清单

### 🔴 P0-1 「每天一票」实际是「距上次投票 24 小时一票」

**现象**：真实数据中最后一条投票是 `2026-09-15 23:59:44`。该用户在 9 月 16 日
**一整天都投不了票**，必须等到 23:59:44 之后 —— 尽管 9 月 16 日是新的一天。

**根因**：两层校验对"一天"的定义不一致。

| 层 | 位置 | "一天"的定义 |
|---|---|---|
| Redis | `RedisKeys.USER_TODAY` + `VoteService` | Key **不含日期**，TTL 固定 `86400`，从投票时刻起算 |
| MySQL | `vote_record.uk_activity_user_date` | `CAST(vote_time AS DATE)`，自然日 |

实测证据：key `vote:user:today:1:62741` 的 `TTL = 21956` 秒。
若按自然日对齐，此刻应已过期或接近重置，而不是还剩 6 小时倒计时。

**改法**：

- Key 改为 `vote:user:today:{activityId}:{yyyyMMdd}:{userId}`（`RedisKeys.USER_TODAY`）
- TTL 改为"到本地次日 00:00 的剩余秒数"（`DayUtils.secondsUntilNextMidnight()`）
- 新增 `DayUtils` 统一自然日口径，Redis 与数据库两侧共用

**验证**：凌晨实测，`TTL = 86110`（到当晚 24 点）。
用户 888001 于前一晚 23:19 投过票，次日凌晨可正常再投。
单元测试 `DayUtilsTest` 覆盖 00:00:00 / 23:59:59 / 23:19:28 三个边界。

---

### 🔴 P0-2 幂等标记先写、后落库，落库失败不回滚 → 静默丢票

**位置**：`VotePersistService`

**现象**：数据库瞬时故障时，这一票永久丢失，而 Redis 侧已经计过票。

**根因**（三个缺陷叠加）：

```java
// 旧代码
setIfAbsent(idempotentKey, "1", 25, HOURS);   // ① 标记先写
if (FALSE.equals(isNewVote)) return;          //   后续重试会被误判为"重复"
voteRecordMapper.insert(record);              // ② 这里失败
} catch (Exception e) {
    log.warn("写入失败（可能重复）...");        // ③ 吞掉异常 → 消费者 ACK
}
```

①②③ 叠加的结果：落库失败 → 标记已占位 → 重试被判重复 → 消息 ACK → **票没了**。

此外，幂等 Key 的日期取自 `LocalDate.now()`（**消费时刻**），
而数据库唯一索引算的是 `DATE(vote_time)`（**投票时刻**）。
23:59:50 投出、00:00:10 才被消费的消息，两侧记的根本不是同一天。

**改法**：

1. 只吞 `DuplicateKeyException`（幂等命中），其余异常**撤销幂等标记后抛出**以触发重试
2. 幂等 Key 的日期改用 `DayUtils.isoOf(record.getVoteTime())`，与数据库口径对齐
3. 去掉 `@Transactional`：单条 insert 本身原子，加了反而引入
   "catch 后事务已标记 rollback-only → 提交时抛 `UnexpectedRollbackException`"的陷阱
4. `batchCreateVoteRecords` 自调用导致注解失效的问题一并消除
5. Redis 不可用时降级为"由数据库唯一索引兜底"，不让投票失败

**验证**：`createVoteRecord` 返回 `PersistResult` 枚举（INSERTED / DUPLICATE / MALFORMED），
消费者据此决定 ACK、重试还是转死信。

---

### 🔴 P0-3 消费端重试是死循环，死信队列永远收不到消息

**位置**：`VoteMessageConsumer`

**现象**：README 宣称"失败重试 3 次后进 DLX"，实际从未生效。

**根因**：

```java
// 旧代码
message.getMessageProperties().getHeaders().put("retryCount", currentRetry);  // 改的是本地对象
channel.basicNack(deliveryTag, false, true);   // Broker 重投【原始消息】，header 原样返回
```

`retryCount` 永远读不到 → `currentRetry` 恒为 1 → 永远到不了 `MAX_RETRY_COUNT=3`
→ 死信分支不可达 → 消息被无限热重投（`prefetch=1` 且无退避，CPU 空转）。

**触发点**：Redis 抖动时 `setIfAbsent` 抛异常（它在 try 块**外面**）→ 消费者 catch → 立即 requeue。

**改法**：改为**重发一条带自增计数的新消息，然后 ACK 原消息**。

```
消费失败 → 重发到 vote.persist.retry（带 retryCount+1）→ ACK 原消息
                ↓ (TTL 5s 到期，Broker 自动回流)
           vote.exchange → vote.persist.queue → 重新消费
                ↓ (retryCount >= 3)
           vote.dlx.exchange → vote.persist.dlq → 落库留存
```

计数随新消息持久化在 Broker 上，重启不丢；延迟队列天然带退避。

**顺带**：死信消费者原先只打日志就 ACK（消息彻底消失），
现在落库到 `vote_dead_letter` 表（Flyway V3），支持人工重放。

---

### 🔴 P0-4 Outbox 先删后发，且不等确认 → 丢消息

**位置**：`VoteOutboxPublisher`

**根因**（三个问题）：

1. `rightPop` 是**破坏性读取**，消息在投递前就从 Redis 移除。
   进程在弹出后、投递前崩溃 → 消息蒸发。
2. `convertAndSend` 不传 `CorrelationData`，**不等 Broker 确认**。
   `RabbitConfirmConfig` 的回调只能异步打日志，不参与任何决策 ——
   README 说的"Publisher Confirm 保证可靠投递"没有落地。
3. 吞吐被锁死：`@Scheduled(fixedDelay = 20)` 每次只处理 **1 条** → 上限 **50 msg/s**。
   500 并发投票时队列以 450/s 增长，且该 key 无 `MAXLEN`、无 TTL → 堆成大 key。

**改法**：

- `RPOP` → `RPOPLPUSH`，原子搬到 `vote:outbox:processing`；
  投递并**收到确认后**才从处理中队列移除
- 携带 `CorrelationData` 并 `getFuture().get(5s)` 等待确认；
  同时检查 `getReturned()`（消息到达交换机但无法路由的情况，只看 ack 会误判成功）
- 单次轮询批量处理（上限 500 条），吞吐提升约 3 个数量级
- 启动时 `@PostConstruct` 把处理中队列的残留消息搬回，恢复崩溃前未完成的投递
- 重试超限转死信，不再静默 `return` 丢弃

**验证**：实测投票后 `LLEN vote:outbox:queue = 0`、处理中队列不存在（无孤儿消息）。

---

### 🔴 P0-5 全新环境启动后，活动完全无法投票

**现象**：新部署（或 Redis 被清空）后，所有投票返回 `4001 活动未开始或已结束`。

**根因**：三个条件叠加。

```java
// ActivityStatusTask：只有状态发生"跳变"时才预热
if (activity.getStatus() != newStatus) {
    ...
    if (newStatus == 1) activityWarmUpService.warmUpActivity(id);
}
```

1. 种子数据 `data.sql` 直接把活动插成 `status = 1`
2. 首次启动时 `newStatus` 也算出 1 → **没有跳变** → 不预热
3. `warmUpAllActiveActivities()` 定义了但**全局零调用点**（grep 证实）

结果：Redis 中始终没有 `vote:activity:info:1`，而 Lua 脚本第一件事就是
`HGET status`，取不到即为活动不可投 → **整个活动投不了票**，只能人工点"一键预热"。

README 里写的"仍建议手动预热一次"，实际是**必须**，不预热系统不可用。

**改法**：

- 新增 `CacheWarmUpRunner`（`ApplicationRunner`），启动时预热所有进行中活动，
  消除"新部署后最多一分钟空窗期"的问题
- `ActivityStatusTask` 增加**自愈**：Hash 或排行榜缺失时补齐，
  不再依赖状态跳变（`ActivityWarmUpService.isActivityCached()`）
- 定时任务加 Redisson 分布式锁，避免多实例重复执行

---

### 🔴 P0-6 调用"预热"会把进行中活动的排行榜清零

**位置**：`AdminController.warmUp` + `CacheWarmUpService.warmUpRanking`

**根因**：

```java
// AdminController：所有目标一律传 0
targets.forEach(t -> initVotes.put(t.getId(), 0L));
// CacheWarmUpService：ZADD 对已存在的 member 是【覆盖 score】
stringRedisTemplate.opsForZSet().add(rankKey, targetId, 0);
```

而 README 第 81 行让用户执行这条 curl，控制台上还有"一键预热"按钮 ——
**照着文档操作一次，榜单就归零了**，且数据库中没有可恢复的票数字段。

这很可能就是背景中"Redis 145 / MySQL 1044"那 899 票差额的成因。

**改法**：新增 `VoteReconcileService`，提供三个安全级别递增的操作：

| 方法 | 行为 | 安全性 |
|---|---|---|
| `check` | 只读对账，报告差异 | 任何时刻可调用 |
| `warmUp` | 只补齐**缺失**的部分，绝不覆盖已有数据 | 活动进行中也可调用 |
| `forceRebuild` | 以数据库为准强制覆盖 | 会丢弃在途票，仅用于故障恢复 |

`/warmup` 接口改为调用 `warmUp`。实测：调用前后榜单完全一致（98/36/31/27/25）；
重复调用返回 `"排行榜已存在，未做改动"`。

同时删除了 `CacheWarmUpService.warmUpTopNCache`等死代码。

---

### 🔴 P0-7 防刷体系可被一个请求头绕过

**位置**：`RateLimitAspect`、`VoteController.getClientIp`

**根因**：

```java
// RateLimitAspect：限流 Key 优先取客户端可任意设置的 header
String userId = request.getHeader("X-User-Id");
if (userId != null) return RedisKeys.RATE_LIMIT + "user:" + userId;   // 每次换值 = 全新桶

// VoteController：无条件信任 XFF 的最左值（正是客户端可写的那段）
ip = ip.split(",")[0].trim();   // 该值会作为 IP 黑名单的 Key
```

后果：

- 每次请求带随机 `X-User-Id` → **限流完全绕过**
- 伪造 `X-Forwarded-For` → **同时绕过 IP 限流和 IP 黑名单**
- 只覆盖 `/api/vote`，所有读接口与 `/admin/**` 无限流
- 另一个反向事故：上线到 Nginx/SLB 后若未配置可信代理，
  `getRemoteAddr()` 返回代理 IP，所有用户共用一个桶 → 20 次/秒变成**全站总配额**

**改法**：

- 新增 `ClientIpResolver` + `SecurityProperties`：维护**可信代理白名单**，
  从 XFF **最右侧**向左跳过可信地址，第一个不可信地址才是真实客户端；
  请求不来自可信代理时**完全不采信**该头
- `X-User-Id` 仅在显式开启（`app.security.trust-client-user-id`，仅 dev）时采信
- 限流 Key 加入方法维度，避免一个用户的读接口吃掉投票配额
- 读接口与 dashboard 补充限流
- `server.forward-headers-strategy: none`，防止框架层面又把它信回来

**验证**：30 并发请求 → 20 过 10 拦（429）；
单元测试 `ClientIpResolverTest` 10 条用例覆盖伪造场景。

---

### 🟠 P1-1 缓存穿透 + 无限递归 → Tomcat 线程池被拖死

**位置**：`MutexCacheService`

```java
// 旧代码
if (data != null) { 回写缓存 }              // ① 空结果不缓存 → 穿透
...
Thread.sleep(50);
return getWithProtection(key, dbFallback);  // ② 无上限递归，注释自己都承认了
```

并发请求一个不存在的活动 ID → 缓存永不命中 → 除一个线程外全部卡在递归里
→ 200 个 Tomcat 线程耗尽 → **连投票接口一起挂死**。

同一处还有锁误删：`setIfAbsent(lockKey, "1", 10s)` + 无条件 `delete`，
没有持锁者标识。回源慢于 10 秒时锁自动过期，线程 A 返回时删掉的是线程 B 的锁。

**改法**：

- 改用 Redisson `RLock`（看门狗自动续期 + `isHeldByCurrentThread()` 校验）
- 空结果写短 TTL 占位（`NULL_VALUE_TTL_SECONDS = 60`），消除穿透
- 抢锁失败改为**有界等待**（`tryLock(3s)`），超时降级为直连回源，不再自旋递归
- 修复 `LogicalExpCacheService` 的同类问题：空值路径此前**完全没有互斥**
  （而这正是防击穿的主场景）、`CallerRunsPolicy` 会把重建压到请求线程上、
  TTL 下界未校验会写出"一写入就过期"的缓存、静态线程池永不关闭

---

### 🟠 P1-2 活动详情把实时票数冻结 1 小时

```java
m.put("totalVotes", voteRankService.getActivityTotal(activityId));  // 塞进 3600s 缓存
```

而全项目 `evict()` **零调用**、`DelayDoubleDeleteService` **零调用** ——
缓存失效链路整体是断的。用户打开活动详情，看到的是一小时前的票数。

**改法**：缓存只放静态元数据，实时票数每次单独读取。
`DelayDoubleDeleteService` 因零调用且语义无法真正解决主从延迟，予以删除。

---

### 🟠 P1-3 管理端无参数校验

`activity_desc` 是 TEXT 类型却无长度约束，可被塞入 MB 级内容；
`userId` 可为负数；`deviceFingerprint` 超过列宽 `VARCHAR(64)` 会导致落库失败。

**改法**：新增 `ActivityCreateRequest` / `TargetCreateRequest` / `BlacklistCreateRequest`，
加 `@NotBlank` / `@Size` / `@Pattern`；控制器启用 `@Valid`。
补充跨字段校验：开始时间必须早于结束时间、黑名单过期时间必须晚于当前、
添加目标时活动必须存在（否则产生指向不存在活动的孤儿目标，其 ID 被提交投票后会污染排行榜）。

**顺带修复**：黑名单 `expireTime` 已经过去时，旧代码会插入数据库但**不写 Redis**，
结果黑名单在列表里看得见、实际不生效。现在直接拒绝。

---

### 🟠 P1-4 分页参数无上限

`?end=99999999` 会让 Redis 单线程执行 `ZREVRANGE key 0 99999999`，
构造上亿元素的响应，阻塞该实例上所有其它请求，应用侧还要把结果物化成 List。

**改法**：`VoteRankService` 夹紧到 `[0, 100]` 并告警；同时容错处理非数字的历史脏成员
（原先会抛 `NumberFormatException` 变成 500）。

---

### 🟠 P1-5 错误码散落，所有异常都返回 HTTP 200

业务码是散落的魔法数字（Controller 里的 4001/4002/4003/5000，
异常类里的 429，处理器里的 500），且一律以 HTTP 200 返回 ——
网关无法识别限流、客户端重试库无法退避、监控统计到的错误率恒为 0。

**改法**：新增 `ErrorCode` 枚举作为唯一来源，携带业务码 + HTTP 状态码 + 默认文案。
`GlobalExceptionHandler` 返回真实状态码（429 附带 `Retry-After`），
并补充 `@Valid` 校验异常、缺参、类型不匹配、请求体不可解析、路径不存在等处理。
实测：参数缺失 → 400，重复投票 → 409，限流 → 429，路径不存在 → 404。

---

### 🟠 P1-6 没有对账机制（v1 最根本的缺失）

**改法**：新增 `VoteReconcileTask`，每 5 分钟比对数据库流水与 Redis 的差异并**告警**。

**关于自动修复（重要设计取舍）**：默认**关闭**，只告警。
开启后也只在 Outbox 排空时执行重建 —— 因为重建是"以数据库覆盖 Redis"，
若此时还有未落库的在途票，这些票会被抹掉。判断失误的代价比漂移本身更大。

实测：人为注入 50 张假票后，对账精确报出「目标[10] 数据库=3 Redis=53」，
`/rebuild-cache` 后可恢复一致。

---

### 🟡 P2 其他

| 问题 | 改法 |
|---|---|
| `spring.sql.init.mode: always` 每次生产启动都塞演示数据 | 迁移到 Flyway；演示数据仅 dev profile 下灌入 |
| 表结构无版本管理 | 引入 Flyway，`schema.sql` → `db/migration/V1~V3` |
| 凭据硬编码 | 配置拆分 `-dev` / `-prod`，生产全部走环境变量且**不提供默认值** |
| 无 `vote_time` 索引，面板每次刷新全表扫描 | Flyway V2 补 `idx_vote_time` |
| `logic-delete-field: deleted` 但表无此列（配置漂移） | 移除该配置 |
| `listener.simple.prefetch` 被自定义工厂覆盖（误导） | 移除并在注释中说明 |
| 统计 Key 无 TTL，每天新增 4 个永久 Key | 首次创建时设置 30 天过期 |
| 定时任务多实例重复执行 | 加 Redisson 分布式锁 |
| Outbox 与定时任务共用单线程调度器 | 配置调度线程池 |
| 无优雅停机 / 健康检查 | `server.shutdown: graceful` + actuator |
| 日志打印全部 SQL（生产同步 IO 瓶颈） | 仅 dev 开启，生产用 `NoLoggingImpl` |
| 零测试 | 新增 17 个单元测试（`DayUtilsTest` 7 + `ClientIpResolverTest` 10） |

---

## 三、如何验证本次修复

```bash
# 1. 单元测试
mvn test

# 2. 启动（dev 环境，默认 profile）
mvn clean package -DskipTests
java -jar vote-web/target/vote-web-1.0.0.jar

# 3. 健康检查（应返回 HTTP 200 且 status=UP）
curl http://localhost:8080/actuator/health

# 4. 跨天语义：Key 应含当天日期，TTL 应小于 86400
#    vote:user:today:1:20260916:888001

# 5. 对账（应报告是否一致）
curl http://localhost:8080/admin/activity/1/reconcile

# 6. 预热安全性：调用前后榜单应完全一致
curl -X POST http://localhost:8080/admin/activity/1/warmup

# 7. 参数校验（应返回 HTTP 400）
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" \
  -d '{"activityId":1,"targetId":3}'
```

---

## 四、本次未做的事（阶段二）

- 用户模块（注册 / 登录 / 微信小程序 code2session）
- JWT + Redis 白名单认证
- `/admin/**` 鉴权与角色控制
- 控制台面板的登录入口与 token 注入
- CI/CD、Dockerfile、Prometheus 指标

---

## 五、一条给未来的提醒

本次修复的问题有一个共同特征：**代码看起来实现了某个机制，但它并不生效**。

写这类代码时，判断标准不是"我写了重试"，而是：
**"这条消息失败后，我能不能指出它的重试次数存在哪里、重启后还在不在、超过上限后去了哪？"**

答不上来，机制就是假的。
