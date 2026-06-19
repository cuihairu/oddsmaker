-- 统计：每日事件量（按游戏与环境）
SELECT game_id, environment, event_date, event_type, event_name, countMerge(evts) AS events
FROM mv_events_by_day
GROUP BY game_id, environment, event_date, event_type, event_name
ORDER BY game_id, environment, event_date;

-- 统计：每日活跃用户（DAU）
SELECT game_id, environment, event_date, uniqExactMerge(dau) AS dau
FROM mv_dau
GROUP BY game_id, environment, event_date
ORDER BY game_id, environment, event_date;

-- 示例：D1 留存（简化版，以首次事件日为 cohort）
WITH first_day AS (
  SELECT game_id, environment, if(player_id!='', player_id, if(user_id!='', user_id, device_id)) AS uid,
         min(event_date) AS d0
  FROM events
  GROUP BY game_id, environment, uid
),
activity AS (
  SELECT game_id, environment, if(player_id!='', player_id, if(user_id!='', user_id, device_id)) AS uid,
         event_date AS d
  FROM events
  GROUP BY game_id, environment, uid, d
)
SELECT f.game_id, f.environment, f.d0 AS cohort_day,
       countIf(a.d = f.d0) AS d0_users,
       countIf(a.d = addDays(f.d0, 1)) AS d1_return,
       round(d1_return / nullIf(d0_users, 0), 4) AS d1_retention
FROM first_day f
LEFT JOIN activity a USING (game_id, environment, uid)
GROUP BY f.game_id, f.environment, f.d0
ORDER BY f.game_id, f.environment, f.d0;

-- 示例：漏斗（两步 progression:level:start -> progression:level:complete）
WITH starts AS (
  SELECT game_id, environment, if(player_id!='', player_id, if(user_id!='', user_id, device_id)) AS uid, min(ts_server) AS t
  FROM events WHERE event_name = 'progression:level:start'
  GROUP BY game_id, environment, uid
),
completes AS (
  SELECT game_id, environment, if(player_id!='', player_id, if(user_id!='', user_id, device_id)) AS uid, min(ts_server) AS t
  FROM events WHERE event_name = 'progression:level:complete'
  GROUP BY game_id, environment, uid
)
SELECT s.game_id, s.environment,
       count() AS started,
       countIf(c.t >= s.t) AS completed,
       round(completed / nullIf(started, 0), 4) AS cr
FROM starts s
LEFT JOIN completes c USING (game_id, environment, uid)
GROUP BY s.game_id, s.environment
ORDER BY s.game_id, s.environment;

-- 示例：高风险事件趋势
SELECT game_id, environment, toDate(ts) AS event_date, risk_type, severity, count() AS risks
FROM risk_events
GROUP BY game_id, environment, event_date, risk_type, severity
ORDER BY game_id, environment, event_date, severity;
