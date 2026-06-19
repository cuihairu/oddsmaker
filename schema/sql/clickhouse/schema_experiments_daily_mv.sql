-- Daily pre-aggregations (no dimensions) for experiments

-- 1) Daily exposures by variant
CREATE TABLE IF NOT EXISTS exp_daily_exposures
(
  game_id String,
  environment LowCardinality(String),
  event_date Date,
  exp String,
  variant String,
  exposures UInt64,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, exp, variant)
TTL event_date + INTERVAL 400 DAY DELETE;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exp_daily_exposures
TO exp_daily_exposures AS
SELECT game_id,
       environment,
       toDate(expose_ts) AS event_date,
       exp,
       variant,
       count() AS exposures,
       uniqExact(uid) AS users
FROM exp_exposure_users
GROUP BY game_id, environment, event_date, exp, variant;

-- 2) Daily 24h conversion by variant
CREATE TABLE IF NOT EXISTS exp_daily_conv_24h
(
  game_id String,
  environment LowCardinality(String),
  exposure_date Date,
  exp String,
  variant String,
  exposed_users UInt64,
  converted_users UInt64,
  cr_24h Float32
)
ENGINE = ReplacingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(exposure_date))
ORDER BY (game_id, environment, exposure_date, exp, variant)
TTL exposure_date + INTERVAL 400 DAY DELETE;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exp_daily_conv_24h
TO exp_daily_conv_24h AS
WITH conv AS (
  SELECT game_id, environment, uid, min(conv_ts) AS conv_ts
  FROM exp_first_level_complete
  GROUP BY game_id, environment, uid
)
SELECT e.game_id,
       e.environment,
       toDate(e.expose_ts) AS exposure_date,
       e.exp,
       e.variant,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 24 HOUR) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_24h
FROM exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant;

-- 3) Daily 7d conversion by variant
CREATE TABLE IF NOT EXISTS exp_daily_conv_7d
(
  game_id String,
  environment LowCardinality(String),
  exposure_date Date,
  exp String,
  variant String,
  exposed_users UInt64,
  converted_users UInt64,
  cr_7d Float32
)
ENGINE = ReplacingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(exposure_date))
ORDER BY (game_id, environment, exposure_date, exp, variant)
TTL exposure_date + INTERVAL 400 DAY DELETE;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exp_daily_conv_7d
TO exp_daily_conv_7d AS
WITH conv AS (
  SELECT game_id, environment, uid, min(conv_ts) AS conv_ts
  FROM exp_first_level_complete
  GROUP BY game_id, environment, uid
)
SELECT e.game_id,
       e.environment,
       toDate(e.expose_ts) AS exposure_date,
       e.exp,
       e.variant,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 7 DAY) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_7d
FROM exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant;
