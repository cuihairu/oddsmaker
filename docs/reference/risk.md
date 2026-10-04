# Risk API

风控管理 API，用于风险规则、案例管理和实时风险评估。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/risk-rules` | 创建风控规则 |
| GET | `/api/risk-rules` | 获取规则列表（分页，支持 gameId/status 等条件） |
| GET | `/api/risk-rules/{id}` | 获取规则详情 |
| PUT | `/api/risk-rules/{id}` | 更新规则 |
| DELETE | `/api/risk-rules/{id}` | 删除规则 |
| POST | `/api/risk-rules/{id}/enable` | 启用规则 |
| POST | `/api/risk-rules/{id}/disable` | 停用规则 |

风控案例（RiskCase）没有独立的 CRUD API。案例由风控链路自动创建，查询与处置走这三个入口：

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/risk-dashboard/recent-cases/{gameId}` | 最近案例列表 |
| GET/POST | `/api/review-queue/...` | 人工审核流（分配、认领、完成、升级） |
| POST | `/api/block-lists/{blockId}/unblock` | 解除封禁（案例触发的封禁也在黑名单表里） |

## 规则类别（category）

| 类别 | 说明 |
|------|------|
| `PAYMENT` | 支付风险 |
| `BEHAVIOR` | 行为异常 |
| `ACCOUNT` | 账号风险 |
| `DEVICE` | 设备聚集 |
| `NETWORK` | 网络/IP 异常 |
| `AUTOMATION` | 脚本与外挂 |
| `COLLUSION` | 团伙作弊 |
| `ECONOMY` | 经济系统异常 |

规则类型（ruleType）7 类：`THRESHOLD`、`FREQUENCY`、`PATTERN`、`VELOCITY`、`RATIO`、`ANOMALY`、`MACHINE_LEARNING`。

## 风险等级

| 等级 | 说明 |
|------|------|
| `LOW` | 低风险 |
| `MEDIUM` | 中风险 |
| `HIGH` | 高风险 |
| `CRITICAL` | 严重风险 |

等级不直接决定处置动作；处置由规则的 `actionType`、`riskScore`、`triggerThreshold` 等字段决定。

## 创建风控规则

请求体是 `RiskRuleEntity` 的字段，注意 `ruleConditions` 是 JSON 字符串（不是嵌套对象），`category`/`riskLevel`/`actionType` 是枚举大写：

```http
POST /api/risk-rules
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "ruleConditions": "{\"event_type\": \"purchase\", \"amount\": \">1000\", \"frequency\": \">10/hour\"}",
  "actionType": "BLOCK",
  "blockDuration": 1440
}
```

**响应:**
```json
{
  "id": "rule_abc123",
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "category": "PAYMENT",
  "riskLevel": "HIGH",
  "ruleType": "THRESHOLD",
  "actionType": "BLOCK",
  "status": "ACTIVE",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 风控案例

案例由风控链路写入 `risk_cases`，关键字段：

```json
{
  "id": "rc_abc123",
  "caseNumber": "CASE_20240101_0001",
  "riskRuleId": "rule_abc123",
  "gameId": "game_abc123",
  "targetType": "user_id",
  "targetId": "user_123",
  "riskLevel": "HIGH",
  "riskScore": 85,
  "actionTaken": "BLOCK",
  "executionStatus": "EXECUTED",
  "executedAt": "2024-01-01T00:00:00Z",
  "disposition": "confirmed_fraud"
}
```

`targetType` 取值 `user_id` / `device_id` / `player_id` / `ip`；`executionStatus` 取值 `PENDING` / `EXECUTED` / `FAILED` / `CANCELLED` / `APPEALED`。

## 解除封禁

解封走黑名单接口，按封禁记录 ID：

```http
POST /api/block-lists/{blockId}/unblock
Content-Type: application/json
Authorization: Bearer {token}

{
  "reason": "误报，用户行为正常"
}
```

## 实时风险评估

风险评估由 Flink risk-job 实时执行：消费事件流，按 Control 下发的规则（60 秒拉取一次）匹配，命中后写 `risk_events` / `risk_scores` / `risk_cases` 并联动黑名单、审核队列与 Webhook。

## 风控大屏

`/api/risk-dashboard` 下按 `gameId` 提供趋势、规则命中、封禁统计、高风险目标、最近案例、审核队列统计等只读聚合，另有 `/api/risk-metrics` 输出按严重度、动作类型的分布。
