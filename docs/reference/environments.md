# Environments API

环境管理 API，用于管理游戏的不同部署环境（dev、staging、prod）。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/games/{gameId}/environments` | 创建环境 |
| GET | `/api/games/{gameId}/environments` | 获取环境列表 |
| GET | `/api/games/{gameId}/environments/{environmentName}` | 获取环境详情 |
| PUT | `/api/games/{gameId}/environments/{environmentName}` | 更新环境 |
| DELETE | `/api/games/{gameId}/environments/{environmentName}` | 删除环境 |

## 环境类型

| 类型 | 说明 |
|------|------|
| `DEVELOPMENT` | 开发环境 |
| `TESTING` | 测试环境 |
| `STAGING` | 预发布环境 |
| `PRODUCTION` | 生产环境 |
| `LOADTEST` | 压测环境 |

## 创建环境

```http
POST /api/games/{gameId}/environments
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "prod",
  "type": "PRODUCTION",
  "displayName": "Production Environment",
  "description": "Production environment for live players"
}
```

**响应:**
```json
{
  "id": "env_game_demo_prod",
  "gameId": "game_demo",
  "name": "prod",
  "type": "PRODUCTION",
  "displayName": "Production Environment",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 获取环境列表

```http
GET /api/games/{gameId}/environments
Authorization: Bearer {token}
```

**响应:**
```json
[
  {
    "id": "env_game_demo_dev",
    "name": "dev",
    "type": "DEVELOPMENT"
  },
  {
    "id": "env_game_demo_prod",
    "name": "prod",
    "type": "PRODUCTION"
  }
]
```

## 环境配置

环境级配置有启用状态、采样开关、采样率（经 Control 内部接口下发，网关对非 active 环境返回 503 `environment_unavailable`，采样按 device_id 确定性分桶，同设备事件同进同出）。

Key 级配置挂在 API Key 上，与 Key 所属环境绑定：PII 策略、props 白名单、限流（`rpm`/`ipRpm`）。数据保留由 ClickHouse TTL 与存储 profile 决定，不是环境字段。

## 推荐环境

| 环境 | 用途 |
|------|------|
| `dev` | 开发测试 |
| `staging` | 预发布验证 |
| `prod` | 正式上线 |

创建游戏时这三个环境会自动建好。
