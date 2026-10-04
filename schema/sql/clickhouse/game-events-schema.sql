-- ClickHouse DDL for Game Events
-- 游戏事件表结构，支持关卡、战斗、任务、成就、物品、货币、广告、社交等游戏事件

-- 游戏事件表
CREATE TABLE IF NOT EXISTS game_events
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  event_date Date DEFAULT toDate(ts_server),
  ts_server DateTime64(3) DEFAULT now64(3),
  ts_client DateTime64(3),

  event_id String,
  event_type LowCardinality(String),
  event_name LowCardinality(String),
  game_event_type LowCardinality(String),

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

  -- 关卡相关字段
  level_id String DEFAULT '',
  level_name String DEFAULT '',
  level_progress Float32 DEFAULT 0,
  level_score UInt64 DEFAULT 0,
  level_time_ms UInt64 DEFAULT 0,
  level_attempts UInt32 DEFAULT 0,

  -- 战斗相关字段
  battle_id String DEFAULT '',
  battle_mode LowCardinality(String) DEFAULT '',
  battle_result LowCardinality(String) DEFAULT '',
  battle_duration_ms UInt64 DEFAULT 0,
  battle_rank UInt32 DEFAULT 0,
  battle_kills UInt32 DEFAULT 0,
  battle_deaths UInt32 DEFAULT 0,
  battle_assists UInt32 DEFAULT 0,

  -- 任务相关字段
  quest_id String DEFAULT '',
  quest_name String DEFAULT '',
  quest_type LowCardinality(String) DEFAULT '',
  quest_progress Float32 DEFAULT 0,
  quest_objective_current UInt32 DEFAULT 0,
  quest_objective_target UInt32 DEFAULT 0,

  -- 成就相关字段
  achievement_id String DEFAULT '',
  achievement_name String DEFAULT '',
  achievement_category LowCardinality(String) DEFAULT '',
  achievement_points UInt32 DEFAULT 0,

  -- 物品相关字段
  item_id String DEFAULT '',
  item_type LowCardinality(String) DEFAULT '',
  item_name String DEFAULT '',
  item_rarity LowCardinality(String) DEFAULT '',
  item_quantity UInt32 DEFAULT 0,
  item_level UInt32 DEFAULT 0,

  -- 货币相关字段
  currency_type LowCardinality(String) DEFAULT '',
  currency_amount Decimal(18,4) DEFAULT 0,
  currency_balance Decimal(18,4) DEFAULT 0,
  currency_source LowCardinality(String) DEFAULT '',
  currency_sink LowCardinality(String) DEFAULT '',

  -- 广告相关字段
  ad_type LowCardinality(String) DEFAULT '',
  ad_placement_id String DEFAULT '',
  ad_reward_type LowCardinality(String) DEFAULT '',
  ad_reward_amount Decimal(18,4) DEFAULT 0,
  ad_duration_ms UInt64 DEFAULT 0,
  ad_completed UInt8 DEFAULT 0,

  -- 社交相关字段
  social_action LowCardinality(String) DEFAULT '',
  social_target_user_id String DEFAULT '',
  social_target_player_id String DEFAULT '',

  -- 错误相关字段
  error_type LowCardinality(String) DEFAULT '',
  error_message String DEFAULT '',
  error_stack_trace String DEFAULT '',
  error_fatal UInt8 DEFAULT 0,

  -- 收入相关字段
  revenue_amount Decimal(18,4) DEFAULT 0,
  revenue_currency FixedString(3) DEFAULT '',
  order_id String DEFAULT '',
  product_id String DEFAULT '',
  receipt_hash String DEFAULT '',

  -- 虚拟货币相关字段
  virtual_currency LowCardinality(String) DEFAULT '',
  virtual_amount Decimal(18,4) DEFAULT 0,
  flow_type LowCardinality(String) DEFAULT '',
  operation_id String DEFAULT '',
  operation_type LowCardinality(String) DEFAULT '',

  -- 资源相关字段
  resource_id String DEFAULT '',
  resource_amount Decimal(18,4) DEFAULT 0,

  -- 广告相关字段
  ad_network LowCardinality(String) DEFAULT '',
  ad_placement String DEFAULT '',
  ad_format LowCardinality(String) DEFAULT '',
  ad_impression_id String DEFAULT '',

  -- 事件契约 v2（06 计划书 §4.2）：网关 TrustPolicy 权威回填；''=契约 v2 前的历史行
  event_version UInt32 DEFAULT 1,
  source LowCardinality(String) DEFAULT '',
  trust_level LowCardinality(String) DEFAULT '',
  event_origin LowCardinality(String) DEFAULT '',

  -- 实验相关字段
  experiments Map(String, String) DEFAULT map(),
  
  -- 风控相关字段
  risk_context Map(String, String) DEFAULT map(),
  device_fingerprint String DEFAULT '',
  client_integrity String DEFAULT '',

  -- 扩展属性
  props_json String DEFAULT '{}'
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, game_event_type, event_date, player_id, user_id, device_id, ts_server, event_id)
TTL event_date + INTERVAL 365 DAY;

