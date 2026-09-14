-- ClickHouse DDL for Configurable Funnels
-- 可配置漏斗分析表结构

-- 可配置漏斗结果表
CREATE TABLE IF NOT EXISTS funnels_configurable
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  funnel_id String,
  event_date Date,
  step UInt32,
  step_name String,
  users UInt64,
  conversion_rate Float64
)
ENGINE = SummingMergeTree()
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, funnel_id, event_date, step);

-- 漏斗每日汇总物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_funnel_daily_summary
ENGINE = AggregatingMergeTree()
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, funnel_id, event_date, step)
AS
SELECT
  game_id,
  environment,
  funnel_id,
  event_date,
  step,
  step_name,
  sumState(users) AS total_users,
  avgState(conversion_rate) AS avg_conversion_rate
FROM funnels_configurable
GROUP BY game_id, environment, funnel_id, event_date, step, step_name;

-- 漏斗每日汇总视图
CREATE OR REPLACE VIEW v_funnel_daily_summary AS
SELECT
  game_id,
  environment,
  funnel_id,
  event_date,
  step,
  step_name,
  sumMerge(total_users) AS total_users,
  avgMerge(avg_conversion_rate) AS avg_conversion_rate
FROM mv_funnel_daily_summary
GROUP BY game_id, environment, funnel_id, event_date, step, step_name;

-- 漏斗转化率视图
CREATE OR REPLACE VIEW v_funnel_conversion AS
SELECT
  f1.game_id,
  f1.environment,
  f1.funnel_id,
  f1.event_date,
  f1.step AS from_step,
  f1.step_name AS from_step_name,
  f2.step AS to_step,
  f2.step_name AS to_step_name,
  f1.total_users AS from_users,
  f2.total_users AS to_users,
  if(f1.total_users > 0, (f2.total_users / f1.total_users) * 100, 0) AS conversion_rate
FROM v_funnel_daily_summary f1
JOIN v_funnel_daily_summary f2 ON f1.game_id = f2.game_id 
  AND f1.environment = f2.environment 
  AND f1.funnel_id = f2.funnel_id 
  AND f1.event_date = f2.event_date
  AND f2.step = f1.step + 1;

-- 漏斗趋势视图
CREATE OR REPLACE VIEW v_funnel_trend AS
SELECT
  game_id,
  environment,
  funnel_id,
  event_date,
  step,
  step_name,
  total_users,
  avg_conversion_rate,
  lagInFrame(total_users, 1) OVER (
    PARTITION BY game_id, environment, funnel_id, step 
    ORDER BY event_date
  ) AS prev_day_users,
  if(
    lagInFrame(total_users, 1) OVER (
      PARTITION BY game_id, environment, funnel_id, step 
      ORDER BY event_date
    ) > 0,
    ((total_users - lagInFrame(total_users, 1) OVER (
      PARTITION BY game_id, environment, funnel_id, step 
      ORDER BY event_date
    )) / lagInFrame(total_users, 1) OVER (
      PARTITION BY game_id, environment, funnel_id, step 
      ORDER BY event_date
    )) * 100,
    0
  ) AS day_over_day_change
FROM v_funnel_daily_summary;

-- 漏斗步骤对比视图
-- 注意：这里不能用 INNER JOIN ... AND f2.step > f1.step（跨表列比较），
-- ClickHouse 24.8 默认拒绝该写法（INVALID_JOIN_ON_EXPRESSION），initdb 会在此中断；
-- 等价改写为 CROSS JOIN + WHERE
CREATE OR REPLACE VIEW v_funnel_step_comparison AS
SELECT
  f1.game_id,
  f1.environment,
  f1.funnel_id,
  f1.event_date,
  f1.step AS step_a,
  f1.step_name AS step_a_name,
  f1.total_users AS step_a_users,
  f2.step AS step_b,
  f2.step_name AS step_b_name,
  f2.total_users AS step_b_users,
  if(f1.total_users > 0, (f2.total_users / f1.total_users) * 100, 0) AS conversion_rate
FROM v_funnel_daily_summary f1
CROSS JOIN v_funnel_daily_summary f2
WHERE f1.game_id = f2.game_id
  AND f1.environment = f2.environment
  AND f1.funnel_id = f2.funnel_id
  AND f1.event_date = f2.event_date
  AND f2.step > f1.step;

-- 漏斗用户分布视图
CREATE OR REPLACE VIEW v_funnel_user_distribution AS
SELECT
  game_id,
  environment,
  funnel_id,
  event_date,
  step,
  step_name,
  total_users,
  sum(total_users) OVER (
    PARTITION BY game_id, environment, funnel_id, event_date
  ) AS total_funnel_users,
  if(
    sum(total_users) OVER (
      PARTITION BY game_id, environment, funnel_id, event_date
    ) > 0,
    (total_users / sum(total_users) OVER (
      PARTITION BY game_id, environment, funnel_id, event_date
    )) * 100,
    0
  ) AS user_percentage
FROM v_funnel_daily_summary;

-- 漏斗完成率视图
-- 原写法在 JOIN ON 里用相关子查询（f3.game_id = f1.game_id 外层列引用），
-- ClickHouse 24.8 不支持（UNSUPPORTED_METHOD），initdb 同样会在此中断；
-- 等价改写为窗口函数算 max(step) 再聚合
CREATE OR REPLACE VIEW v_funnel_completion AS
SELECT
  game_id,
  environment,
  funnel_id,
  event_date,
  sumIf(total_users, step = 1) AS started_users,
  sumIf(total_users, step = max_step) AS completed_users,
  if(started_users > 0, (completed_users / started_users) * 100, 0) AS completion_rate
FROM (
  SELECT
    game_id, environment, funnel_id, event_date, step, total_users,
    max(step) OVER (PARTITION BY game_id, environment, funnel_id, event_date) AS max_step
  FROM v_funnel_daily_summary
)
GROUP BY game_id, environment, funnel_id, event_date;