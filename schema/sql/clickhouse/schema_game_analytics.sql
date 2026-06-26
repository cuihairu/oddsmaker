-- Game analytics views built on top of the raw `events` table.
-- These views expose game-native datasets for progression, economy,
-- monetization, and health using the v1 game_id + environment contract.

CREATE OR REPLACE VIEW v_level_progress_events AS
SELECT
  game_id,
  environment,
  event_date,
  ts_server,
  if(player_id != '', player_id, if(user_id != '', user_id, device_id)) AS uid,
  event_name,
  coalesce(nullIf(level_id, ''), nullIf(JSONExtractString(props_json, 'level_id'), '')) AS level_id,
  coalesce(nullIf(game_mode, ''), nullIf(JSONExtractString(props_json, 'game_mode'), '')) AS game_mode,
  toUInt32(ifNull(JSONExtract(props_json, 'attempt', 'Nullable(UInt64)'), 0)) AS attempt,
  toUInt32(ifNull(JSONExtract(props_json, 'duration_sec', 'Nullable(UInt64)'), 0)) AS duration_sec,
  ifNull(JSONExtract(props_json, 'score', 'Nullable(Int64)'), 0) AS score,
  JSONExtractString(props_json, 'fail_reason') AS fail_reason
FROM events
WHERE event_name IN ('level_start', 'level_fail', 'level_complete');

CREATE OR REPLACE VIEW v_level_progress_daily AS
SELECT
  game_id,
  environment,
  event_date,
  level_id,
  game_mode,
  countIf(event_name = 'level_start') AS starts,
  countIf(event_name = 'level_fail') AS fails,
  countIf(event_name = 'level_complete') AS completes,
  uniqExactIf(uid, event_name = 'level_start') AS starters,
  uniqExactIf(uid, event_name = 'level_complete') AS completers,
  round(completes / nullIf(starts, 0), 4) AS completion_rate,
  avgIf(duration_sec, duration_sec > 0 AND event_name = 'level_complete') AS avg_complete_duration_sec,
  avgIf(attempt, attempt > 0 AND event_name = 'level_complete') AS avg_attempts_to_complete
FROM v_level_progress_events
GROUP BY game_id, environment, event_date, level_id, game_mode;

CREATE OR REPLACE VIEW v_economy_flows AS
SELECT
  game_id,
  environment,
  event_date,
  ts_server,
  if(player_id != '', player_id, if(user_id != '', user_id, device_id)) AS uid,
  event_name,
  coalesce(nullIf(resource_id, ''), nullIf(virtual_currency, ''), nullIf(JSONExtractString(props_json, 'currency_code'), '')) AS currency_code,
  if(resource_amount != 0, toFloat64(resource_amount), if(virtual_amount != 0, toFloat64(virtual_amount), ifNull(JSONExtract(props_json, 'amount', 'Nullable(Float64)'), 0))) AS amount,
  JSONExtractString(props_json, 'source') AS source,
  JSONExtractString(props_json, 'sink') AS sink,
  JSONExtractString(props_json, 'reason') AS reason,
  ifNull(JSONExtract(props_json, 'balance_after', 'Nullable(Float64)'), 0) AS balance_after
FROM events
WHERE event_name IN ('currency_source', 'currency_sink');

CREATE OR REPLACE VIEW v_economy_daily AS
SELECT
  game_id,
  environment,
  event_date,
  currency_code,
  sumIf(amount, event_name = 'currency_source') AS produced_amount,
  sumIf(amount, event_name = 'currency_sink') AS consumed_amount,
  sumIf(amount, event_name = 'currency_source') - sumIf(amount, event_name = 'currency_sink') AS net_amount,
  uniqExact(uid) AS affected_users
FROM v_economy_flows
GROUP BY game_id, environment, event_date, currency_code;

CREATE OR REPLACE VIEW v_monetization_events AS
SELECT
  game_id,
  environment,
  event_date,
  ts_server,
  if(player_id != '', player_id, if(user_id != '', user_id, device_id)) AS uid,
  event_name,
  revenue_amount,
  coalesce(nullIf(toString(revenue_currency), ''), nullIf(JSONExtractString(props_json, 'currency'), '')) AS revenue_currency,
  coalesce(nullIf(order_id, ''), nullIf(JSONExtractString(props_json, 'order_id'), '')) AS order_id,
  coalesce(nullIf(product_id, ''), nullIf(JSONExtractString(props_json, 'product_id'), '')) AS product_id,
  JSONExtractString(props_json, 'store') AS store,
  coalesce(nullIf(ad_placement, ''), nullIf(JSONExtractString(props_json, 'placement_id'), '')) AS placement_id,
  coalesce(nullIf(ad_network, ''), nullIf(JSONExtractString(props_json, 'network'), '')) AS network,
  coalesce(nullIf(ad_format, ''), nullIf(JSONExtractString(props_json, 'ad_format'), '')) AS ad_format
FROM events
WHERE event_name IN ('revenue', 'iap_order', 'webshop_order', 'ad_impression');

CREATE OR REPLACE VIEW v_monetization_daily AS
SELECT
  game_id,
  environment,
  event_date,
  event_name AS monetization_type,
  revenue_currency,
  count() AS orders_or_impressions,
  uniqExact(uid) AS paying_or_viewing_users,
  sum(revenue_amount) AS revenue,
  avgIf(revenue_amount, revenue_amount > 0) AS avg_revenue_per_event
FROM v_monetization_events
GROUP BY game_id, environment, event_date, monetization_type, revenue_currency;

CREATE OR REPLACE VIEW v_tech_health_daily AS
SELECT
  game_id,
  environment,
  event_date,
  uniqExact(if(player_id != '', player_id, if(user_id != '', user_id, device_id))) AS active_users,
  countIf(event_name = 'crash') AS crashes,
  uniqExactIf(if(player_id != '', player_id, if(user_id != '', user_id, device_id)), event_name = 'crash') AS crashed_users,
  countIf(event_name = 'fps_drop') AS fps_drop_events,
  avgIf(ifNull(JSONExtract(props_json, 'fps', 'Nullable(Float64)'), 0), event_name = 'fps_drop') AS avg_reported_fps_on_drop,
  countIf(event_name = 'network_timeout') AS network_timeouts,
  round(1 - crashed_users / nullIf(active_users, 0), 4) AS crash_free_user_rate
FROM events
GROUP BY game_id, environment, event_date;
