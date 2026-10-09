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

风控案例（RiskCase）没有独立的创建/删除 API——案例由风控链路自动创建。查询与处置入口：

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/games/{gameId}/risk-cases` | 案例列表（`status`/`riskLevel` 过滤；`ruleId`/`disposition` 为策略实验室样本下钻过滤——在最近 2000 条案例窗口内内存过滤后截断，`limit` 默认 100、上限 500，最新在前） |
| GET | `/api/games/{gameId}/risk-cases/{caseId}` | 案例详情（证据/上下文 JSON 解析为对象，解析失败回传原文） |
| POST | `/api/games/{gameId}/risk-cases/{caseId}/unblock` | 人工解除封禁（误杀处置；须 `BLOCK` 已执行且未解除，联动释放由本案例创建的黑名单记录） |
| GET | `/api/games/{gameId}/risk-lab/rule-stats` | 策略实验室规则复盘聚合：每规则一行（案例数、误杀/确认违规/证据不足/未复盘分桶、误杀率分母=已处置、平均复盘时长、最近案例），零案例规则也出行；聚合在最近 5000 条案例内进行 |
| POST | `/api/games/{gameId}/risk-lab/replay` | 策略实验室试算回放（dry-run）：body `{samples:[{eventId?,amount?,features?}],ruleIds?}`，1~500 条即传即算不落库；THRESHOLD 严格大于阈值、FEATURE 条件全 AND（缺值 fail-closed）逐事件试算，语义对齐线上纯函数；FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD/PATTERN 标 `needsStreaming` 跳过、ANOMALY/MACHINE_LEARNING 标 `notCovered`、条件非法标 `invalid`；同 ruleType 线上只生效 riskScore 最高者（`effectiveHits` 收敛，`matchedRuleIds` 保留逐规则原始命中） |
| GET | `/api/games/{gameId}/risk-lab/sample-sets` | 策略实验室样本集列表（仅元信息：name/sampleCount/createdAt 等，不含样本内容） |
| POST | `/api/games/{gameId}/risk-lab/sample-sets` | 新建样本集：body `{name,description?,samples}`，name 同游戏唯一，samples 1~500 条走试算回放同款校验（留档即不可改，删除重建），create/delete 审计；鉴权 `risk:manage` |
| GET | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | 样本集详情（含解析后的 `samples`，供载入回放） |
| DELETE | `/api/games/{gameId}/risk-lab/sample-sets/{id}` | 删除样本集（审计）；需替换时删除重建；鉴权 `risk:manage` |
| GET | `/api/games/{gameId}/risk-scores?subjectType=&subjectId=` | 主体累计风险分最新快照（B6 边界闭合）：读 CH `risk_scores`（`ORDER BY updated_at DESC LIMIT 1`，ReplacingMergeTree 合并前后都正确），`reasons` 解析为 `[{ruleId,contribution}]`；未落过分返回 `found:false`；CH 未配置抛 `CH_UNAVAILABLE`；鉴权 `game:read` |
| GET | `/api/risk-dashboard/recent-cases/{gameId}` | 最近案例列表 |
| GET/POST | `/api/review-queue/...` | 人工审核流（分配、认领、完成、升级） |
| POST | `/api/block-lists/{blockId}/unblock` | 按封禁记录解除（案例触发的封禁也在黑名单表里） |

案例回看控制台页为 `/risk-cases`（列表过滤 + 详情弹层 + 误杀解除），列表/详情鉴权 `game:read`，解除封禁鉴权 `risk:manage`。策略实验室控制台页为 `/risk-lab`（规则复盘聚合 + 点规则行下钻案例样本，处置 chips 过滤；试算回放面板支持样本 JSON 批量 dry-run 打分，「样本集」区命名留档当前样本、载入回填后改规则重试算对比），页面鉴权 `game:read`，样本集写入/删除 `risk:manage`。

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
  "status": "BLOCK",
  "actionTaken": "BLOCK",
  "executionStatus": "EXECUTED",
  "executedAt": "2024-01-01T00:00:00Z",
  "disposition": "confirmed_fraud"
}
```

`targetType` 取值 `user_id` / `device_id` / `player_id` / `ip`；`status` 为判定状态（`OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED` 分层前向，OPEN 不直达 RESOLVED）；`executionStatus` 取值 `PENDING` / `EXECUTED` / `FAILED` / `CANCELLED` / `APPEALED`。

## 解除封禁

解封有两条路径：按封禁记录 ID 走黑名单接口；按案例走 `POST /api/games/{gameId}/risk-cases/{caseId}/unblock`（案例侧会联动释放由该案例创建、仍活跃的黑名单记录，并记录案例维度审计日志）。按封禁记录 ID：

```http
POST /api/block-lists/{blockId}/unblock
Content-Type: application/json
Authorization: Bearer {token}

{
  "reason": "误报，用户行为正常"
}
```

## 实时风险评估

风险评估由 Flink risk-job 实时执行：消费事件流，按 Control 下发的规则（60 秒拉取一次）匹配，命中后写 `risk_events` / `risk_scores`（主体累计分，评估侧单写）到 ClickHouse，并把事件发回 Control（Kafka `oddsmaker.risk_events`）；Control 的 RiskEventConsumer 按判定状态机落 `risk_cases`（Decision 先于 Action：非法流转的处置动作被拒绝），并联动黑名单、审核队列与 Webhook，处置归档 `risk_actions`。BLOCK 级动作要求输入事件 `trust_level=HIGH`（risk-job 与 Control 两侧同语义双门槛，非 HIGH fail-closed 降级 REVIEW）。

## 风控大屏

`/api/risk-dashboard` 下按 `gameId` 提供趋势、规则命中、封禁统计、高风险目标、最近案例、审核队列统计等只读聚合，另有 `/api/risk-metrics` 输出按严重度、动作类型的分布。
