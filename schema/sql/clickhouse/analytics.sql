-- 由 Flink 作业写入的分析聚合表
-- 与 schema.sql 中的 retention / funnels 完整模型表互补：
--   retention_daily  —— RetentionJob 按日聚合输出
--   funnels_2step    —— FunnelsJob 两步漏斗聚合输出
-- 字段与 Flink INSERT 列表、BI 数据集 yaml 保持一致。

CREATE TABLE IF NOT EXISTS retention_daily
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  cohort_date Date,
  d UInt16,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, d);

CREATE TABLE IF NOT EXISTS funnels_2step
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  event_date Date,
  step1 String,
  step2 String,
  started UInt64,
  completed UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, step1, step2);
