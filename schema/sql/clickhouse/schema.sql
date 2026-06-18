-- ClickHouse DDL（Pit v2 - 多租户）
-- tenant_id = organization_id; app_id = game_id + environment

CREATE TABLE IF NOT EXISTS events
(
  tenant_id LowCardinality(String),
  app_id LowCardinality(String),
  event_date Date DEFAULT toDate(ts_server),
  ts_server DateTime64(3) DEFAULT now64(3),
  event_id String,
  event_name LowCardinality(String),
  user_id String DEFAULT '',
  device_id String,
  session_id String DEFAULT '',
  platform LowCardinality(String),
  app_version LowCardinality(String),
  country FixedString(2) DEFAULT '' ,
  props_json JSON,
  revenue_amount Decimal(18,4) DEFAULT 0,
  revenue_currency FixedString(3) DEFAULT 'USD'
)
ENGINE = MergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, user_id, device_id, ts_server, event_name, event_id)
TTL event_date + INTERVAL 365 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_events_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, event_name)
AS
SELECT tenant_id, app_id, event_date, event_name,
       countState() AS evts
FROM events
GROUP BY tenant_id, app_id, event_date, event_name;

CREATE TABLE IF NOT EXISTS sessions
(
  tenant_id LowCardinality(String),
  app_id LowCardinality(String),
  session_id String,
  user_id String,
  device_id String,
  session_start DateTime64(3),
  session_end DateTime64(3),
  duration UInt32,
  country FixedString(2),
  events UInt32
)
ENGINE = MergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(session_start))
ORDER BY (tenant_id, app_id, user_id, session_start);

-- 每日活跃用户（基于 events 表）
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dau
ENGINE = AggregatingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date)
AS
SELECT
  tenant_id,
  app_id,
  event_date,
  uniqState(if(user_id != '' , user_id, device_id)) AS dau
FROM events
GROUP BY tenant_id, app_id, event_date;

-- 便于 BI 的视图
CREATE OR REPLACE VIEW v_events_trend AS
SELECT tenant_id, app_id, event_date, event_name, countMerge(evts) AS events
FROM mv_events_by_day
GROUP BY tenant_id, app_id, event_date, event_name;

CREATE OR REPLACE VIEW v_dau_trend AS
SELECT tenant_id, app_id, event_date, uniqExactMerge(dau) AS dau
FROM mv_dau
GROUP BY tenant_id, app_id, event_date;

-- 收入：按日与货币聚合
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_revenue_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, revenue_currency)
AS
SELECT
  tenant_id,
  app_id,
  toDate(ts_server) AS event_date,
  revenue_currency,
  sumState(revenue_amount) AS amount
FROM events
WHERE revenue_amount > 0
GROUP BY tenant_id, app_id, event_date, revenue_currency;

CREATE OR REPLACE VIEW v_revenue_by_day AS
SELECT tenant_id, app_id, event_date, revenue_currency, sumMerge(amount) AS revenue
FROM mv_revenue_by_day
GROUP BY tenant_id, app_id, event_date, revenue_currency;

-- UA/OS 维度（按 props_json 提取）
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_ua_os_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, ua_family, os_family)
AS
SELECT
  tenant_id,
  app_id,
  toDate(ts_server) AS event_date,
  ifNull(JSONExtractString(props_json,'ua_family'),'') AS ua_family,
  ifNull(JSONExtractString(props_json,'os_family'),'') AS os_family,
  countState() AS c
FROM events
GROUP BY tenant_id, app_id, event_date, ua_family, os_family;

CREATE OR REPLACE VIEW v_ua_by_day AS
SELECT tenant_id, app_id, event_date, ua_family, countMerge(c) AS events
FROM mv_ua_os_by_day
GROUP BY tenant_id, app_id, event_date, ua_family;

CREATE OR REPLACE VIEW v_os_by_day AS
SELECT tenant_id, app_id, event_date, os_family, countMerge(c) AS events
FROM mv_ua_os_by_day
GROUP BY tenant_id, app_id, event_date, os_family;

-- Retention 按 cohort 日与偏移 d（0/1/7/30）聚合
CREATE TABLE IF NOT EXISTS retention_daily
(
  tenant_id LowCardinality(String),
  app_id LowCardinality(String),
  cohort_date Date,
  d UInt16,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(cohort_date))
ORDER BY (tenant_id, app_id, cohort_date, d);

-- 漏斗（两步）：按日聚合 started/completed
CREATE TABLE IF NOT EXISTS funnels_2step
(
  tenant_id LowCardinality(String),
  app_id LowCardinality(String),
  event_date Date,
  step1 LowCardinality(String),
  step2 LowCardinality(String),
  started UInt64,
  completed UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, step1, step2);

-- 多租户行级安全策略（生产启用；本地开发可注释）
-- CREATE ROW POLICY tenant_isolation ON events
--   FOR SELECT USING (tenant_id = currentRowPolicyTenant());
