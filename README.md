# 实时投票排行榜与防刷系统

基于 **Spring Boot 3.2 + MyBatis-Plus + Redis 7 + Redisson + RabbitMQ** 的生产级实时投票系统。
核心链路：**Redis Lua 原子投票 → Outbox 可靠消息 → RabbitMQ 削峰落库 → ZSet 实时排行榜 → 多维度防刷 → 缓存防击穿**。

> **当前版本 v2.0.0** —— 相比 v1 做了一轮针对可靠性的系统性修复。
> 修了什么、为什么、怎么验证，见 **[docs/V2-RELIABILITY-NOTES.md](docs/V2-RELIABILITY-NOTES.md)**；
> 版本变更历史见 [CHANGELOG.md](CHANGELOG.md)。

## 一、技术栈与模块

| 模块 | 职责 |
|---|---|
| vote-common | 统一响应、错误码、异常、Redis/Redisson 配置、常量、时间工具 |
| vote-model | 实体、Mapper（MyBatis-Plus）、DTO（含校验注解） |
| vote-service | 投票服务、Outbox 投递、MQ 消费、排行榜、防刷限流、缓存、对账与重建 |
| vote-task | 定时任务（活动状态刷新与自愈预热、票数对账） |
| vote-web | Controller、全局异常、启动入口（可执行 jar） |

## 二、环境要求

| 组件 | 版本 | 开发环境默认连接 |
|---|---|---|
| JDK | 21 | `JAVA_HOME` 指向 JDK 21 |
| Maven | 3.9+ | — |
| MySQL | 8.0 | `root / root`，库 `vote_system` 自动创建 |
| Redis | 7.x | `localhost:6379`，无密码 |
| RabbitMQ | 3.13 | `admin / admin`，管理台 `http://localhost:15672` |

> Redis 7.0 以下**不要使用**：滑动窗口限流依赖 Lua 中 `math.random` 的随机性，
> 而 Redis 7.0 之前对 Lua 的 `math.random` 使用固定种子，会导致同毫秒内的请求
> 生成相同的 ZSet member，限流计数失真。

## 三、快速启动

```bash
# 1. 构建
mvn clean package -DskipTests

# 2. 启动（默认使用 dev profile，连接本机 MySQL/Redis/RabbitMQ）
java -jar vote-web/target/vote-web-1.0.0.jar
# 或在 IDEA 中运行 vote-web 模块的 VoteApplication

# 3. 访问
#    可视化控制台（推荐）  http://localhost:8080/panel.html
#    接口文档（Swagger）   http://localhost:8080/swagger-ui.html
#    健康检查              http://localhost:8080/actuator/health
```

**关于首次启动**：

- 表结构由 **Flyway** 自动迁移（`db/migration/V1~V3`），已有数据库会自动基线化，无需手工建表
- 演示数据（活动 1 + 5 个选手）仅在 `dev` profile 下灌入，生产环境不会出现
- 进行中活动的缓存由**启动预热**自动建立，无需手工调用预热接口

> ⚠️ **v1 → v2 升级注意**：异常现在返回真实的 HTTP 状态码
> （400/403/404/409/429/500），不再一律返回 200。若客户端依赖"HTTP 恒为 200"，需相应调整。

## 四、配置说明

配置按环境拆分：

| 文件 | 用途 |
|---|---|
| `application.yml` | 公共配置，**不含任何凭据** |
| `application-dev.yml` | 本地开发：连接 localhost，含本地默认密码 |
| `application-prod.yml` | 生产：**所有凭据必须由环境变量注入，无默认值** |

切换环境：

```bash
java -jar vote-web/target/vote-web-1.0.0.jar --spring.profiles.active=prod
# 或
export SPRING_PROFILES_ACTIVE=prod
```

### 关键环境变量

| 变量 | 说明 | 默认 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 激活的环境 | `dev` |
| `SERVER_PORT` | 服务端口 | `8080` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接（**prod 必填**） | — |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接（**prod 必填**） | — |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | MQ 连接（**prod 必填**） | — |
| `TRUSTED_PROXIES` | **可信反向代理地址**，逗号分隔，支持 CIDR | 空 |
| `TRUST_CLIENT_USER_ID` | 是否信任客户端 `X-User-Id`，**生产必须为 false** | `false` |
| `RECONCILE_AUTO_REPAIR` | 对账发现漂移时是否自动重建缓存 | `false` |

