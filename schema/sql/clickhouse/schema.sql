-- ClickHouse DDL（Oddsmaker v1 - 单公司多游戏）
-- 公司是部署边界；事件、配置和查询隔离维度为 game_id + environment。

CREATE TABLE IF NOT EXISTS events
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  event_date Date DEFAULT toDate(ts_server),
  ts_server DateTime64(3) DEFAULT now64(3),
  ts_client DateTime64(3),

  event_id String,
  event_type LowCardinality(String),
  event_name LowCardinality(String),

  user_id String DEFAULT '',
  device_id String,
  player_id String DEFAULT '',
  character_id String DEFAULT '',
  session_id String DEFAULT '',

  platform LowCardinality(String) DEFAULT '',
  app_version LowCardinality(String) DEFAULT '',
  sdk_version LowCardinality(String) DEFAULT '',
  country FixedString(2) DEFAULT '',
  client_ip_hash String DEFAULT '',
  user_agent String DEFAULT '',

  server_id String DEFAULT '',
  guild_id String DEFAULT '',
  match_id String DEFAULT '',
  level_id String DEFAULT '',
  game_mode LowCardinality(String) DEFAULT '',
  difficulty LowCardinality(String) DEFAULT '',

  order_id String DEFAULT '',
  product_id String DEFAULT '',
  revenue_amount Decimal(18,4) DEFAULT 0,
  revenue_currency FixedString(3) DEFAULT '',
  receipt_hash String DEFAULT '',

  virtual_currency LowCardinality(String) DEFAULT '',
  virtual_amount Int64 DEFAULT 0,
  flow_type LowCardinality(String) DEFAULT '',
  item_id String DEFAULT '',

  resource_id String DEFAULT '',
  resource_amount Decimal(18,4) DEFAULT 0,

  ad_network LowCardinality(String) DEFAULT '',
  ad_placement String DEFAULT '',
  ad_format LowCardinality(String) DEFAULT '',
  ad_impression_id String DEFAULT '',

  experiments Map(String, String),
  attribution Map(String, String),
  risk_context Map(String, String),
  props Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_type, event_date, player_id, user_id, device_id, ts_server, event_id)
TTL event_date + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_events_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, event_type, event_name)
AS
SELECT
  game_id,
  environment,
  event_date,
  event_type,
  event_name,
  countState() AS evts
FROM events
GROUP BY game_id, environment, event_date, event_type, event_name;

CREATE OR REPLACE VIEW v_events_trend AS
SELECT
  game_id,
  environment,
  event_date,
  event_type,
  event_name,
  countMerge(evts) AS events
FROM mv_events_by_day
GROUP BY game_id, environment, event_date, event_type, event_name;

CREATE TABLE IF NOT EXISTS sessions
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  session_id String,
  identity_id String DEFAULT '',
  user_id String DEFAULT '',
  player_id String DEFAULT '',
  device_id String,
  session_start DateTime64(3),
  session_end DateTime64(3),
  duration UInt32,
  country FixedString(2),
  events UInt32
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(session_start))
ORDER BY (game_id, environment, identity_id, user_id, player_id, device_id, session_start);

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dau
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date)
AS
SELECT
  game_id,
  environment,
  event_date,
  uniqState(if(player_id != '', player_id, if(user_id != '', user_id, device_id))) AS dau
FROM events
GROUP BY game_id, environment, event_date;

CREATE OR REPLACE VIEW v_dau_trend AS
SELECT game_id, environment, event_date, uniqExactMerge(dau) AS dau
FROM mv_dau
GROUP BY game_id, environment, event_date;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_revenue_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, revenue_currency)
AS
SELECT
  game_id,
  environment,
  event_date,
  revenue_currency,
  sumState(revenue_amount) AS amount
FROM events
WHERE revenue_amount > 0
GROUP BY game_id, environment, event_date, revenue_currency;

CREATE OR REPLACE VIEW v_revenue_by_day AS
SELECT game_id, environment, event_date, revenue_currency, sumMerge(amount) AS revenue
FROM mv_revenue_by_day
GROUP BY game_id, environment, event_date, revenue_currency;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_ua_os_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, ua_family, os_family)
AS
SELECT
  game_id,
  environment,
  event_date,
  ifNull(props['ua_family'], '') AS ua_family,
  ifNull(props['os_family'], '') AS os_family,
  countState() AS c
FROM events
GROUP BY game_id, environment, event_date, ua_family, os_family;

CREATE OR REPLACE VIEW v_ua_by_day AS
SELECT game_id, environment, event_date, ua_family, countMerge(c) AS events
FROM mv_ua_os_by_day
GROUP BY game_id, environment, event_date, ua_family;

CREATE OR REPLACE VIEW v_os_by_day AS
SELECT game_id, environment, event_date, os_family, countMerge(c) AS events
FROM mv_ua_os_by_day
GROUP BY game_id, environment, event_date, os_family;

CREATE TABLE IF NOT EXISTS retention
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  cohort_date Date,
  d_offset UInt16,
  retention_type LowCardinality(String),
  segment LowCardinality(String) DEFAULT '',
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, d_offset, retention_type, segment);

CREATE TABLE IF NOT EXISTS funnels
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  funnel_id String,
  funnel_version UInt32,
  ts DateTime64(3),
  identity_id String,
  step_completed UInt16,
  completed_all Bool
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(ts))
ORDER BY (game_id, environment, funnel_id, funnel_version, identity_id, ts);

CREATE TABLE IF NOT EXISTS identities
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  identity_id String,
  user_id String DEFAULT '',
  player_id String DEFAULT '',
  character_ids Array(String),
  device_ids Array(String),
  first_seen DateTime64(3),
  last_seen DateTime64(3),
  risk_score Float32 DEFAULT 0
)
ENGINE = ReplacingMergeTree(last_seen)
PARTITION BY (game_id, environment, toYYYYMM(last_seen))
ORDER BY (game_id, environment, identity_id);

CREATE TABLE IF NOT EXISTS risk_events
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  ts DateTime64(3),
  risk_event_id String,
  source_event_id String DEFAULT '',
  rule_id String,
  risk_type LowCardinality(String),
  severity LowCardinality(String),
  subject_type LowCardinality(String),
  subject_id String,
  score Float32,
  action LowCardinality(String),
  reason String,
  evidence Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(ts))
ORDER BY (game_id, environment, risk_type, severity, ts, subject_id);

CREATE TABLE IF NOT EXISTS risk_scores
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  subject_type LowCardinality(String),
  subject_id String,
  score Float32,
  updated_at DateTime64(3),
  reasons Array(String)
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY (game_id, environment, toYYYYMM(updated_at))
ORDER BY (game_id, environment, subject_type, subject_id);
