-- 可配置漏斗断链修复（配套：Flink job 改读 funnel_configs/funnel_steps(funnel_id)）
-- funnel_analysis_id 此前 NOT NULL（V0.3.3），而控制面 FunnelStepEntity 不映射该列——
-- 控制面写步骤在 PG 上必然违反 NOT NULL。放宽为可空后控制面行（funnel_id 非空）与
-- 历史分析行（funnel_analysis_id 非空）共存；FK 保留（PG 外键不约束 NULL）。
ALTER TABLE funnel_steps ALTER COLUMN funnel_analysis_id DROP NOT NULL;
