# v3.0.0 用户模块与认证说明

> 本文说明 v3 引入的用户体系：**设计取舍 → 三种登录方式 → 小程序对接 → 安全加固 → 实测结果**。
> 面向准备把后端接入微信小程序的使用者。

---

## 一、这次做了什么

v2 修好了"投票链路本身可靠"，但整个系统还是**裸奔**的：
`/admin/**` 无鉴权、投票人可以随便填别人的 `userId`、没有任何用户概念。

v3 补上这一层：

| 能力 | 说明 |
|---|---|
| 用户模块 | 注册、登录、登出、改密、封禁状态 |
| 三种登录方式 | 微信小程序登录 / 账号密码 / 微信 + 密码同账号（同一张表） |
| 令牌体系 | JWT + Redis 白名单，支持主动失效 |
| 权限模型 | `USER` / `ADMIN` 两级，`/admin/**` 全量保护 |
| 投票身份绑定 | 登录后投票人身份取自令牌，请求体中的 `userId` 被忽略 |
| 控制台改造 | `panel.html` 增加登录界面，请求自动携带令牌 |
| 安全加固 | 登录限流、账号锁定、防账号枚举、防时序攻击 |

---

## 二、核心设计取舍

### 2.1 为什么是「JWT + Redis 白名单」而不是纯 JWT

纯 JWT 的问题是**签发后无法提前失效**。用户点"退出登录"，服务端其实仍然认这个令牌；
改密码后旧令牌照样能用；管理员想强制下线某个人也做不到。

纯 Redis 会话（把 token 当 key）能解决上述问题，但每个请求都要查一次 Redis，
且无法在网关层做无状态验签。

这里采用两者结合：

```
JWT 负责「这枚令牌是不是我签发的、有没有过期」—— 通过验签完成，无需查库
Redis 白名单负责「这枚令牌现在还算不算数」—— 一次 Redis 查询
```

代价是每个需登录的请求多一次 Redis 读；收益是**退出登录、强制下线、改密踢人全部真实生效**。

> 可用性取舍：白名单查询失败时**拒绝请求**（fail-closed），而不是放行。
> 若降级放行，等于"Redis 一挂，所有已登出的令牌全部复活"。
> 对本项目影响有限——投票链路本身也依赖 Redis。

### 2.2 为什么角色不直接信令牌里的 `role`

JWT 里的 `role` 是**签发那一刻的快照**。如果不额外校验：

- 管理员被**降权**后，旧令牌在有效期内（默认 7 天）**仍然拥有管理权限**
- 账号被**封禁**后，旧令牌在有效期内**仍然可以正常调用接口**

这是实测中发现并修复的真实漏洞。修法是每次鉴权时读一份「数据库当前状态」，
用短 TTL（60 秒）缓存避免每请求查库。窗口从 **7 天压缩到 60 秒**，
需要立即生效时调用 `UserStateService.invalidate(userId)`。

### 2.3 为什么投票身份必须由服务端决定

早期实现里 `userId` 来自请求体，谁都能填。这在引入用户体系后会变成：
登录用户填别人的 ID 投票 → **"每天一票"、黑名单、投票记录全部算到别人头上**。

现在：

| 情况 | 行为 |
|---|---|
| 已登录 | 一律使用令牌中的用户 ID，请求体中的 `userId` 被忽略（并记 WARN 日志） |
| 未登录 + `require-login-to-vote=true`（生产） | 返回 401 |
| 未登录 + `require-login-to-vote=false`（开发） | 允许用请求体中的 `userId`，便于本地压测 |

---

## 三、三种登录方式

### 3.1 账号密码

```bash
# 注册（用户名 3~32 位字母/数字/下划线，密码 8~64 位）
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Passw0rd!2026","nickname":"Alice"}'

# 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Passw0rd!2026"}'
```

两者都返回：

```json
{
  "code": 0,
  "data": {
    "token": "eyJhbGciOiJIUzM4NCJ9...",
    "expiresIn": 604800,
    "user": { "id": 1, "username": "alice", "nickname": "Alice", "role": "USER" }
  }
}
```

> 密码用 **BCrypt** 存储（自带随机盐），数据库里看不到明文。
> 响应中**永远不包含 `passwordHash`** —— 对外一律使用 `UserProfile` DTO，
> 而不是直接序列化 `User` 实体。

### 3.2 微信小程序登录

```bash
curl -X POST http://localhost:8080/api/auth/wechat-login \
  -H "Content-Type: application/json" \
  -d '{"code":"<wx.login 返回的 code>","nickname":"可选","avatarUrl":"可选"}'
```

