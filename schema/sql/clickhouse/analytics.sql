-- 由 Flink 作业写入的分析聚合表
-- 与 schema.sql 中的 retention / funnels 完整模型表互补：
--   retention_daily  —— RetentionJob 按日聚合输出
--   funnels_2step    —— FunnelsJob 两步漏斗聚合输出
-- 字段与 Flink INSERT 列表、BI 数据集 yaml 保持一致。

CREATE TABLE IF NOT EXISTS retention_daily
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  subject_id String,
  cohort_date Date,
  d UInt16,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
-- subject_id 必须进排序键：SummingMergeTree 按 ORDER BY 键折叠同键行对 users 求和，
-- 不进键会把不同主体合并成"去重前计数"。进键后每主体一行（users=1），
-- 上层 sum(users) 聚合口径不变，且支持 subject_id IN (SELECT ... segment_members) 分群下推。
-- 已有部署需重建表迁移（SummingMergeTree 不支持修改排序键）：
-- 脚本见 migrations/2026-09-retention-daily-subject-id.sql（停旧 job → 迁移 → 换名 → 起新 job）。
ORDER BY (game_id, environment, cohort_date, d, subject_id);

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

-- Rolling 留存：第 n 天及以后任意一天活跃（无界口径）
CREATE TABLE IF NOT EXISTS retention_rolling
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  cohort_date Date,
  n UInt16,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, n);
