-- Experiment MVs and helper tables for performance

-- 1) Exposure users (one row per exposure event with user + dims)
CREATE TABLE IF NOT EXISTS exp_exposure_users
(
  game_id String,
  environment LowCardinality(String),
  exp String,
  variant String,
  uid String,
  expose_ts DateTime64(3),
  platform LowCardinality(String),
  app_version LowCardinality(String),
  country FixedString(2)
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(expose_ts))
ORDER BY (game_id, environment, exp, variant, uid, expose_ts);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exp_exposure_users
TO exp_exposure_users AS
SELECT game_id,
       environment,
       JSONExtractString(props_json,'exp') AS exp,
       JSONExtractString(props_json,'variant') AS variant,
       if(user_id!='',user_id,device_id) AS uid,
       ts_server AS expose_ts,
       platform, app_version, country
FROM events
WHERE event_name='experiment_exposure';

-- 2) First conversion per user (primary metric assumed 'level_complete' for MVP)
CREATE TABLE IF NOT EXISTS exp_first_level_complete
(
  game_id String,
  environment LowCardinality(String),
  uid String,
  conv_ts DateTime64(3)
)
ENGINE = ReplacingMergeTree
ORDER BY (game_id, environment, uid);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exp_first_level_complete
TO exp_first_level_complete AS
SELECT game_id,
       environment,
       if(user_id!='',user_id,device_id) AS uid,
       min(ts_server) AS conv_ts
FROM events
WHERE event_name='level_complete'
GROUP BY game_id, environment, uid;