-- 游戏事件每日聚合物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_game_events_by_day
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, game_event_type, event_name)
AS
SELECT
  game_id,
  environment,
  event_date,
  game_event_type,
  event_name,
  countState() AS evts,
  uniqState(user_id) AS users,
  uniqState(device_id) AS devices,
  uniqState(session_id) AS sessions
FROM game_events
GROUP BY game_id, environment, event_date, game_event_type, event_name;

-- 游戏事件趋势视图
CREATE OR REPLACE VIEW v_game_events_trend AS
SELECT
  game_id,
  environment,
  event_date,
  game_event_type,
  event_name,
  countMerge(evts) AS events,
  uniqMerge(users) AS users,
  uniqMerge(devices) AS devices,
  uniqMerge(sessions) AS sessions
FROM mv_game_events_by_day
GROUP BY game_id, environment, event_date, game_event_type, event_name;

-- 关卡分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_level_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, level_id, game_event_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  level_id,
  level_name,
  game_event_type,
  countState() AS attempts,
  uniqState(user_id) AS users,
  avgState(level_progress) AS avg_progress,
  avgState(level_score) AS avg_score,
  avgState(level_time_ms) AS avg_time_ms,
  avgState(level_attempts) AS avg_attempts
FROM game_events
WHERE game_event_type IN ('level_start', 'level_complete', 'level_fail')
GROUP BY game_id, environment, event_date, level_id, level_name, game_event_type;

-- 关卡分析视图
CREATE OR REPLACE VIEW v_level_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  level_id,
  level_name,
  game_event_type,
  countMerge(attempts) AS attempts,
  uniqMerge(users) AS users,
  avgMerge(avg_progress) AS avg_progress,
  avgMerge(avg_score) AS avg_score,
  avgMerge(avg_time_ms) AS avg_time_ms,
  avgMerge(avg_attempts) AS avg_attempts
FROM mv_level_analysis
GROUP BY game_id, environment, event_date, level_id, level_name, game_event_type;

-- 战斗分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_battle_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, battle_mode, battle_result)
AS
SELECT
  game_id,
  environment,
  event_date,
  battle_mode,
  battle_result,
  countState() AS battles,
  uniqState(user_id) AS users,
  avgState(battle_duration_ms) AS avg_duration_ms,
  avgState(battle_rank) AS avg_rank,
  avgState(battle_kills) AS avg_kills,
  avgState(battle_deaths) AS avg_deaths,
  avgState(battle_assists) AS avg_assists
FROM game_events
WHERE game_event_type IN ('battle_start', 'battle_end')
GROUP BY game_id, environment, event_date, battle_mode, battle_result;

-- 战斗分析视图
CREATE OR REPLACE VIEW v_battle_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  battle_mode,
  battle_result,
  countMerge(battles) AS battles,
  uniqMerge(users) AS users,
  avgMerge(avg_duration_ms) AS avg_duration_ms,
  avgMerge(avg_rank) AS avg_rank,
  avgMerge(avg_kills) AS avg_kills,
  avgMerge(avg_deaths) AS avg_deaths,
  avgMerge(avg_assists) AS avg_assists
FROM mv_battle_analysis
GROUP BY game_id, environment, event_date, battle_mode, battle_result;

-- 任务分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_quest_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, quest_id, game_event_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  quest_id,
  quest_name,
  quest_type,
  game_event_type,
  countState() AS events,
  uniqState(user_id) AS users,
  avgState(quest_progress) AS avg_progress,
  avgState(quest_objective_current) AS avg_objective_current,
  avgState(quest_objective_target) AS avg_objective_target
FROM game_events
WHERE game_event_type IN ('quest_accept', 'quest_complete')
GROUP BY game_id, environment, event_date, quest_id, quest_name, quest_type, game_event_type;

-- 任务分析视图
CREATE OR REPLACE VIEW v_quest_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  quest_id,
  quest_name,
  quest_type,
  game_event_type,
  countMerge(events) AS events,
  uniqMerge(users) AS users,
  avgMerge(avg_progress) AS avg_progress,
  avgMerge(avg_objective_current) AS avg_objective_current,
  avgMerge(avg_objective_target) AS avg_objective_target
FROM mv_quest_analysis
GROUP BY game_id, environment, event_date, quest_id, quest_name, quest_type, game_event_type;

-- 成就分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_achievement_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, achievement_id)
AS
SELECT
  game_id,
  environment,
  event_date,
  achievement_id,
  achievement_name,
  achievement_category,
  countState() AS unlocks,
  uniqState(user_id) AS users,
  sumState(achievement_points) AS total_points
