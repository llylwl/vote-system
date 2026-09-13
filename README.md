# 实时投票排行榜与防刷系统

基于 **Spring Boot 3.2 + MyBatis-Plus + Redis 7 + Redisson + RabbitMQ** 的生产级实时投票系统。
核心链路：**Redis Lua 原子投票 → Outbox 可靠消息 → RabbitMQ 削峰落库 → ZSet 实时排行榜 → 多维度防刷 → 缓存防击穿**。

## 一、技术栈与模块

| 模块 | 职责 |
|---|---|
| vote-common | 统一响应、异常、Redis/Redisson 配置、常量 |
| vote-model | 实体、Mapper（MyBatis-Plus）、DTO |
| vote-service | 投票服务、Outbox 发布、MQ 消费、排行榜、防刷限流、缓存防击穿 |
| vote-task | 定时任务（活动状态刷新与预热） |
| vote-web | Controller、全局异常、启动入口（可执行 jar） |

## 二、环境要求（本机已验证）

| 组件 | 版本 | 连接 |
|---|---|---|
| JDK | 21（JAVA_HOME） | `C:\Program Files\Java\jdk-21.0.10` |
| Maven | 3.9.14 | `D:\AppGallery\maven\apache-maven-3.9.14` |
| MySQL | 8.0.46（Windows 服务） | `root / root`，库 `vote_system` 自动创建 |
| Redis | 7.x（WSL Docker） | `localhost:6379`，无密码 |
| RabbitMQ | 3.13.7（WSL Docker） | `admin / admin`，管理台 `http://localhost:15672` |

## 三、快速启动

```bash
# 1. 构建（首次会从阿里云镜像下载依赖）
mvn clean package -DskipTests

# 2. 启动
java -jar vote-web\target\vote-web-1.0.0.jar
# 或在 IDEA 中直接运行 vote-web 模块的 VoteApplication

# 3. 访问接口文档（Swagger）
http://localhost:8080/swagger-ui.html

# 4. 可视化控制台（推荐）
http://localhost:8080/panel.html
```

首次启动会自动建库建表（`createDatabaseIfNotExist` + `schema.sql`）并写入种子数据（活动1 + 5个选手）。

## 控制台面板（panel.html）

单文件自包含面板，由 Spring Boot 同源托管，无需单独启动前端服务。功能：

| 模块 | 能力 |
|---|---|
| 系统状态栏 | Redis / RabbitMQ / MySQL 指示灯、队列积压、消费者数、自动刷新开关 |
| KPI 仪表盘 | 实时总票数、活动数、目标数、黑名单数、今日入库票数、今日拦截总数 |
| 投票模拟器 | 选择活动/目标、输入用户ID与设备指纹投票；随机模拟 5 票；实时反馈成功/拦截原因 |
| 实时排行榜 | ECharts Top10 柱状图 + 完整排名表格（3 秒自动刷新） |
| 活动管理 | 活动列表与状态、切换活动、创建活动、添加目标、一键预热 |
| 黑名单管理 | 添加 USER/IP/DEVICE 黑名单（支持过期时间）、列表、删除 |
| 防刷监控 | 今日拦截分类统计：活动不可投 / 黑名单 / 重复投票 / 限流 |

面板源码：`vote-web/src/main/resources/static/panel.html`（可在 VSCode 中直接编辑）。

## 四、接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/activity` | 创建活动 |
| POST | `/admin/activity/{id}/target` | 添加投票目标 |
| POST | `/admin/activity/{id}/warmup` | 预热活动（信息 + 排行榜 + TopN 缓存） |
| POST | `/admin/blacklist` | 添加黑名单（USER/IP/DEVICE） |
| DELETE | `/admin/blacklist/{id}` | 删除黑名单 |
| GET | `/admin/activity/list` | 活动列表 |
| POST | `/api/vote` | 投票（限流：IP 1秒20次） |
| GET | `/api/activity/{id}` | 活动详情（互斥锁防击穿缓存） |
| GET | `/api/activity/{id}/topn?n=10` | TopN 排行榜 |
| GET | `/api/activity/{id}/rank/{targetId}` | 指定目标排名 |
| GET | `/api/activity/{id}/ranking?start=0&end=49` | 排行榜（分页） |

