# Authentication API

认证 API，用于用户登录、令牌管理和身份验证。

## 认证方式

| 方式 | 说明 | 使用场景 |
|------|------|----------|
| Bearer Token | JWT 令牌 | API 调用 |
| API Key | API 密钥 | SDK 集成 |
| Admin Token | 管理员令牌 | 内部服务 |

## 登录

```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "admin@example.com",
  "password": "your_password"
}
```

**响应:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
  "expiresIn": 3600,
  "user": {
    "id": "user_123",
    "email": "admin@example.com",
    "name": "Admin User",
    "roles": ["admin"]
  }
}
```

## 刷新令牌

```http
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4..."
}
```

## 使用令牌

```http
GET /api/games
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

## API Key 认证

```http
POST /v1/batch
x-api-key: pk_your_api_key_here
Content-Type: application/json

[...]
```

## 权限范围

| 角色 | 权限 |
|------|------|
| `admin` | 全部权限 |
| `operator` | 游戏管理、用户管理 |
| `analyst` | 查看数据、创建报告 |
| `developer` | SDK 管理、API Key |
| `viewer` | 只读访问 |
