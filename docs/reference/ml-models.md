# ML Models API

机器学习模型管理 API，用于模型训练、部署和预测。实际训练由 `ml/` 目录的 `oddsmaker-ml` Python 管线执行（churn / pltv / risk / propensity 四类线性可解释模型），产物为版本化 JSON，经 `/api/ml-artifacts` 注册后由 Control 侧批量打分回写。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/ml-models` | 创建模型 |
| GET | `/api/ml-models/{id}` | 获取模型详情 |
| GET | `/api/ml-models/game/{gameId}` | 按游戏列模型 |
| GET | `/api/ml-models/deployed` | 已部署模型列表 |
| PUT | `/api/ml-models/{id}` | 更新模型配置 |
| DELETE | `/api/ml-models/{id}` | 删除模型 |
| POST | `/api/ml-models/{id}/archive` | 归档模型 |
| POST | `/api/ml-models/{id}/training` | 创建训练任务 |
| POST | `/api/ml-models/training/{trainingId}/start` | 启动训练任务 |
| PUT | `/api/ml-models/training/{trainingId}/progress` | 回写训练进度 |
| POST | `/api/ml-models/training/{trainingId}/complete` | 标记训练完成 |
| POST | `/api/ml-models/training/{trainingId}/fail` | 标记训练失败 |
| POST | `/api/ml-models/{id}/deploy` | 部署模型 |
| POST | `/api/ml-models/predictions` | 记录一条预测 |
| GET | `/api/ml-models/{id}/predictions` | 预测历史 |
| GET | `/api/ml-models/{id}/stats` | 模型统计 |
| GET | `/api/ml-models/{id}/drift` | 漂移检测报告 |

没有 `GET /api/ml-models`（无全量列表）、`/{id}/train`、`/{id}/predict` 这三个端点——训练任务走 `/training` 子资源，打分走 `/api/prediction-metrics` 批量链路。

## 模型类型

`CLASSIFICATION`、`REGRESSION`、`CLUSTERING`、`ANOMALY_DETECTION`、`RECOMMENDATION`、`TIME_SERIES`、`NLP`、`CUSTOM`。`framework` 是自由字符串（如 `scikit-learn`）。

## 模型状态

`DRAFT`、`TRAINING`、`EVALUATING`、`DEPLOYED`、`STAGING`、`ARCHIVED`、`FAILED`。

## 创建模型

```http
POST /api/ml-models
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "name": "Churn Prediction Model",
  "type": "CLASSIFICATION",
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

## 训练

两条路径：手动（`POST /api/ml-models/{id}/training` 建任务，`POST /api/ml-models/training/{trainingId}/start` 启动）和定时（`MlRetrainScheduler` 每日 04:20 自动重训，开关默认关闭，`oddsmaker.ml.retrain.*` 可配）。数据源优先 ClickHouse 真实数据，不可用时按 `allow-synthetic` 开关回落合成数据。产物（feature_names + coefficients + intercept 的 JSON）经 `POST /api/ml-artifacts` 注册，校验通过后打分链路优先模型分（`path=model`），缺失或不匹配时回落启发式（`path=heuristic`）。

## 打分与预测

实时单条 `predict` 端点没有。打分是批量的：`PredictionMetricsService` 按 feature_names 从 ClickHouse 取特征，点积 + sigmoid（pltv 为 D7 收入 × 乘数），结果回写 ClickHouse `predictions` 表（TTL 30 天）。`/api/prediction-metrics` 下按 churn / risk-score / pltv / propensity 四类提供触发与 refresh 端点，`GET /api/ml-models/{id}/predictions` 查历史。

## 模型监控

`GET /api/ml-models/{id}/drift` 返回窗口内的漂移报告；`/{id}/stats` 输出预测量与反馈统计。

## A/B 测试

模型级 A/B 测试通过 `POST /api/ml-models/{id}/ab-test` 配置、`DELETE` 停止。
