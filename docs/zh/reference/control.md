# Oddsmaker 控制面 API（目标模型）

> 🌐 Language: **中文** · English → [control.en.md](../../reference/control)

控制面负责管理一个公司内部的游戏、环境、API Key、Tracking Plan、PII 策略、风控策略、用户权限和审计日志。

不再设计 Organization/Tenant API。公司信息属于部署配置，不作为业务资源。

环境设计采用：

- `Game`：业务对象
- `Environment`：逻辑发布阶段
- `StorageProfile`：集群配置和容量规划

**数据库架构**（当前实现）：PostgreSQL 元数据库 + 共享 ClickHouse 表，事件与聚合表按 `(game_id, environment, 月)` 分区。「按游戏分库」是目标形态，由存储 profile 的 `isolationStrategy`（`shared` / `prod_isolated` / `dedicated`）表达，物理分库尚未接线。

```
PostgreSQL（元数据）
├─ games / environments / api_keys
├─ users / roles / user_role_assignments
├─ tracking_plans / risk_rules / risk_features / block_lists / audit_logs
└─ storage_profiles ...

ClickHouse（共享表，按 game_id + environment + 月 分区）
├─ events
├─ risk_events / risk_scores / risk_actions
└─ ...
```

## 字段命名约定

- `gameId` / `environmentId`：控制面 API 使用的内部标识符
- `game_id` / `environment`：事件协议使用的逻辑名称（如 `prod`、`staging`）
- API Key 绑定到 `gameId + environmentId`（内部 ID）
- **事件上报**：`game_id` 和 `environment` 是事件必填字段，也是 ClickHouse 的分区键（不是"表层级就不需要存"）
- **新增 `server_id`**：可选字段，MMORPG 建议携带，用于区分不同服务器/大区

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

