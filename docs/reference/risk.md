# Risk API

风控管理 API，用于风险规则、案例管理和实时风险评估。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/risk-rules` | 创建风控规则 |
| GET | `/api/risk-rules` | 获取规则列表 |
| GET | `/api/risk-rules/{id}` | 获取规则详情 |
| PUT | `/api/risk-rules/{id}` | 更新规则 |
| DELETE | `/api/risk-rules/{id}` | 删除规则 |
| GET | `/api/risk-cases` | 获取风控案例 |
| GET | `/api/risk-cases/{id}` | 获取案例详情 |
| POST | `/api/risk-cases/{id}/unblock` | 解除封禁 |
| POST | `/api/risk-cases/{id}/review` | 完成审核 |

## 风险类型

| 类型 | 说明 |
|------|------|
| `payment` | 支付欺诈 |
| `behavior` | 行为异常 |
| `account` | 账号风险 |
| `cheating` | 作弊行为 |

## 风险等级

| 等级 | 说明 | 处置 |
|------|------|------|
| `LOW` | 低风险 | 记录 |
| `MEDIUM` | 中风险 | 告警 |
| `HIGH` | 高风险 | 审核 |
| `CRITICAL` | 严重风险 | 封禁 |

## 创建风控规则

```http
POST /api/risk-rules
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "riskType": "payment",
  "severity": "HIGH",
  "ruleType": "threshold",
  "condition": {
    "event_type": "payment",
    "amount": ">1000",
    "frequency": ">10/hour"
  },
  "action": "BLOCK",
  "blockDuration": 1440
}
```

**响应:**
```json
{
  "id": "rule_abc123",
  "gameId": "game_abc123",
  "name": "高频支付检测",
  "riskType": "payment",
  "severity": "HIGH",
  "status": "ACTIVE",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 风控案例

当规则被触发时，系统自动创建风控案例：

```json
{
  "id": "rc_abc123",
  "ruleId": "rule_abc123",
  "gameId": "game_abc123",
  "targetType": "user",
  "targetId": "user_123",
  "riskLevel": "HIGH",
  "riskScore": 85,
  "actionTaken": "BLOCK",
  "executionStatus": "EXECUTED",
  "blockedAt": "2024-01-01T00:00:00Z"
}
```

## 解除封禁

```http
POST /api/risk-cases/{id}/unblock
Content-Type: application/json
Authorization: Bearer {token}

{
  "reason": "误报，用户行为正常"
}
```

## 实时风险评估

风险评估由 Flink 作业实时执行：

1. **事件采集** - 接收游戏事件
2. **规则匹配** - 匹配风控规则
3. **风险评分** - 计算风险分数
4. **执行动作** - 封禁/告警/审核

## 风控大屏

访问 `/api/risk-dashboard` 获取风控统计数据：

- 风险趋势
- 规则命中率
- 封禁统计
- 高风险目标
