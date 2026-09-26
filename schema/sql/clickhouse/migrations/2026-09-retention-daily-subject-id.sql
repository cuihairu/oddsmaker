-- 迁移：retention_daily 增加 subject_id 主体维度（2026-09，P7-2 分群留存预聚合）
--
-- 注意：本脚本一次性执行（非幂等），重复执行会重复累计历史行；仅适用于已有旧结构表的部署。
-- 新部署直接执行 schema/sql/clickhouse/analytics.sql 即得新结构，跳过本脚本。
--
-- 背景：分群过滤需要按主体（player>user>device）下推 segment_members 成员过滤。
-- SummingMergeTree 的 ORDER BY 键决定同键行折叠求和，subject_id 必须进排序键，
-- 而 ClickHouse 不支持修改既有表的排序键 → 只能重建表。
--
-- 顺序要求（务必遵守）：
--   1. 停掉旧版 retention-job（旧作业 5 列 INSERT 对新表缺列会报错；新作业 6 列 INSERT 对旧表多列也会报错）
--   2. 执行本脚本完成换表
--   3. 部署/重启新版 retention-job
-- 全程窗口内留存报表短暂无数据（历史数据保留，非分群口径不受影响）。
--
-- 历史数据处理：旧表行复制进新表时 subject_id 置空串。
--   - 非分群留存趋势（sum(users)）口径不变，历史 cohort 继续可见；
--   - 分群留存查询带 `subject_id != ''` 过滤，空串历史行不参与成员匹配（预期行为）。

-- 1) 建新表（结构必须与 schema/sql/clickhouse/analytics.sql 中 retention_daily 一致）
CREATE TABLE IF NOT EXISTS retention_daily_new
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  subject_id String,
  cohort_date Date,
  d UInt16,
  users UInt64
)
ENGINE = SummingMergeTree
PARTITION BY (game_id, environment, toYYYYMM(cohort_date))
ORDER BY (game_id, environment, cohort_date, d, subject_id);

-- 2) 迁移历史数据：保留非分群口径，subject_id 置空串（分群查询自动忽略）
INSERT INTO retention_daily_new (game_id, environment, subject_id, cohort_date, d, users)
SELECT game_id, environment, '', cohort_date, d, users
FROM retention_daily
WHERE EXISTS (SELECT 1 FROM system.tables
              WHERE database = currentDatabase() AND name = 'retention_daily');

-- 3) 原子换名（ClickHouse RENAME 为原子操作，同一条语句内完成交换）
RENAME TABLE retention_daily TO retention_daily_old, retention_daily_new TO retention_daily;

-- 4) 验证新版 retention-job 写入正常、留存报表口径比对无误后，清理旧表（建议观察 1-2 周）
-- DROP TABLE IF EXISTS retention_daily_old;
