-- ML 预测表和特征视图（P4.4）
-- 预测结果表供 Control Service / BI 查询；特征视图供训练管线抽取特征。

-- 预测结果表（每次预测写入一行，ReplacingMergeTree 保留最新预测）
CREATE TABLE IF NOT EXISTS predictions
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  user_id String,
  model_id String,
  model_version LowCardinality(String),
  prediction_type LowCardinality(String),
  score Float32,
  predicted_at DateTime64(3),
  expires_at DateTime64(3)
)
ENGINE = ReplacingMergeTree(predicted_at)
PARTITION BY (game_id, environment, toYYYYMM(predicted_at))
ORDER BY (game_id, environment, prediction_type, user_id, model_id);

-- 用户 7 天特征视图（训练 + 实时打分共用）
CREATE OR REPLACE VIEW v_user_features_7d AS
SELECT
  game_id,
  environment,
  user_id,
  count() AS event_count,
  uniqExactIf(session_id, session_id != '') AS session_count,
  countIf(event_type = 'resource') AS resource_event_count,
  sumIf(revenue_amount, revenue_amount > 0) AS revenue_total,
  sumIfIf(resource_amount, event_type = 'resource', flow_type = 'source') AS resource_source_total,
  sumIfIf(resource_amount, event_type = 'resource', flow_type = 'sink') AS resource_sink_total,
  max(event_date) AS last_active_date,
  dateDiff('day', max(event_date), today()) AS days_inactive,
  uniqExactIf(level_id, level_id != '') AS unique_levels,
  countIf(event_name = 'level_complete') AS levels_completed
FROM events
WHERE event_date >= today() - 7
  AND user_id != ''
GROUP BY game_id, environment, user_id;

-- 用户 30 天特征视图
CREATE OR REPLACE VIEW v_user_features_30d AS
SELECT
  game_id,
  environment,
  user_id,
  count() AS event_count_30d,
  uniqExactIf(session_id, session_id != '') AS session_count_30d,
  sumIf(revenue_amount, revenue_amount > 0) AS revenue_total_30d,
  max(event_date) AS last_active_date_30d,
  dateDiff('day', max(event_date), today()) AS days_inactive_30d
FROM events
WHERE event_date >= today() - 30
  AND user_id != ''
GROUP BY game_id, environment, user_id;

-- 查询某用户的当前预测
-- SELECT prediction_type, score, predicted_at
-- FROM predictions FINAL
-- WHERE game_id = 'game_demo' AND environment = 'prod'
--   AND user_id = 'u123'
-- ORDER BY prediction_type;

-- 查询高流失风险用户（top 100）
-- SELECT p.user_id, p.score, f.days_inactive, f.event_count
-- FROM predictions p
-- ANY LEFT JOIN v_user_features_7d f
--   ON p.user_id = f.user_id AND p.game_id = f.game_id AND p.environment = f.environment
-- WHERE p.prediction_type = 'churn' AND p.game_id = 'game_demo'
-- ORDER BY p.score DESC
-- LIMIT 100;
