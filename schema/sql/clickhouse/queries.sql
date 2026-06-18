-- 统计：每日事件量（按租户与应用）
SELECT tenant_id, app_id, event_date, event_name, countMerge(evts) AS events
FROM mv_events_by_day
GROUP BY tenant_id, app_id, event_date, event_name
ORDER BY tenant_id, app_id, event_date;

-- 统计：每日活跃用户（DAU）
SELECT tenant_id, app_id, event_date, uniqExactMerge(dau) AS dau
FROM mv_dau
GROUP BY tenant_id, app_id, event_date
ORDER BY tenant_id, app_id, event_date;

-- 示例：D1 留存（简化版，以首次事件日为 cohort）
WITH first_day AS (
  SELECT tenant_id, app_id, if(user_id!='', user_id, device_id) AS uid,
         min(event_date) AS d0
  FROM events
  GROUP BY tenant_id, app_id, uid
),
activity AS (
  SELECT tenant_id, app_id, if(user_id!='', user_id, device_id) AS uid,
         event_date AS d
  FROM events
  GROUP BY tenant_id, app_id, uid, d
)
SELECT f.tenant_id, f.app_id, f.d0 AS cohort_day,
       countIf(a.d = f.d0) AS d0_users,
       countIf(a.d = addDays(f.d0, 1)) AS d1_return,
       round(d1_return / nullIf(d0_users, 0), 4) AS d1_retention
FROM first_day f
LEFT JOIN activity a USING (tenant_id, app_id, uid)
GROUP BY f.tenant_id, f.app_id, f.d0
ORDER BY f.tenant_id, f.app_id, f.d0;

-- 示例：漏斗（两步 level_start -> level_complete）
WITH starts AS (
  SELECT tenant_id, app_id, if(user_id!='', user_id, device_id) AS uid, min(ts_server) AS t
  FROM events WHERE event_name = 'level_start'
  GROUP BY tenant_id, app_id, uid
),
completes AS (
  SELECT tenant_id, app_id, if(user_id!='', user_id, device_id) AS uid, min(ts_server) AS t
  FROM events WHERE event_name = 'level_complete'
  GROUP BY tenant_id, app_id, uid
)
SELECT s.tenant_id, s.app_id,
       count() AS started,
       countIf(c.t >= s.t) AS completed,
       round(completed / nullIf(started, 0), 4) AS cr
FROM starts s
LEFT JOIN completes c USING (tenant_id, app_id, uid)
GROUP BY s.tenant_id, s.app_id
ORDER BY s.tenant_id, s.app_id;
