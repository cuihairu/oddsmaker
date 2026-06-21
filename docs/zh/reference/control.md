# Oddsmaker 控制面 API（目标模型）

控制面负责管理一个公司内部的游戏、环境、API Key、Tracking Plan、PII 策略、风控策略、用户权限和审计日志。

不再设计 Organization/Tenant API。公司信息属于部署配置，不作为业务资源。

环境设计采用：

- `Game`：业务对象
- `Environment`：逻辑发布阶段
- `StorageProfile`：集群配置和容量规划

**数据库架构**：按游戏分库

```
oddsmaker_meta (元数据库)
├─ games
├─ environments
├─ api_keys
└─ audit_logs

game_demo_prod (游戏数据库)
├─ events
├─ resource_changes
└─ risk_events

game_demo_staging
└─ (同样的表结构)
```

Control API 需要根据 `gameId + environment` 路由到对应的数据库。

## 字段命名约定

- `gameId` / `environmentId`：控制面 API 使用的内部标识符
- `game_id` / `environment`：事件协议使用的逻辑名称（如 `prod`、`staging`）
- API Key 绑定到 `gameId + environmentId`（内部 ID）
- **事件上报**：`game_id` 和 `environment` 已在数据库/表层级体现，不需要作为字段
- **新增 `server_id`**：MMORPG 游戏必需，用于区分不同服务器/大区

## 核心资源

### Game

```http
POST /api/games
GET /api/games
GET /api/games/{gameId}
PUT /api/games/{gameId}
DELETE /api/games/{gameId}
POST /api/games/{gameId}/publish
POST /api/games/{gameId}/unpublish
```

示例：

```json
{
  "id": "game_demo",
  "name": "Demo Game",
  "genre": "rpg",
  "platforms": ["android", "ios"],
  "timezone": "Asia/Shanghai",
  "defaultCurrency": "USD"
}
```

### Environment

```http
POST /api/games/{gameId}/environments
GET /api/games/{gameId}/environments
GET /api/games/{gameId}/environments/{environmentName}
PUT /api/games/{gameId}/environments/{environmentName}
DELETE /api/games/{gameId}/environments/{environmentName}
```

推荐默认环境：`dev`、`staging`、`prod`。

环境配置包括：

- 数据保留时间。
- 采样策略。
- Schema 校验模式。
- PII 策略绑定。
- 风控策略绑定。
- 限流默认值。
- `storageProfile` 绑定。

### Storage Profile

```http
POST /api/storage-profiles
GET /api/storage-profiles
GET /api/storage-profiles/{profileId}
PUT /api/storage-profiles/{profileId}
DELETE /api/storage-profiles/{profileId}
```

Storage Profile 负责决定：

- Kafka cluster / topic namespace
- ClickHouse backend
- Redis backend
- Archive bucket
- isolation strategy: `shared | prod_isolated | dedicated`

### API Key

```http
POST /api/api-keys
GET /api/api-keys?gameId=&environmentId=
GET /api/api-keys/{keyId}
PUT /api/api-keys/{keyId}
DELETE /api/api-keys/{keyId}
```

兼容旧路由：

```http
POST /api/keys
GET /api/keys?gameId=&environmentId=
GET /api/keys/{keyId}
PUT /api/keys/{keyId}/policy
DELETE /api/keys/{keyId}
```

示例：

```json
{
  "gameId": "game_demo",
  "environmentId": "env_game_demo_prod",
  "name": "android-client",
  "keyType": "client",
  "rateLimit": {
    "rpm": 10000,
    "ipRpm": 300
  }
}
```

> **注意**：`environmentId` 是环境的内部标识符（如 `env_game_demo_prod`），而非逻辑名称（如 `prod`）。创建环境时会自动生成内部 ID。

Key 类型：

- `client`: 客户端写事件，只返回 public key。
- `server`: 服务端写事件和支付校验，返回 secret 一次。
- `admin`: 控制面或内部服务使用。

### Tracking Plan

```http
POST /api/games/{gameId}/environments/{environmentName}/tracking-plans
GET /api/games/{gameId}/environments/{environmentName}/tracking-plans/current
POST /api/tracking-plans/{planId}/publish
POST /api/tracking-plans/{planId}/rollback
```

说明：Tracking Plan 相关接口目前仍属于规划中的目标形态，当前仓库优先落地 Game / Environment / Storage Profile / API Key / Experiments。

### Experiments

```http
POST /api/experiments
GET /api/experiments?gameId=&environmentId=&environment=&status=
GET /api/experiments/{id}
PUT /api/experiments/{id}
DELETE /api/experiments/{id}
POST /api/experiments/{id}/publish
POST /api/experiments/{id}/pause
```

**字段说明**：
- `gameId`：游戏 ID（必填）
- `environmentId`：环境内部 ID（与 `environment` 二选一）
- `environment`：环境逻辑名称，如 `dev`/`staging`/`prod`（与 `environmentId` 二选一，兼容字段）
- `name`：实验名称
- `status`：实验状态：`draft`/`running`/`paused`
- `salt`：分流盐值
- `config`：实验配置（variants/targeting/metrics）

**公开配置端点**（无需认证）：
```http
GET /api/config/{gameId}/{environment}
```

返回该游戏环境下所有运行中的实验配置，供 SDK 使用。

Tracking Plan 管理：

- 事件名。
- 事件类型。
- 必填字段。
- 字段类型。
- 枚举值。
- cardinality 上限。
- 兼容性策略。

### PII Policy

```http
POST /api/pii-policies
GET /api/pii-policies/{policyId}
PUT /api/pii-policies/{policyId}
```

示例：

```json
{
  "name": "default-prod",
  "email": "mask",
  "phone": "drop",
  "ip": "coarse",
  "denyKeys": ["password", "credit_card"],
  "maskKeys": ["email", "mobile"]
}
```

### Risk Rule

```http
POST /api/risk-rules
GET /api/risk-rules?gameId=&environmentId=
GET /api/risk-rules/{ruleId}
PUT /api/risk-rules/{ruleId}
DELETE /api/risk-rules/{ruleId}
POST /api/risk-rules/{ruleId}/publish
POST /api/risk-rules/{ruleId}/disable
```

示例：

```json
{
  "gameId": "game_demo",
  "environmentId": "env_game_demo_prod",
  "ruleId": "payment_receipt_reuse",
  "riskType": "payment",
  "severity": "high",
  "ruleType": "threshold",
  "window": "24h",
  "condition": {
    "receiptHashDistinctUsersGt": 1
  },
  "action": "block"
}
```

### User 与 RoleBinding

```http
GET /api/users
POST /api/users
GET /api/users/{userId}
PUT /api/users/{userId}
POST /api/users/{userId}/role-bindings
DELETE /api/users/{userId}/role-bindings/{bindingId}
```

权限范围：

- `global`
- `game`
- `environment`

角色：

- `owner`
- `operator`
- `analyst`
- `developer`
- `risk_admin`
- `viewer`

### Audit Log

```http
GET /api/audit-logs?gameId=&environmentId=&actor=&action=&from=&to=
```

必须审计：

- API Key 创建、轮换、禁用。
- Tracking Plan 发布和回滚。
- PII 策略变更。
- 风控规则发布、禁用、阈值变更。
- 用户授权变更。
- 风控处置动作。

## 网关集成

Gateway 根据 `x-api-key` 从控制面拉取并缓存：

- `game_id`
- `environment`
- key 类型和状态
- 限流策略
- PII 策略
- Tracking Plan 版本
- 风控策略版本

缓存建议 30-60 秒，策略发布可通过事件或主动刷新缩短生效时间。
