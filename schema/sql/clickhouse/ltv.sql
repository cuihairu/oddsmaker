-- LTV (Lifetime Value) 物化视图与查询
-- 基于 events 表的 revenue_amount，按 cohort（用户首次出现日）累加收入。
-- 与 Flink 分析作业解耦：LTV 是 OLAP 聚合，让 ClickHouse 做。

-- 用户首次出现日（cohort 定义）
CREATE OR REPLACE VIEW v_user_first_seen AS
SELECT
  game_id,
  environment,
  user_id,
  min(event_date) AS cohort_date
FROM events
WHERE user_id != ''
GROUP BY game_id, environment, user_id;

-- 按 cohort 日 + 事件日龄（age_day）的 LTV 明细
-- age_day = 0 表示注册当天，age_day = 6 表示注册后第 7 天
CREATE OR REPLACE VIEW v_ltv_by_cohort_day AS
SELECT
  f.game_id,
  f.environment,
  f.cohort_date,
  dateDiff('day', f.cohort_date, e.event_date) AS age_day,
  sum(e.revenue_amount) AS revenue,
  count(DISTINCT e.user_id) AS payers,
  uniqExactIf(e.order_id, e.order_id != '') AS orders
FROM events AS e
INNER JOIN v_user_first_seen AS f
  ON e.game_id = f.game_id
    AND e.environment = f.environment
    AND e.user_id = f.user_id
WHERE e.revenue_amount > 0
GROUP BY f.game_id, f.environment, f.cohort_date, age_day;

-- 按 cohort 日 + 年龄的累计 LTV
-- 查询示例：每个 cohort 的 D7 / D30 LTV（累计收入 / cohort 人数）
-- 注意：分母用 cohort 总人数（非付费），所以需要 v_user_first_seen 统计
--
-- SELECT
--   cohort_date,
--   sumIf(revenue, age_day <= 6)  AS ltv_d7_revenue,
--   sumIf(revenue, age_day <= 29) AS ltv_d30_revenue
-- FROM v_ltv_by_cohort_day
-- GROUP BY cohort_date
-- ORDER BY cohort_date;

-- 广告收入（ad_revenue）单独视图，便于 IAP + Ad 统一分析
CREATE OR REPLACE VIEW v_ad_revenue_by_day AS
SELECT
  game_id,
  environment,
  event_date,
  ad_network,
  ad_format,
  count() AS impressions,
  countIf(event_name = 'ad_click') AS clicks,
  countIf(event_name = 'ad_reward') AS rewards,
  sumIf(revenue_amount, event_name = 'ad_revenue') AS ad_revenue
FROM events
WHERE event_type = 'ad'
GROUP BY game_id, environment, event_date, ad_network, ad_format;

-- 综合 LTV 查询（IAP + Ad），按 cohort 输出 D7/D30 累计 ARPU/ARPPU
-- ARPU = 总收入 / cohort 总注册人数
-- ARPPU = 总收入 / cohort 总付费人数
--
-- SELECT
--   l.cohort_date,
--   uniqExact(f.user_id) AS cohort_size,
--   sumIf(l.revenue, l.age_day <= 6)  AS iap_d7,
--   sumIf(l.revenue, l.age_day <= 29) AS iap_d30,
--   sumIf(l.revenue, l.age_day <= 6) / uniqExact(f.user_id) AS arpu_d7,
--   sumIf(l.revenue, l.age_day <= 6) / sumIf(l.payers, l.age_day <= 6) AS arppu_d7
-- FROM v_ltv_by_cohort_day l
-- ANY LEFT JOIN v_user_first_seen f
--   ON l.game_id = f.game_id AND l.environment = f.environment AND l.cohort_date = f.cohort_date
-- GROUP BY l.cohort_date
-- ORDER BY l.cohort_date;
