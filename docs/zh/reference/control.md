# Oddsmaker 控制面 API（目标模型）

控制面负责管理一个公司内部的游戏、环境、API Key、Tracking Plan、PII 策略、风控策略、用户权限和审计日志。

不再设计 Organization/Tenant API。公司信息属于部署配置，不作为业务资源。

环境设计采用：

- `Game`：业务对象
- `Environment`：逻辑发布阶段
- `StorageProfile`：集群配置和容量规划

**数据库架构**（当前实现）：PostgreSQL 元数据库 + 共享 ClickHouse 表，事件与聚合表按 `(game_id, environment)` 分区。「按游戏分库」是目标形态，由存储 profile 的 `isolationStrategy`（`shared` / `prod_isolated` / `dedicated`）表达，物理分库尚未接线。

```
PostgreSQL（元数据）
├─ games / environments / api_keys
├─ users / roles / user_role_assignments
├─ tracking_plans / risk_rules / risk_features / block_lists / audit_logs
└─ storage_profiles ...

ClickHouse（共享表，按 game_id + environment 分区）
├─ events / resource_changes
├─ risk_events / risk_scores / risk_actions
└─ ...
```

## 字段命名约定

- `gameId` / `environmentId`：控制面 API 使用的内部标识符
- `game_id` / `environment`：事件协议使用的逻辑名称（如 `prod`、`staging`）
- API Key 绑定到 `gameId + environmentId`（内部 ID）
- **事件上报**：`game_id` 和 `environment` 是事件必填字段，也是 ClickHouse 的分区键（不是"表层级就不需要存"）
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
  "genre": "RPG",
  "platforms": ["MOBILE", "PC"],
  "defaultTimezone": "Asia/Shanghai",
  "defaultCurrency": "USD"
}
```

`genre` 枚举大写（RPG/STRATEGY/ACTION/.../RACING/OTHER 共 13 类）；`platforms` 取值 `WEB/MOBILE/PC/CONSOLE/VR/AR`（没有 android/ios）；时区字段名是 `defaultTimezone`。创建成功后自动建 `dev` / `staging` / `prod` 三个环境。

### Environment

```http
POST /api/games/{gameId}/environments
GET /api/games/{gameId}/environments
GET /api/games/{gameId}/environments/{environmentName}
PUT /api/games/{gameId}/environments/{environmentName}
DELETE /api/games/{gameId}/environments/{environmentName}
```

推荐默认环境：`dev`、`staging`、`prod`。

环境级配置：启用状态、采样开关与采样率（网关对非 active 环境回 503）。数据保留时间不是环境字段——由 ClickHouse TTL 与存储 profile 决定。

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

> `environmentId` 是环境的内部标识符（如 `env_game_demo_prod`），不是逻辑名称（如 `prod`）；创建环境时自动生成。

Key 类型：

- `client`: 客户端写事件，只返回 public key。
- `server`: 服务端写事件和支付校验，返回 secret 一次。
- `admin`: 控制面或内部服务使用。

### Tracking Plan

```http
POST /api/games/{gameId}/tracking-plans
GET /api/games/{gameId}/tracking-plans
GET /api/games/{gameId}/tracking-plans/{trackingPlanId}
GET /api/games/{gameId}/tracking-plans/active
GET /api/games/{gameId}/tracking-plans/environment/{environmentId}
PUT /api/games/{gameId}/tracking-plans/{trackingPlanId}
POST /api/games/{gameId}/tracking-plans/{trackingPlanId}/activate
POST /api/games/{gameId}/tracking-plans/{trackingPlanId}/deactivate
DELETE /api/games/{gameId}/tracking-plans/{trackingPlanId}
```

事件与属性字典挂在计划下：`.../{trackingPlanId}/events[/{eventDefinitionId}]` 与 `.../events/{eventDefinitionId}/properties[/{propertyDefinitionId}]` 的标准 CRUD。没有 `publish` / `rollback`——启用状态用 `activate` / `deactivate` 切换。

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

### PII 策略（Key 级）

没有独立的 `/api/pii-policies` 资源。PII 策略挂在 API Key 上：

```http
PUT /api/keys/{keyId}/policy
```

```json
{
  "piiEmail": "mask",
  "piiPhone": "drop",
  "piiIp": "coarse",
  "denyKeys": ["password", "credit_card"],
  "maskKeys": ["email", "mobile"],
  "propsAllowlist": ["level", "vip"],
  "rpm": 600,
  "ipRpm": 300
}
```

Gateway 按 key 缓存执行 deny / mask / coarse / drop。

### Risk Rule

```http
POST /api/risk-rules
GET /api/risk-rules?gameId=&status=&page=&size=
GET /api/risk-rules/{ruleId}
PUT /api/risk-rules/{ruleId}
DELETE /api/risk-rules/{ruleId}
POST /api/risk-rules/{ruleId}/enable
POST /api/risk-rules/{ruleId}/disable
```

没有 `publish` 端点——启停即 `enable` / `disable`。示例（字段是 `RiskRuleEntity` 的形状，`ruleConditions` 是 JSON 字符串）：

```json
{
  "gameId": "game_demo",
  "name": "收据复用检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "ruleConditions": "{\"receiptHashDistinctUsersGt\": 1}",
  "actionType": "BLOCK",
  "blockDuration": 1440
}
```

状态：`DRAFT` / `ACTIVE` / `PAUSED` / `ARCHIVED` / `DEPRECATED`。

`ruleType=FEATURE` 的特征规则（B5）：`ruleConditions` 形状为
`{"features":[{"scope":"SUBJECT","feature":"gold_gain_1h","op":">","threshold":500000}]}`，
`scope` 取 `SUBJECT`（玩家/设备主体）或 `IP`，条件间全 AND；特征行未产出按不满足计（无值不判真）。
特征值来自 risk-job 特征作业按滑动窗口写入的 `risk_features`（首 6 个：gold_gain_1h/24h、device_count、
account_count_per_ip、win_rate、event_count_10m），risk-job 每 60 秒拉取规则时解析 `features` 数组。

### User 与 RoleAssignment

```http
GET /api/users
POST /api/users
GET /api/users/{userId}
PUT /api/users/{userId}
GET /api/users/{userId}/role-assignments
POST /api/users/{userId}/role-assignments
DELETE /api/users/{userId}/role-assignments?roleId=&gameId=&environment=
```

授权请求体：

```json
{ "roleId": "role_analyst", "gameId": "game_demo", "environment": null }
```

`gameId` / `environment` 均缺省即全局范围。权限范围：`global` / `game` / `environment`。

角色（V0.2.3 种子 8 个，`roles` 表是存储位置，没有 `owner` / `risk_admin`）：

- `operator`（GLOBAL，全权限）
- `game_admin`（GAME，继承 analyst/marketing/finance/developer/viewer/qa）
- `analyst`、`marketing`、`finance`、`developer`（GAME）
- `viewer`、`qa`（ENVIRONMENT）

### Audit Log

```http
GET /api/audit-logs?gameId=&action=&from=&to=
GET /api/audit-logs/user/{userId}
GET /api/audit-logs/resource/{resourceType}/{resourceId}
GET /api/audit-logs/action/{action}
GET /api/audit-logs/time-range?start=&end=
GET /api/audit-logs/statistics
GET /api/audit-logs/{logId}
```

必须审计：

- API Key 创建、轮换、禁用。
- Tracking Plan 发布和回滚。
- PII 策略变更。
- 风控规则发布、禁用、阈值变更。
- 用户授权变更。
- 风控处置动作。

## 网关集成

Gateway 当前从控制面拉取并缓存（`/internal/api-keys` 等 internal 端点）：

- key 上下文：`game_id`、`environment`、档位（client/server/admin）与启用状态
- PII / props 策略与限流（`rpm` / `ipRpm`）

风控侧：规则由 Flink risk-job 每 60 秒拉取一次；黑名单走批量校验。risk-job 命中事件经 Kafka 回流后由 RiskEventConsumer 判定落库（`risk_cases` 判定状态机 OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED，Decision 先于 Action，非法流转拒绝执行并归档 decision_rejected；BLOCK 级动作要求输入事件 trust_level=HIGH，非 HIGH fail-closed 降级 REVIEW）。Tracking Plan 尚未接入 Gateway 的实时校验链（schema 校验目前用内置 JSON Schema），缓存为进程内短 TTL。