环境级配置：启用状态、采样开关与采样率（网关对非 active 环境回 503）。数据保留由环境/游戏的 `dataRetentionDays` 驱动 ClickHouse 表 TTL（`RetentionEnforcementService`，优先级 环境 ?? 游戏 ?? 默认），存储 profile 不参与保留期。

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
  "environmentId": "game_demo_prod",
  "name": "android-client",
  "keyType": "client",
  "rateLimit": {
    "rpm": 10000,
    "ipRpm": 300
  }
}
```

> `environmentId` 是环境的内部标识符（如 `game_demo_prod`），不是逻辑名称（如 `prod`）；创建环境时自动生成。

Key 类型：

- `client`: 客户端写事件，不参与 HMAC 签名（secret 不用于服务端调用）；建档响应仍返回 secret 字段，调用方勿下发到客户端。
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

### Event Schema（B7 一等资源化）

与追踪计划同一底层（同库同实体），以 EventSchema 语义暴露的版本化资源 API（B7）：

```http
POST /api/games/{gameId}/schemas
GET /api/games/{gameId}/schemas
GET /api/games/{gameId}/schemas/active
GET /api/games/{gameId}/schemas/{schemaId}
PUT /api/games/{gameId}/schemas/{schemaId}
POST /api/games/{gameId}/schemas/{schemaId}/publish?userId=
GET /api/games/{gameId}/schemas/{schemaId}/compatibility
POST /api/games/{gameId}/schemas/{schemaId}/deactivate
DELETE /api/games/{gameId}/schemas/{schemaId}
```

事件与属性定义子资源与追踪计划完全同形（`.../{schemaId}/events[...]`）。与 `activate` 的差别在 `publish` 带**兼容门**：Schema 的 `compatibility` 非 NONE 时，发布前对同 game + 同 environment 的最近 ACTIVE 基线版本做事件集 diff——BACKWARD 要求 `removed` 为空（旧生产者事件不被新版本拒收）、FORWARD 要求 `added` 为空（旧消费方不识别新增）、FULL 两者、NONE 不检查；同名事件的类型/必填位/重要性变化记入 `changedEvents` 仅作信息项不判兼容。检查为只读端点 `GET .../compatibility`，返回 `{schemaId, mode, baselineId, compatible, addedEvents, removedEvents, changedEvents}`；不兼容的发布直接 400，Schema 保持 DRAFT。

B7 起新增字段：`compatibility`（默认 NONE）、`piiPolicy`（事件级 PII 策略 JSON）、`retentionDays`、`samplingRate`、`ownerId`；`rejectUnknownEvents` 默认值收口为 `true`——ACTIVE Schema 的事件名清单随 internal feed（`/internal/api-keys/{key}`）下发网关（环境绑定版优先、回退全局版），网关对未定义事件按 `unknown_event` 拒收，dev 环境豁免。web 控制台入口：`/schemas`（事件 Schema 页）。

**PII 优先级链（环境级 Schema > ApiKey > 网关默认）**：ACTIVE Schema 的 `piiPolicy` 随同一 internal feed 下发网关（环境绑定版优先、回退全局版，无 Schema 不下发），网关在 ApiKey 级策略之上按字段收敛——`email`/`phone`（`allow|mask|drop`）与 `ip`（`allow|coarse|drop`）取更高一层的合法值，`denyKeys`/`maskKeys` 名单与 ApiKey 级取并集（任一层收紧即生效）；JSON 非法或单字段值非法时该字段回落 ApiKey 级/网关默认（与 Schema 事件面 fail-open 同口径）。`piiPolicy` 契约：`{"email":"allow|mask|drop","phone":"allow|mask|drop","ip":"allow|coarse|drop","denyKeys":["..."],"maskKeys":["..."]}`，字段均可缺省。控制台 `/schemas` 页提供策略列展示与 DRAFT 版编辑入口（仅 DRAFT 可改，ACTIVE 走新版本发布流程）。

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
`{"features":[{"scope":"SUBJECT","feature":"gold_gain_1h","op":">","value":500000}]}`，
`scope` 取 `SUBJECT`（玩家/设备主体）或 `IP`，条件间全 AND；特征行未产出按不满足计（无值不判真）。
写路径结构校验：`feature` 非空、`op ∈ {>, >=, <, <=, ==}`、`value` 须为数值、`scope` 须为 `SUBJECT|IP`，
任一违反整列表拒绝（4xx，文案以 `ruleConditions` 或 `features` 开头）；特征名须落在 risk-job 计算面的
**已知特征清单**内（`service/RiskFeatures` ↔ risk-job `RiskFeatures`，两侧测试钉同一份名单）——
未知名规则在 `risk_features` 表永远查不到、生产侧永远不命中，写路径显式拒绝
（`FEATURE rule references unknown feature(s) …`，已代决 2026-10-16）。
特征值来自 risk-job 特征作业按滑动窗口写入的 `risk_features`（首 6 个：gold_gain_1h/24h、device_count、
account_count_per_ip、win_rate、event_count_10m），risk-job 每 60 秒拉取规则时解析 `features` 数组；
读路径（replay/RuleFetcher）维持容忍：坏形态或未知特征名整条跳过，存量行不改写。

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
- `game_admin`（GAME，继承 operator）
- `analyst`、`marketing`、`finance`、`developer`（GAME，继承 game_admin）
- `viewer`（ENVIRONMENT，继承 analyst）、`qa`（ENVIRONMENT，继承 developer）

### Audit Log

```http
GET    /api/audit-logs?page=&size=&sortBy=&sortDir=          # audit:read
GET    /api/audit-logs/user/{userId}                         # audit:read
GET    /api/audit-logs/resource/{resourceType}/{resourceId}  # audit:read
GET    /api/audit-logs/action/{action}                       # audit:read
GET    /api/audit-logs/status/{status}                       # audit:read
GET    /api/audit-logs/time-range?start=&end=                # audit:read
GET    /api/audit-logs/game/{gameId}                         # audit:read
GET    /api/audit-logs/search?query=                         # audit:read
GET    /api/audit-logs/{logId}                               # audit:read
GET    /api/audit-logs/failed                                # audit:sensitive
GET    /api/audit-logs/auth                                  # audit:sensitive
GET    /api/audit-logs/sensitive                             # audit:sensitive
GET    /api/audit-logs/statistics?days=                      # audit:sensitive
DELETE /api/audit-logs/cleanup?daysToKeep=                   # audit:manage
```

响应为审计实体直出（无 DTO 隔离层），除落库列外含**读时派生**的 `eventType`——不落新列，判定 = `resourceType` × `action` × `details.action`（`AuditLogService` 把 metadata 的 `action` 等键写进 `details`，`gameId` / `environment` 提为独立列）：

| `eventType` | 判定 |
|-------------|------|
| `RISK_POLICY_CHANGED` | `risk_rule` × 变更类动作（create/update/delete/enable/disable/activate/deactivate） |
| `API_KEY_ROTATED` | `api_key` × update × `details.action=auto_rotate`（自动轮换执行端写入） |
| `DATA_EXPORTED` | 动作 `EXPORT`（与资源无关） |
| `PERMISSION_CHANGED` | grant_role / revoke_role / grant_permission / revoke_permission，或 `user` × 裸 `GRANT` |
| `EXPERIMENT_PUBLISHED` | `experiment` × update × `details.action=publish` |
| `OTHER` | 其余（含 `details` 非法 JSON、缺 `action` 键、`action` 非字符串） |

必须审计（以代码实际写库为准）：

- API Key 创建、禁用；清单中「API Key 轮换」对应的轮换端点不存在（当前仅 create / update-policy / delete）。
- Tracking Plan 删除；发布 / 回滚（activate / deactivate）的审计写库未实现，当前仅删除有审计。
- PII 策略变更。
- 风控规则发布、禁用、阈值变更。
- 用户授权变更。
- 风控处置动作。

## 网关集成

Gateway 当前从控制面拉取并缓存（`/internal/api-keys` 等 internal 端点）：

- key 上下文：`game_id`、`environment`、档位（client/server/admin）与启用状态
- PII / props 策略与限流（`rpm` / `ipRpm`）

风控侧：规则由 Flink risk-job 每 60 秒拉取一次；黑名单走批量校验。risk-job 命中事件经 Kafka 回流后由 RiskEventConsumer 判定落库（`risk_cases` 判定状态机 OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED，Decision 先于 Action，非法流转拒绝执行并归档 decision_rejected；BLOCK 级动作要求输入事件 trust_level=HIGH，非 HIGH fail-closed 降级 REVIEW）。Tracking Plan 尚未接入 Gateway 的实时校验链（schema 校验目前用内置 JSON Schema），缓存为进程内短 TTL。