服务端拿 code 调微信 `jscode2session` 换 openid；
**openid 已存在则直接登录，不存在则自动注册** —— 小程序场景没有独立的"注册"步骤。

> **code 必须由服务端换取 openid**：AppSecret 绝不能下发到客户端，
> 且 code 只能用一次、5 分钟过期。

### 3.3 本地开发没有 AppID 怎么办

开启 mock 模式（dev 环境已默认开启）：

```yaml
app:
  wechat:
    mock-enabled: true   # openid 由 code 直接推导，不请求微信接口
```

同一个 code 永远映射到同一个 openid，便于反复测试同一个"微信用户"。

> ⚠️ **生产环境开启 mock 会导致应用直接启动失败**（启动时有硬校验）。
> 因为该模式下任何人都能凭空构造 code 登录任意账号。

---

## 四、微信小程序对接指南

### 4.1 准备

1. 在[微信公众平台](https://mp.weixin.qq.com)注册小程序，拿到 **AppID** 与 **AppSecret**
2. 配置服务器域名：小程序后台 → 开发 → 开发设置 → 服务器域名 → `request 合法域名`
   填你的后端域名（必须是 **HTTPS**，且已备案）
3. 设置环境变量：

```bash
export WECHAT_APP_ID="<你的小程序AppID>"
export WECHAT_APP_SECRET=your_app_secret
export WECHAT_MOCK_ENABLED=false
```

### 4.2 小程序端代码

```javascript
// utils/auth.js —— 登录并缓存令牌
const BASE_URL = 'https://your-domain.com';

export function login() {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (!res.code) return reject(new Error('wx.login 未返回 code'));
        wx.request({
          url: BASE_URL + '/api/auth/wechat-login',
          method: 'POST',
          header: { 'Content-Type': 'application/json' },
          data: { code: res.code },
          success: (r) => {
            if (r.data.code === 0) {
              wx.setStorageSync('token', r.data.data.token);
              wx.setStorageSync('user', r.data.data.user);
              resolve(r.data.data);
            } else {
              reject(new Error(r.data.message));
            }
          },
          fail: reject
        });
      },
      fail: reject
    });
  });
}

// 统一请求封装：自动带令牌 + 401 自动重新登录
export function request(options) {
  return new Promise((resolve, reject) => {
    const doRequest = () => wx.request({
      ...options,
      url: BASE_URL + options.url,
      header: {
        'Content-Type': 'application/json',
        ...(options.header || {}),
        'Authorization': 'Bearer ' + (wx.getStorageSync('token') || '')
      },
      success: (r) => {
        if (r.statusCode === 401) {
          // 令牌失效：重新登录后重试一次
          login().then(doRequest).catch(reject);
          return;
        }
        resolve(r.data);
      },
      fail: reject
    });
    doRequest();
  });
}
```

### 4.3 小程序端调用示例

```javascript
import { login, request } from './utils/auth';

// 进入页面时静默登录
onLoad() {
  login().then(user => {
    this.setData({ user });
  });
}

// 投票
onVote(targetId) {
  request({
    url: '/api/vote',
    method: 'POST',
    data: { activityId: 1, targetId }
  }).then(res => {
    if (res.code === 0) wx.showToast({ title: '投票成功' });
    else wx.showToast({ title: res.message, icon: 'none' });
  });
}
```

> 注意投票请求体里**不需要**传 `userId` —— 服务端会从令牌里取。
> 传了也会被忽略。

### 4.4 需要注意的小程序限制

| 限制 | 说明 |
|---|---|
| code 只能用一次 | 不能用同一个 code 反复登录，每次都要重新 `wx.login()` |
| 必须 HTTPS | 后端需配置证书；本地调试可在开发者工具中勾选"不校验合法域名" |
| 域名需备案 | 国内服务器必须完成 ICP 备案 |
| `getUserProfile` 已收紧 | 昵称/头像需用户主动授权后单独获取，再回传给后端 |

---

## 五、权限模型

| 角色 | 权限 |
|---|---|
| `USER` | 调用 `/api/**` 下的业务接口 |
| `ADMIN` | `USER` 的全部权限 + `/admin/**` 全部管理接口 |

`AdminController` 上标注了类级 `@RequireAdmin`，一次覆盖全部管理接口 ——
用类级注解而不是逐个方法标注，是为了**避免将来新增接口时忘记加权限**。

### 如何创建第一个管理员

新注册用户都是 `USER`，需要手工提升：

```sql
UPDATE vote_user SET role = 'ADMIN' WHERE username = '你的用户名';
```

然后**重新登录**（或等 60 秒让状态缓存过期）即可访问管理接口。

> 生产环境建议做一个"仅允许内网访问"的管理员初始化接口，或直接在数据库里操作。

---

## 六、安全加固清单

除了常规实现，这次还处理了这些容易被忽略的点：

| 风险 | 处理 | 位置 |
|---|---|---|
| **暴力破解** | 接口层按 IP 限流（登录 10 次/分钟）+ **账号级锁定**（连续 5 次失败锁 15 分钟） | `AuthController` / `UserService` |
| **账号枚举** | 不区分"用户不存在"与"密码错误"，统一返回同一提示 | `UserService.login` |
| **时序攻击** | 用户不存在时也执行一次等价开销的 BCrypt 校验，拉平响应时间 | `UserService.initDummyHash` |
| **令牌泄露后无法收回** | Redis 白名单 + `jti`，支持单设备登出与全设备登出 | `TokenStore` |
| **改密后旧令牌仍可用** | 改密成功后自动撤销该用户全部令牌 | `UserService.changePassword` |
| **降权/封禁不生效** | 每次鉴权读数据库当前状态（60 秒缓存） | `UserStateService` |
| **密码哈希外泄** | 对外一律用 `UserProfile` DTO，绝不返回实体 | `UserProfile.from` |
| **JWT 密钥过弱** | 启动时校验长度 ≥ 32 字节，不满足直接启动失败 | `JwtProperties.validate` |
| **mock 模式误上生产** | prod profile 下检测到 mock 直接启动失败 | `WechatAuthService.validateConfig` |
| **ThreadLocal 串号** | 拦截器 `afterCompletion` 中强制清理 `UserContext` | `AuthInterceptor` |
| **冒名投票** | 投票人身份一律取自令牌，忽略请求体 | `VoteController.resolveVoterId` |

### 已知取舍

- **账号锁定可被用于恶意锁死他人账号**：攻击者用错误密码反复尝试即可锁定目标。
  这是"防撞库"与"防锁死"的固有矛盾。当前用 15 分钟自动解锁折中；
  若更怕锁死，可改为按「账号 + IP」组合计数。
- **角色变更最长 60 秒延迟生效**：由状态缓存 TTL 决定。
  需要立即生效时调用 `UserStateService.invalidate(userId)`。
- **JWT 一旦泄露，在有效期内（默认 7 天）可被使用**：只能靠白名单主动撤销。
  生产建议缩短 `JWT_EXPIRE_SECONDS` 并配合刷新令牌机制。

---

## 七、实测结果（2026-09-17）

| 验证项 | 结果 |
|---|---|
| 单元测试 | 30 个用例全部通过（`JwtServiceTest` 8 + `JwtPropertiesTest` 5 + `DayUtilsTest` 7 + `ClientIpResolverTest` 10） |
| Flyway | V4 用户表迁移成功，当前版本 v4 |
| 注册 | 成功，响应中**无 `passwordHash`** |
| 重复注册 | HTTP **409** + `4004 用户名已被占用` |
| 密码过短 | HTTP **400** + `password: 密码长度需为 8~64 位` |
| 登录 | 返回 246 字符 JWT |
| 无令牌访问 `/admin/**` | HTTP **401** |
| 普通用户访问 `/admin/**` | HTTP **403** |
| 伪造令牌 | HTTP **401** |
| **升为 ADMIN 后旧令牌** | HTTP **200**（以数据库当前角色为准） |
| **降为 USER 后旧令牌** | HTTP **403** ✅ 修复前会一直是 200 直到令牌过期 |
| **封禁后旧令牌** | HTTP **403** + `4006 账号已被封禁` ✅ 修复前仍可正常使用 |
| 登出 | 登出后同一令牌访问 `/me` → HTTP **401** |
| 微信登录（mock） | 首次自动注册，同一 code 再次登录命中同一用户 |
| **投票身份绑定** | 带 token 且请求体写 `userId=9999` → 实际落库 `user_id=1`，`user_id=9999` 记录数 **0** |
| 账号锁定 | 第 1~5 次 `4005`，第 6 次 `4009`（HTTP 429）；锁定期间正确密码也被拒 |
| 控制台面板 | `/panel.html` 正常加载，含登录遮罩 |

---

## 八、本次未做的事

- 刷新令牌（refresh token）：当前令牌 7 天有效，过期需重新登录
- 手机号验证码登录
- 用户资料编辑、头像上传
- 管理员对用户的管理界面（封禁/解封/改角色）
- CI/CD、Dockerfile、Prometheus 指标