### ⚠️ 部署在 Nginx / SLB 之后必读

`X-Forwarded-For` 由客户端完全控制。本项目**只采信来自可信代理的该请求头**，
并从最右侧向左解析，避免伪造头绕过 IP 限流与 IP 黑名单。

若部署在反向代理之后却**未配置** `TRUSTED_PROXIES`：

- 所有请求会被识别为同一个代理 IP
- 限流会误伤全站用户（`limit=20/秒` 变成全站总配额）

```bash
# 示例：信任内网网段与网关地址
export TRUSTED_PROXIES="10.0.0.0/8,172.16.0.0/12,192.168.1.100"
```

## 五、接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/vote` | 投票（按可信 IP 限流 20 次/秒） |
| GET | `/api/activity/{id}` | 活动详情（互斥锁防击穿缓存） |
| GET | `/api/activity/{id}/topn?n=10` | TopN 排行榜 |
| GET | `/api/activity/{id}/rank/{targetId}` | 指定目标排名 |
| GET | `/api/activity/{id}/ranking?start=0&end=49` | 排行榜（分页） |
| POST | `/admin/activity` | 创建活动 |
| POST | `/admin/activity/{id}/target` | 添加投票目标 |
| POST | `/admin/activity/{id}/warmup` | **预热缓存（安全，不覆盖已有票数）** |
| POST | `/admin/activity/{id}/rebuild-cache` | **强制以数据库为准重建（故障恢复，会丢弃在途票）** |
| GET | `/admin/activity/{id}/reconcile` | **票数对账（只读，报告差异）** |
| POST | `/admin/blacklist` | 添加黑名单（USER / IP / DEVICE） |
| DELETE | `/admin/blacklist/{id}` | 删除黑名单 |
| GET | `/admin/activity/list` | 活动列表 |
| GET | `/admin/stats/dashboard` | 控制台仪表盘统计 |

### HTTP 状态码

异常会返回真实的 HTTP 状态码：

| 状态码 | 场景 | 业务码 |
|---|---|---|
| 400 | 参数校验失败 / 活动未开始或已结束 | 400 / 4001 |
| 403 | 命中黑名单 | 4002 |
| 404 | 活动或接口不存在 | 404 |
| 409 | 今日已投过票 | 4003 |
| 429 | 触发限流（附带 `Retry-After`） | 429 |
| 500 | 系统异常 | 500 / 5000 |

### 快速验证（Demo 活动 id=1，目标 1~5）

```bash
# 投票
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" \
  -d '{"activityId":1,"targetId":3,"userId":101}'

# 排行榜
curl "http://localhost:8080/api/activity/1/topn?n=5"

# 重复投票（应返回 HTTP 409 + code 4003）
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" \
  -d '{"activityId":1,"targetId":3,"userId":101}'

# 参数校验（应返回 HTTP 400）
curl -X POST http://localhost:8080/api/vote -H "Content-Type: application/json" \
  -d '{"activityId":1,"targetId":3}'

# 票数对账
curl "http://localhost:8080/admin/activity/1/reconcile"

# 限流验证：1 秒内并发 30 次（部分应返回 429）
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/vote \
    -H "Content-Type: application/json" \
    -d "{\"activityId\":1,\"targetId\":1,\"userId\":8$i}" &
done; wait
```

## 六、核心设计

1. **原子投票（Lua）**：`lua/vote_and_outbox.lua` 在一个 Redis 原子操作内完成
   「活动状态校验 → 黑名单校验 → 今日重复校验 → 总票数+1 → ZSet 排行榜+1 → 写 Outbox」。
2. **可靠落库（Transactional Outbox）**：Lua 将消息 `LPUSH` 到 `vote:outbox:queue`；
   Publisher 用 **`RPOPLPUSH` 原子搬到处理中队列**，投递并**收到 Broker 确认后**才移除；
   失败重试超限转死信。进程崩溃后残留消息在启动时自动恢复。
3. **重试策略**：消费失败时**重发一条带自增计数的新消息并 ACK 原消息**，
   经 TTL 重试队列延迟回流，超过 3 次进入死信队列并**落库留存**（可人工重放）。
