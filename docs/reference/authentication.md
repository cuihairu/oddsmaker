# Authentication

控制服务的认证、令牌与权限模型。

## 认证方式

| 方式 | 携带方式 | 使用场景 |
|------|------|----------|
| Session Token | `Authorization: Bearer {token}` | 控制台/控制面 API 调用 |
| Admin Token | `x-admin-token: {token}` | 引导类管理端点（建号、发 Key 等） |
| API Key | `x-api-key: {key}` | 网关事件上报（`/v1/batch`） |
| HMAC 签名 | `x-signature: t={秒}, s={hex}` | Server SDK 场景：SERVER 型 key 在 API Key 之上对请求体签名 |

## 登录

`POST /api/auth/login`，请求体用 `username`（不是邮箱）：

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your_password"
}
```

成功响应只有两个字段，没有 refresh token，也没有 expiresIn：

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "user_123",
    "username": "admin",
    "roles": ["ADMIN"]
  }
}
```

失败口径：账号不存在与密码错误统一返回 401（不泄露账号是否存在）；账号被禁用或锁定返回 403。登录、登录失败、登出都写审计日志。

## 登出

`POST /api/auth/logout`。JWT 是无状态的，服务端不保存会话；登出由客户端删除本地 token 完成，服务端仅在能识别身份时补一条登出审计。没有 `/api/auth/refresh` 端点——token 过期后重新登录。

## 使用令牌

```http
GET /api/games
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

## API Key 认证

事件上报走网关，不带 Bearer：

```http
POST /v1/batch
x-api-key: pk_your_api_key_here
Content-Type: application/x-ndjson

{...}
```

key 分三档：`client`（客户端 SDK）、`server`（服务端，必须配 HMAC `x-signature`，见下）、`admin`（只读管理）。client key 携带签名头会被拒（401 `signature_not_supported`）。

### HMAC 请求签名（server key）

SERVER 型 key 的每个请求都要带签名头，格式与校验窗口：

```
x-signature: t=1760000000, s=5256a8...（hex）
s = hex(hmac_sha256(secret, t + "." + raw_body))
```

时间戳 `t` 与服务器时间差超过 ±300 秒返回 401；同一签名重复提交被重放防护拦截（401 `replay_detected`）；secret 轮换后旧签名同样 401。

## 权限范围

角色通过 `/api/users/{userId}/role-assignments` 分配，支持 global / game / environment 三级 scope。内置 8 个角色：

| 角色 | 权限 |
|------|------|
| `operator` | 公司级运营管理员，全部权限 |
| `game_admin` | 单游戏内管理 |
| `analyst` | 查看分析数据、创建报告 |
| `marketing` | 用户分析、A/B 实验 |
| `finance` | 收入与商业化数据 |
| `developer` | SDK 管理、API Key |
| `viewer` | 只读 |
| `qa` | 测试环境操作 |

没有 `owner` 和 `risk_admin` 角色；风控操作权限挂在 operator/game_admin 上。鉴权细节（权限点清单、scope 回收）见控制面「角色管理」页。
