# ML Models API

Machine learning model management API for model training, deployment, and prediction. Actual training is executed by the `oddsmaker-ml` Python pipeline in the `ml/` directory (four types of linear, interpretable models: churn / pltv / risk / propensity). The outputs are versioned JSON, registered via `/api/ml-artifacts`, and then batch-scored and written back by the Control side.

## Endpoint List

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/ml-models` | Create a model |
| GET | `/api/ml-models/{id}` | Get model details |
| GET | `/api/ml-models/game/{gameId}` | List models by game |
| GET | `/api/ml-models/deployed` | List of deployed models |
| PUT | `/api/ml-models/{id}` | Update model configuration |
| DELETE | `/api/ml-models/{id}` | Delete a model |
| POST | `/api/ml-models/{id}/archive` | Archive a model |
| POST | `/api/ml-models/{id}/training` | Create a training job |
| POST | `/api/ml-models/training/{trainingId}/start` | Start a training job |
| PUT | `/api/ml-models/training/{trainingId}/progress` | Write back training progress |
| POST | `/api/ml-models/training/{trainingId}/complete` | Mark training complete |
| POST | `/api/ml-models/training/{trainingId}/fail` | Mark training failed |
| POST | `/api/ml-models/training/{trainingId}/cancel` | Cancel a training job |
| GET | `/api/ml-models/training/{trainingId}` | Get training job details |
| GET | `/api/ml-models/{id}/training` | Model training history |
| POST | `/api/ml-models/{id}/deploy` | Deploy a model |
| POST | `/api/ml-models/predictions` | Record a prediction |
| POST | `/api/ml-models/predictions/{predictionId}/complete` | Mark prediction complete |
| POST | `/api/ml-models/predictions/{predictionId}/fail` | Mark prediction failed |
| POST | `/api/ml-models/predictions/{predictionId}/feedback` | Add prediction feedback |
| GET | `/api/ml-models/predictions/{predictionId}` | Get a single prediction record |
| GET | `/api/ml-models/{id}/predictions` | Prediction history |
| GET | `/api/ml-models/{id}/predictions/range` | Query predictions by time range |
| GET | `/api/ml-models/{id}/stats` | Model statistics |
| GET | `/api/ml-models/stats/global` | Global ML statistics |
| GET | `/api/ml-models/{id}/drift` | Drift detection report |

There is no `GET /api/ml-models` (no full list), `/{id}/train`, or `/{id}/predict` endpoints — training jobs go through the `/training` subresource, and scoring goes through the `/api/prediction-metrics` batch pipeline.

## Model Types

`CLASSIFICATION`, `REGRESSION`, `CLUSTERING`, `ANOMALY_DETECTION`, `RECOMMENDATION`, `TIME_SERIES`, `NLP`, `CUSTOM`. `framework` is a free-form string (e.g. `scikit-learn`).

## Model Statuses

`DRAFT`, `TRAINING`, `EVALUATING`, `DEPLOYED`, `STAGING`, `ARCHIVED`, `FAILED`.

## Create a Model

```http
POST /api/ml-models
Content-Type: application/json
Authorization: Bearer {token}

{
  "gameId": "game_abc123",
  "modelName": "Churn Prediction Model",
  "type": "CLASSIFICATION",
  "framework": "scikit-learn",
  "description": "预测玩家流失概率"
}
```

**Response:**
```json
{
  "id": "ml_abc123",
  "gameId": "game_abc123",
  "modelName": "Churn Prediction Model",
  "type": "CLASSIFICATION",
  "status": "DRAFT",
  "version": 1,
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Training

Two paths: manual (create a job via `POST /api/ml-models/{id}/training`, start it via `POST /api/ml-models/training/{trainingId}/start`) and scheduled (the `MlRetrainScheduler` retrains automatically daily at 04:20, the switch is off by default, configurable via `oddsmaker.ml.retrain.*`). The data source prefers real ClickHouse data, falling back to synthetic data via the `allow-synthetic` switch when unavailable. The output (a JSON with feature_names + coefficients + intercept) is registered via `POST /api/ml-artifacts`; after validation passes, the scoring pipeline prefers the model score (`path=model`), falling back to heuristics (`path=heuristic`) when missing or mismatched. Registered outputs can be queried: `GET /api/ml-artifacts/game/{gameId}` lists registered versions (optional `modelType` filter), and `GET /api/ml-artifacts/game/{gameId}/active` fetches the currently effective version for that type (returns 404 if none registered).

## Scoring and Prediction

There is no real-time single-record `predict` endpoint. Scoring is batch-based: `PredictionMetricsService` fetches features from ClickHouse by feature_names, computes a dot product + sigmoid (for pltv, D7 revenue × multiplier), and writes the results back to the ClickHouse `predictions` table (TTL 30 days). Under `/api/prediction-metrics`, trigger and refresh endpoints are provided for the four types churn / risk-score / pltv / propensity; `GET /api/ml-models/{id}/predictions` queries history.

## Model Monitoring

`GET /api/ml-models/{id}/drift` returns the drift report within the window; `/{id}/stats` outputs prediction volume and feedback statistics.

## A/B Testing

Model-level A/B testing is configured via `POST /api/ml-models/{id}/ab-test` and stopped via `DELETE`.
