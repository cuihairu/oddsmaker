# ML Models API

机器学习模型管理 API，用于模型训练、部署和预测。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/ml-models` | 创建模型 |
| GET | `/api/ml-models` | 获取模型列表 |
| GET | `/api/ml-models/{id}` | 获取模型详情 |
| PUT | `/api/ml-models/{id}` | 更新模型 |
| DELETE | `/api/ml-models/{id}` | 删除模型 |
| POST | `/api/ml-models/{id}/train` | 启动训练 |
| POST | `/api/ml-models/{id}/deploy` | 部署模型 |
| POST | `/api/ml-models/{id}/predict` | 预测请求 |

## 模型类型

| 类型 | 说明 | 应用场景 |
|------|------|----------|
| `CLASSIFICATION` | 分类模型 | 流失预测、作弊检测 |
| `REGRESSION` | 回归模型 | LTV 预测、风险评分 |
| `CLUSTERING` | 聚类模型 | 玩家分群 |
| `ANOMALY_DETECTION` | 异常检测 | 异常行为识别 |
| `TIME_SERIES` | 时间序列 | 趋势预测 |

## 模型状态

| 状态 | 说明 |
|------|------|
| `DRAFT` | 草稿 |
| `TRAINING` | 训练中 |
| `EVALUATING` | 评估中 |
| `DEPLOYED` | 已部署 |
| `ARCHIVED` | 已归档 |

## 创建模型

```http
POST /api/ml-models
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "name": "Churn Prediction Model",
  "type": "CLASSIFICATION",
  "algorithm": "Random Forest",
  "framework": "scikit-learn",
  "description": "预测玩家流失概率"
}
```

**响应:**
```json
{
  "id": "ml_abc123",
  "gameId": "game_abc123",
  "name": "Churn Prediction Model",
  "type": "CLASSIFICATION",
  "status": "DRAFT",
  "version": 1,
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 启动训练

```http
POST /api/ml-models/{id}/train
Content-Type: application/json
Authorization: Bearer {token}

{
  "datasetConfig": {
    "source": "clickhouse",
    "query": "SELECT * FROM events WHERE event_date > '2024-01-01'",
    "features": ["play_time", "level", "purchases"],
    "label": "churned"
  },
  "hyperparameters": {
    "n_estimators": 100,
    "max_depth": 10
  }
}
```

## 部署模型

```http
POST /api/ml-models/{id}/deploy
Content-Type: application/json
Authorization: Bearer {token}

{
  "servingEndpoint": "http://ml-service:8080/predict",
  "canaryDeployment": false
}
```

## 预测请求

```http
POST /api/ml-models/{id}/predict
Content-Type: application/json
Authorization: Bearer {token}

{
  "features": {
    "play_time": 120,
    "level": 15,
    "purchases": 3
  }
}
```

**响应:**
```json
{
  "prediction": "churn",
  "probability": 0.85,
  "confidence": 0.92,
  "latencyMs": 15
}
```

## 模型监控

- **预测量** - 每日预测请求数
- **延迟** - P50/P95/P99 延迟
- **准确率** - 基于反馈的准确率
- **漂移检测** - 模型性能下降告警

## A/B 测试

支持模型 A/B 测试：

```http
POST /api/ml-models/{id}/ab-test
Content-Type: application/json
Authorization: Bearer {token}

{
  "baselineModelId": "ml_baseline",
  "trafficSplit": 50
}
```