### 快速验证（Demo 活动 id=1，目标 1~5）

```bash
# 预热活动（数据已由种子脚本写入，仍建议手动预热一次）
curl -X POST http://localhost:8080/admin/activity/1/warmup

# 模拟 5 个用户投票给目标 3
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" -d "{\"activityId\":1,\"targetId\":3,\"userId\":101}"
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" -d "{\"activityId\":1,\"targetId\":3,\"userId\":102}"
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" -d "{\"activityId\":1,\"targetId\":3,\"userId\":103}"

# 查看排行榜
curl http://localhost:8080/api/activity/1/topn?n=5

# 防刷验证：同一用户重复投票（应返回 4003 今日已投）
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" -d "{\"activityId\":1,\"targetId\":3,\"userId\":101}"

# 限流验证：1 秒内连发 21 次（应触发 429 请求过于频繁）

# 黑名单验证
curl -X POST http://localhost:8080/admin/blacklist -H "Content-Type: application/json" -d "{\"targetType\":\"USER\",\"targetValue\":\"999\",\"reason\":\"刷票\"}"
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" -d "{\"activityId\":1,\"targetId\":1,\"userId\":999}"
```

## 五、核心设计

1. **原子投票（Lua）**：`lua/vote_and_outbox.lua` 在一个 Redis 原子操作内完成「活动状态校验 → 黑名单校验 → 今日重复校验 → 总票数+1 → ZSet 排行榜+1 → 写 Outbox」，杜绝并发覆盖与掉票。
2. **可靠落库（Transactional Outbox）**：Lua 将消息 LPUSH 到 `vote:outbox:queue`，`VoteOutboxPublisher` 每 20ms RPOP 投递到 RabbitMQ，失败放回并累计重试，超 5 次转死信；消费者手动 ACK，失败重试 3 次后进 DLX。
3. **实时排行榜**：Redis ZSet 按票数降序，TopN / 排名 / 票数查询毫秒级返回。
4. **多维度防刷**：用户每日一票（Lua）+ 用户/IP/设备黑名单 + 滑动窗口限流（`@RateLimit` AOP）+ 落库幂等（Redis setIfAbsent + 数据库联合唯一索引兜底）。
5. **缓存防击穿**：互斥锁（`mutexCacheService`）与逻辑过期（`logicalExpCacheService`）两种策略 + 活动预热 + 延迟双删。
6. **MQ 可靠性**：Publisher Confirm + Return、消息/队列持久化、手动 ACK、死信队列兜底。

## 六、压测提示

使用本机 JMeter（`D:\AppGallery\apache-jmeter-5.6.3`）对 `/api/vote` 施压（500 并发），注意：
- 每用户每天仅可投一票，压测需使用不同 userId（或用 `X-User-Id` 头配合不同值）；
- 接口默认 IP 限流 20 次/秒，压测时可放宽 `@RateLimit` 参数或移除该注解。

## 七、实测验收结果（2026-09-15）

| 验证项 | 结果 |
|---|---|
| 构建 | `mvn clean package -DskipTests` → BUILD SUCCESS（5 模块 + 根工程） |
| 启动 | `java -jar vote-web\target\vote-web-1.0.0.jar` → Tomcat 8080，Started in ~10s，自动建库建表 + 种子数据 |
| 投票 | 7 个用户分投目标 1/2/3，全部 `code:0` 成功 |
| 排行榜 | TopN/全榜按票数实时降序（目标3:3票 目标1:3票 目标2:1票） |
| 重复投票 | 同用户再投 → `4003 您今日已投过票`（Lua 幂等拦截） |
| 黑名单 | 封禁 userId=999 后投票 → `4002 您已被限制投票` |
| 限流 | 同 IP 1 秒内 25 连发 → 12 过 13 拦（`429` 滑动窗口生效） |
| 异步落库 | Outbox → RabbitMQ（`vote.persist.queue` publish/ack 对账一致）→ 消费者手动 ACK → `vote_record` 3 行落库 |
| 缓存 | 活动详情互斥锁缓存正常，预热后毫秒级返回 |