FROM game_events
WHERE game_event_type = 'achievement_unlock'
GROUP BY game_id, environment, event_date, achievement_id, achievement_name, achievement_category;

-- 成就分析视图
CREATE OR REPLACE VIEW v_achievement_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  achievement_id,
  achievement_name,
  achievement_category,
  countMerge(unlocks) AS unlocks,
  uniqMerge(users) AS users,
  sumMerge(total_points) AS total_points
FROM mv_achievement_analysis
GROUP BY game_id, environment, event_date, achievement_id, achievement_name, achievement_category;

-- 物品分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_item_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, item_id, game_event_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  item_id,
  item_type,
  item_name,
  item_rarity,
  game_event_type,
  countState() AS events,
  uniqState(user_id) AS users,
  sumState(item_quantity) AS total_quantity
FROM game_events
WHERE game_event_type IN ('item_grant', 'item_consume')
GROUP BY game_id, environment, event_date, item_id, item_type, item_name, item_rarity, game_event_type;

-- 物品分析视图
CREATE OR REPLACE VIEW v_item_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  item_id,
  item_type,
  item_name,
  item_rarity,
  game_event_type,
  countMerge(events) AS events,
  uniqMerge(users) AS users,
  sumMerge(total_quantity) AS total_quantity
FROM mv_item_analysis
GROUP BY game_id, environment, event_date, item_id, item_type, item_name, item_rarity, game_event_type;

-- 货币分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_currency_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, currency_type, flow_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  currency_type,
  flow_type,
  countState() AS transactions,
  uniqState(user_id) AS users,
  sumState(currency_amount) AS total_amount,
  avgState(currency_balance) AS avg_balance
FROM game_events
WHERE game_event_type IN ('currency_source', 'currency_sink')
GROUP BY game_id, environment, event_date, currency_type, flow_type;

-- 货币分析视图
CREATE OR REPLACE VIEW v_currency_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  currency_type,
  flow_type,
  countMerge(transactions) AS transactions,
  uniqMerge(users) AS users,
  sumMerge(total_amount) AS total_amount,
  avgMerge(avg_balance) AS avg_balance
FROM mv_currency_analysis
GROUP BY game_id, environment, event_date, currency_type, flow_type;

-- 广告分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_ad_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, ad_type, ad_reward_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  ad_type,
  ad_reward_type,
  countState() AS views,
  uniqState(user_id) AS users,
  sumState(ad_completed) AS completions,
  sumState(ad_reward_amount) AS total_reward_amount,
  avgState(ad_duration_ms) AS avg_duration_ms
FROM game_events
WHERE game_event_type IN ('ad_watch', 'ad_reward')
GROUP BY game_id, environment, event_date, ad_type, ad_reward_type;

-- 广告分析视图
CREATE OR REPLACE VIEW v_ad_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  ad_type,
  ad_reward_type,
  countMerge(views) AS views,
  uniqMerge(users) AS users,
  sumMerge(completions) AS completions,
  sumMerge(total_reward_amount) AS total_reward_amount,
  avgMerge(avg_duration_ms) AS avg_duration_ms
FROM mv_ad_analysis
GROUP BY game_id, environment, event_date, ad_type, ad_reward_type;

-- 社交分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_social_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, social_action)
AS
SELECT
  game_id,
  environment,
  event_date,
  social_action,
  countState() AS actions,
  uniqState(user_id) AS users,
  uniqState(social_target_user_id) AS target_users
FROM game_events
WHERE game_event_type IN ('social_invite', 'social_accept')
GROUP BY game_id, environment, event_date, social_action;

-- 社交分析视图
CREATE OR REPLACE VIEW v_social_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  social_action,
  countMerge(actions) AS actions,
  uniqMerge(users) AS users,
  uniqMerge(target_users) AS target_users
FROM mv_social_analysis
GROUP BY game_id, environment, event_date, social_action;

-- 错误分析物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_error_analysis
ENGINE = AggregatingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_date, error_type)
AS
SELECT
  game_id,
  environment,
  event_date,
  error_type,
  countState() AS errors,
  uniqState(user_id) AS users,
  sumState(error_fatal) AS fatal_errors
FROM game_events
WHERE game_event_type IN ('error_crash', 'error_exception', 'error_network')
GROUP BY game_id, environment, event_date, error_type;

-- 错误分析视图
CREATE OR REPLACE VIEW v_error_analysis AS
SELECT
  game_id,
  environment,
  event_date,
  error_type,
  countMerge(errors) AS errors,
  uniqMerge(users) AS users,
  sumMerge(fatal_errors) AS fatal_errors
FROM mv_error_analysis
GROUP BY game_id, environment, event_date, error_type;