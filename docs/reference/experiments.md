# Experiments API

> 🌐 Language: **English** · 中文 → [experiments.zh.md](../zh/reference/experiments)

A/B test analytics API, used to analyze experiment data and conversion rates.

## Endpoint List

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/experiments` | Create experiment |
| GET | `/api/experiments` | Get experiment list |
| GET | `/api/experiments/{id}` | Get experiment details |
| PUT | `/api/experiments/{id}` | Update experiment |
| DELETE | `/api/experiments/{id}` | Delete experiment |
| POST | `/api/experiments/{id}/publish` | Publish experiment |
| POST | `/api/experiments/{id}/pause` | Pause experiment |
| GET | `/api/experiments/{id}/assign?userId=&deviceId=` | Server-side deterministic split |
| POST | `/api/experiments/{id}/metrics` | Receive metric snapshots |
| POST | `/api/experiments/{id}/metrics/aggregate` | Aggregate metrics by window |
| GET | `/api/experiments/{id}/results` | Experiment results (lift, confidence interval, p-value, SRM) |
| GET | `/api/config/{gameId}/{environment}` | Get running experiment configuration |

## Experiment Status

| Status | Description |
|------|------|
| `DRAFT` | Draft |
| `LIVE` | Running |
| `PAUSED` | Paused |
| `ENDED` | Ended |

## Create Experiment

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

**Response:**
```json
{
  "id": "exp_abc123",
  "gameId": "game_abc123",
  "environment": "prod",
  "name": "Homepage Banner Test",
  "status": "DRAFT",
  "salt": "unique_salt",
  "config": {...},
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Publish Experiment

```http
POST /api/experiments/{id}/publish
Authorization: Bearer {token}
```

## Get Running Experiment Configuration

```http
GET /api/config/{gameId}/{environment}
```

**Response (public endpoint, no authentication required):**
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

## SDK Usage

```javascript
// 获取实验配置
const experiments = await fetch('/api/config/game_abc123/prod');

// 分流（hash32 = FNV-1a 32 位变体，四端 SDK 与服务端 ExperimentSplitter 同款算法；
// 锚定向量：hash32("a")=0xe40c292c、hash32("foobar")=0xbf9cf968）
function assignVariant(experiment, userId) {
  const totalWeight = experiment.config.variants.reduce((sum, v) => sum + v.weight, 0);
  const hash = hash32(experiment.id + ':' + experiment.salt + ':' + userId) >>> 0;
  const bucket = hash % totalWeight;
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

## Analytics Metrics

| Metric | Description |
|------|------|
| Conversion rate | Comparison of conversion rates across variants |
| Statistical significance | p-value < 0.05 |
| SRM | Sample ratio mismatch detection |
| Uplift | Improvement of B vs A |
| Confidence interval | 95% confidence interval |