4. **实时排行榜**：Redis ZSet 按票数降序，TopN / 排名 / 票数查询毫秒级返回。
5. **多维度防刷**：用户每日一票（按**自然日**，与数据库唯一索引口径一致）+
   用户/IP/设备黑名单 + 滑动窗口限流 + 落库幂等（Redis 标记 + 数据库唯一索引兜底）。
6. **缓存防击穿**：互斥锁与逻辑过期两种策略（均使用 Redisson 锁 + 空值占位防穿透）+
   启动预热 + 活动预热。
7. **票数对账**：定时比对数据库流水与 Redis 的票数差异并告警；
   提供只读对账与强制重建接口。**数据库是票数的唯一真相，Redis 只是它的缓存。**

## 七、实测验收结果（2026-09-16，v2.0.0）

| 验证项 | 结果 |
|---|---|
| 单元测试 | `mvn test` → 17 个用例全部通过（`DayUtilsTest` 7 + `ClientIpResolverTest` 10） |
| 构建 | `mvn clean package -DskipTests` → BUILD SUCCESS（5 模块 + 根工程） |
| Flyway 迁移 | 已有库自动基线化到 v0 → 应用 V1/V2/V3 → 当前版本 v3，`vote_time` 索引已补 |
| 启动 | Tomcat 8081，`Started VoteApplication in ~20s`，启动预热处理 1 个进行中活动，**0 ERROR** |
| 健康检查 | `GET /actuator/health` → HTTP 200，`status: UP`（含 DB 组件） |
| 投票 | `code:0` 成功，Outbox 排空（待投递 0、无处理中残留），`vote_record` 正常落库 |
| **跨天语义** | Key `vote:user:today:1:20260916:888001`，**TTL 86110 秒**（到当晚 24 点，而非固定 86400） |
| **跨天实测** | 用户 888001 于 9-15 23:19 投过票，9-16 凌晨可正常再投（修复前会被拦一整天） |
| 重复投票 | HTTP **409** + `4003 您今日已投过票` |
| 参数校验 | HTTP **400** + `userId: userId 必填` |
| 限流 | 30 并发 → 20 过 10 拦（HTTP **429**），Key 含方法维度 |
| **预热安全性** | 调用 `/warmup` 前后榜单完全一致（98/36/31/27/25），返回"排行榜已存在，未做改动" |
| **对账能力** | 人为注入 50 张假票 → 精确报出「目标[10] 数据库=3 Redis=53」；`/rebuild-cache` 后恢复一致 |
| 404 处理 | HTTP **404**（修复前被兜底处理器转成 500） |

## 八、部署

生产环境需要设置的环境变量：

```bash
export SPRING_PROFILES_ACTIVE=prod

export DB_URL="jdbc:mysql://mysql-host:3306/vote_system?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false"
export DB_USERNAME="vote_app"
export DB_PASSWORD="<数据库密码>"

export REDIS_HOST="redis-host"
export REDIS_PORT="6379"
export REDIS_PASSWORD="<Redis密码>"

export RABBITMQ_HOST="rabbitmq-host"
export RABBITMQ_PORT="5672"
export RABBITMQ_USERNAME="vote_app"
export RABBITMQ_PASSWORD="<MQ密码>"

# 部署在反向代理之后时必须配置
export TRUSTED_PROXIES="10.0.0.0/8"

java -jar vote-web/target/vote-web-1.0.0.jar
```

生产环境自动生效的配置：Flyway `clean` 禁用、Swagger 关闭、
SQL 日志关闭、健康检查详情隐藏、优雅停机。

## 九、后续规划

- **v3（规划中）**：用户模块（注册 / 登录 / 微信小程序 `code2session`）、
  JWT + Redis 白名单认证、`/admin/**` 鉴权与角色控制、控制台登录入口
- CI/CD、Dockerfile 与 docker-compose、Prometheus 指标

## 十、已知限制

- Lua 脚本使用 7 个 key 且未加 hash tag，**无法直接运行在 Redis Cluster 上**（单机部署不受影响）
- 排行榜 ZSet 与 Outbox 队列是全局单一 key，Redis 单线程下其写入速率即系统吞吐上限；
  Outbox 队列无 `MAXLEN`，长时间积压会形成大 key，需监控长度
- 管理端接口尚未鉴权（v3 处理）
