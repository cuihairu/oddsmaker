# Experiments API

A/B 测试分析 API，用于分析实验数据和转化率。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/experiments` | 创建实验 |
| GET | `/api/experiments` | 获取实验列表 |
| GET | `/api/experiments/{id}` | 获取实验详情 |
| PUT | `/api/experiments/{id}` | 更新实验 |
| DELETE | `/api/experiments/{id}` | 删除实验 |
| POST | `/api/experiments/{id}/publish` | 发布实验 |
| POST | `/api/experiments/{id}/pause` | 暂停实验 |
| GET | `/api/config/{gameId}/{environment}` | 获取运行中的实验配置 |

## 实验状态

| 状态 | 说明 |
|------|------|
| `draft` | 草稿 |
| `running` | 运行中 |
| `paused` | 已暂停 |

## 创建实验

```http
POST /api/experiments
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "environment": "prod",
  "name": "Homepage Banner Test",
  "salt": "unique_salt",
  "config": {
    "variants": [
      {"name": "A", "weight": 50},
      {"name": "B", "weight": 50}
    ],
    "targeting": {
      "platform": ["android", "ios"],
      "countries": ["US", "CN"]
    },
    "metrics": {
      "primary": "conversion",
      "secondary": ["revenue", "retention"]
    }
  }
}
```

**响应:**
```json
{
  "id": "exp_abc123",
  "gameId": "game_abc123",
  "environment": "prod",
  "name": "Homepage Banner Test",
  "status": "draft",
  "salt": "unique_salt",
  "config": {...},
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 发布实验

```http
POST /api/experiments/{id}/publish
Authorization: Bearer {token}
```

## 获取运行中的实验配置

```http
GET /api/config/{gameId}/{environment}
```

**响应（公开端点，无需认证）:**
```json
[
  {
    "id": "exp_abc123",
    "salt": "unique_salt",
    "config": {
      "variants": [
        {"name": "A", "weight": 50},
        {"name": "B", "weight": 50}
      ]
    }
  }
]
```

## SDK 使用

```javascript
// 获取实验配置
const experiments = await fetch('/api/config/game_abc123/prod');

// 分流
function assignVariant(experiment, userId) {
  const hash = murmur3(experiment.id + ':' + experiment.salt + ':' + userId);
  const bucket = hash % 100;
  let acc = 0;
  for (const variant of experiment.config.variants) {
    acc += variant.weight;
    if (bucket < acc) return variant.name;
  }
  return experiment.config.variants[0].name;
}

// 上报曝光
oddsmaker.track('experiment_exposure', {
  exp: 'exp_abc123',
  variant: 'B'
});
```

## 分析指标

| 指标 | 说明 |
|------|------|
| 转化率 | 各变体的转化率对比 |
| 统计显著性 | p-value < 0.05 |
| SRM | 样本比例不匹配检测 |
| Uplift | B vs A 的提升度 |
| 置信区间 | 95% 置信区间 |
