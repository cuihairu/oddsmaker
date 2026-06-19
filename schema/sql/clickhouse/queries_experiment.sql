-- 实验曝光（每日、按 variant）
CREATE OR REPLACE VIEW v_exp_exposures_by_day AS
SELECT game_id, environment, toDate(ts_server) AS event_date,
       JSONExtractString(props_json,'exp') AS exp,
       JSONExtractString(props_json,'variant') AS variant,
       count() AS exposures,
       uniqExact(if(user_id!='',user_id,device_id)) AS users
FROM events
WHERE event_name = 'experiment_exposure'
GROUP BY game_id, environment, event_date, exp, variant;

-- 曝光（含常用维度）
CREATE OR REPLACE VIEW v_exp_exposures_by_day_dim AS
SELECT game_id, environment, toDate(ts_server) AS event_date,
       JSONExtractString(props_json,'exp') AS exp,
       JSONExtractString(props_json,'variant') AS variant,
       platform, app_version, country,
       count() AS exposures,
       uniqExact(if(user_id!='',user_id,device_id)) AS users
FROM events
WHERE event_name = 'experiment_exposure'
GROUP BY game_id, environment, event_date, exp, variant, platform, app_version, country;

-- 示例：转化（以 level_complete 为 primary），按曝光当日 + 同日转化率（演示用）
CREATE OR REPLACE VIEW v_exp_conversion_same_day AS
WITH exp_users AS (
  SELECT game_id, environment, toDate(ts_server) AS d, JSONExtractString(props_json,'exp') AS exp,
         JSONExtractString(props_json,'variant') AS variant,
         groupUniqArray(if(user_id!='',user_id,device_id)) AS users
  FROM events
  WHERE event_name='experiment_exposure'
  GROUP BY game_id, environment, d, exp, variant
),
conversions AS (
  SELECT game_id, environment, toDate(ts_server) AS d, if(user_id!='',user_id,device_id) AS uid,
         count() AS cnt
  FROM events
  WHERE event_name='level_complete'
  GROUP BY game_id, environment, d, uid
)
SELECT e.game_id, e.environment, e.d AS event_date, e.exp, e.variant,
       arraySize(e.users) AS exposed_users,
       countIf(c.uid IN e.users) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr
FROM exp_users e
LEFT JOIN conversions c ON c.game_id=e.game_id AND c.environment=e.environment AND c.d=e.d
GROUP BY e.game_id, e.environment, e.d, e.exp, e.variant;

-- 曝光明细视图（用户级）
CREATE OR REPLACE VIEW v_exp_exposure_users AS
SELECT game_id,
       environment,
       JSONExtractString(props_json,'exp') AS exp,
        JSONExtractString(props_json,'variant') AS variant,
        if(user_id!='',user_id,device_id) AS uid,
        ts_server AS expose_ts
FROM events
WHERE event_name='experiment_exposure';

-- 24小时转化率（以首次 level_complete 为准）
CREATE OR REPLACE VIEW v_exp_conversion_24h AS
WITH conv AS (
  SELECT game_id, environment, if(user_id!='',user_id,device_id) AS uid, min(ts_server) AS conv_ts
  FROM events WHERE event_name='level_complete'
  GROUP BY game_id, environment, uid
)
SELECT e.game_id, e.environment, toDate(e.expose_ts) AS exposure_date, e.exp, e.variant,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 24 HOUR) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_24h
FROM v_exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant;

-- 24小时转化率（含维度）
CREATE OR REPLACE VIEW v_exp_conversion_24h_dim AS
WITH conv AS (
  SELECT game_id, environment, if(user_id!='',user_id,device_id) AS uid, min(ts_server) AS conv_ts
  FROM events WHERE event_name='level_complete'
  GROUP BY game_id, environment, uid
)
SELECT e.game_id, e.environment, toDate(e.expose_ts) AS exposure_date, e.exp, e.variant,
       e.platform, e.app_version, e.country,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 24 HOUR) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_24h
FROM v_exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant, e.platform, e.app_version, e.country;

-- 7天转化率（以首次 level_complete 为准）
CREATE OR REPLACE VIEW v_exp_conversion_7d AS
WITH conv AS (
  SELECT game_id, environment, if(user_id!='',user_id,device_id) AS uid, min(ts_server) AS conv_ts
  FROM events WHERE event_name='level_complete'
  GROUP BY game_id, environment, uid
)
SELECT e.game_id, e.environment, toDate(e.expose_ts) AS exposure_date, e.exp, e.variant,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 7 DAY) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_7d
FROM v_exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant;

-- 7天转化率（含维度）
CREATE OR REPLACE VIEW v_exp_conversion_7d_dim AS
WITH conv AS (
  SELECT game_id, environment, if(user_id!='',user_id,device_id) AS uid, min(ts_server) AS conv_ts
  FROM events WHERE event_name='level_complete'
  GROUP BY game_id, environment, uid
)
SELECT e.game_id, e.environment, toDate(e.expose_ts) AS exposure_date, e.exp, e.variant,
       e.platform, e.app_version, e.country,
       count() AS exposed_users,
       countIf(c.conv_ts >= e.expose_ts AND c.conv_ts <= e.expose_ts + INTERVAL 7 DAY) AS converted_users,
       round(converted_users / nullIf(exposed_users,0), 4) AS cr_7d
FROM v_exp_exposure_users e
LEFT JOIN conv c ON c.game_id=e.game_id AND c.environment=e.environment AND c.uid=e.uid
GROUP BY e.game_id, e.environment, exposure_date, e.exp, e.variant, e.platform, e.app_version, e.country;
