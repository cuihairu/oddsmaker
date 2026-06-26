-- Crash/Error 聚合视图（P4.3）
-- 基于 events 表 event_type='error' 的事件做影响面统计和趋势分析。
-- 堆栈哈希分组用 props_json.crash_hash（由 SDK 或 Gateway 计算），缺失时回退到 event_name。

-- 按日/版本/平台的崩溃影响面
CREATE OR REPLACE VIEW v_crash_by_day AS
SELECT
  game_id,
  environment,
  event_date,
  app_version,
  platform,
  country,
  event_name AS crash_type,
  count() AS crashes,
  uniqExact(device_id) AS affected_devices,
  uniqExactIf(user_id, user_id != '') AS affected_users
FROM events
WHERE event_type = 'error'
GROUP BY game_id, environment, event_date, app_version, platform, country, event_name;

-- 按堆栈哈希分组的 Top Crashes（用于崩溃分组排行）
-- crash_hash 来自 SDK（props_json.crash_hash）；缺失时用 event_name 作为分组键
CREATE OR REPLACE VIEW v_crash_top_groups AS
SELECT
  game_id,
  environment,
  coalesce(nullIf(JSONExtractString(props_json, 'crash_hash'), ''), event_name) AS crash_group,
  any(JSONExtractString(props_json, 'crash_message')) AS sample_message,
  count() AS occurrences,
  uniqExact(device_id) AS affected_devices,
  min(event_date) AS first_seen,
  max(event_date) AS last_seen
FROM events
WHERE event_type = 'error'
GROUP BY game_id, environment, crash_group
ORDER BY occurrences DESC;

-- 崩溃率（crash 设备数 / 活跃设备数）按版本
-- 分母用当日活跃设备数（所有事件类型），分子用崩溃设备数
CREATE OR REPLACE VIEW v_crash_rate_by_version AS
SELECT
  crash.game_id,
  crash.environment,
  crash.event_date,
  crash.app_version,
  crash.affected_devices AS crash_devices,
  active.active_devices,
  if(active.active_devices > 0, crash.affected_devices / active.active_devices, 0) AS crash_rate
FROM
  (SELECT game_id, environment, event_date, app_version,
          uniqExact(device_id) AS affected_devices
   FROM events
   WHERE event_type = 'error'
   GROUP BY game_id, environment, event_date, app_version) AS crash
INNER JOIN
  (SELECT game_id, environment, event_date, app_version,
          uniqExact(device_id) AS active_devices
   FROM events
   GROUP BY game_id, environment, event_date, app_version) AS active
  ON crash.game_id = active.game_id
    AND crash.environment = active.environment
    AND crash.event_date = active.event_date
    AND crash.app_version = active.app_version;

-- 说明：符号化（dSYM/Proguard/source map）需要专门的符号化服务，
-- 不在 ClickHouse 视图范围。SDK 上报的 stack_trace 应该是原始地址，
-- 符号化服务消费后回填 symbolicated_stack 到 props 或单独表。
