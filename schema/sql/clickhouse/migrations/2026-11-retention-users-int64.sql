-- 迁移：retention_daily / retention_rolling 的 users UInt64 → Int64（2026-11，审核发现 N-Day 行多发）
--
-- 背景：RetentionJob 队列校正（更早首日事件，day < first）会重推 N-Day/Rolling，且按 cohort
-- 校正前移逐级重算。被取代 cohort（中间校正）的已发行行在 UInt64 下无法撤回（不支持负数），
-- 多级校正场景（day7/8/9→day6→day1）会在 retention_daily/retention_rolling 累积虚增留存行。
-- 修复：新版 job 以 users=-1 撤回行精确对消旧 cohort 全部已发行（每条正发行恰对应一个
-- seen_d_<n>/seen_r_<n> 标记或 d=0 规模行，故可精确撤回）；users 列须为有符号类型。
--
-- 注意：本脚本一次性执行（非幂等），重复执行会重复累计历史行；仅适用于已有旧结构表的部署。
-- 新部署直接执行 schema/sql/clickhouse/analytics.sql 即得新结构，跳过本脚本。
--
-- 顺序要求（务必遵守）：
--   1. 停掉旧版 retention-job（旧作业 UInt64 语义对新表无冲突，但新作业 Int64 撤回行对旧表会溢出/报错）
--   2. 执行本脚本完成换表
--   3. 部署/重启新版 retention-job
-- 全程窗口内留存报表短暂无数据（历史数据保留，口径不变）。
--
-- 历史数据处理：旧表行复制进新表（users 数值原样保留，UInt64 值在 Int64 范围内无损）。
-- 旧版作业历史产生的虚增行无法追溯重建（需原始事件重放），本迁移只保证新行为不再累积；
-- 生产如需清历史虚增，另做 cohort 全量重算（外部条件：原始事件 + 完整重放窗口）。

-- 1) 建新表（结构必须与 schema/sql/clickhouse/analytics.sql 中两表一致，users 为 Int64）
CREATE TABLE IF NOT EXISTS retention_daily_new
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  subject_id String,
  cohort_date Date,
  d UInt16,
  users Int64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, d, subject_id);

CREATE TABLE IF NOT EXISTS retention_rolling_new
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  cohort_date Date,
  n UInt16,
  users Int64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, n);

-- 2) 迁移历史数据（users 值原样复制；Int64 表示范围覆盖 UInt64 实际留存计数）
INSERT INTO retention_daily_new (game_id, environment, subject_id, cohort_date, d, users)
SELECT game_id, environment, subject_id, cohort_date, d, users
FROM retention_daily
WHERE EXISTS (SELECT 1 FROM system.tables
              WHERE database = currentDatabase() AND name = 'retention_daily');

INSERT INTO retention_rolling_new (game_id, environment, cohort_date, n, users)
SELECT game_id, environment, cohort_date, n, users
FROM retention_rolling
WHERE EXISTS (SELECT 1 FROM system.tables
              WHERE database = currentDatabase() AND name = 'retention_rolling');

-- 3) 原子换名（ClickHouse RENAME 为原子操作，同一条语句内完成交换）
RENAME TABLE retention_daily TO retention_daily_old, retention_daily_new TO retention_daily;
RENAME TABLE retention_rolling TO retention_rolling_old, retention_rolling_new TO retention_rolling;

-- 4) 验证新版 retention-job 写入正常、留存报表口径比对无误后，清理旧表（建议观察 1-2 周）
-- DROP TABLE IF EXISTS retention_daily_old;
-- DROP TABLE IF EXISTS retention_rolling_old;
