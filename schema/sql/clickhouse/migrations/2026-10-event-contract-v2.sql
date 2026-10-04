-- 迁移：events 表增加事件契约 v2 四字段（2026-10，B4 事件契约 v2 增量）
--
-- 幂等：ADD COLUMN IF NOT EXISTS，可重复执行；新部署直接执行 schema.sql 即得新结构，跳过本脚本。
-- 历史行回填口径：event_version=1（历史行即 v1 契约事件，语义精确）；
--   source/trust_level/event_origin 置空串（''=契约 v2 前落库，网关尚未回填权威值；
--   消费方须以 trust_level='HIGH' 精确匹配做信任判定，'' 天然不满足，安全侧收敛）。
--
-- 顺序要求：先执行本迁移，再部署新版 events-enrich-job（新作业 47 列 INSERT 对旧表缺列会报错；
--   旧作业 43 列 INSERT 对新表多列可容忍——新列均有 DEFAULT，滚动发布先迁表后升作业即可）。
--
-- 对应代码：网关 TrustPolicy 权威回填 → Avro 四字段 → Flink events-enrich-job 透传落库。

ALTER TABLE events ADD COLUMN IF NOT EXISTS event_version UInt32 DEFAULT 1 AFTER ad_impression_id;
ALTER TABLE events ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT '' AFTER event_version;
ALTER TABLE events ADD COLUMN IF NOT EXISTS trust_level LowCardinality(String) DEFAULT '' AFTER source;
ALTER TABLE events ADD COLUMN IF NOT EXISTS event_origin LowCardinality(String) DEFAULT '' AFTER trust_level;
