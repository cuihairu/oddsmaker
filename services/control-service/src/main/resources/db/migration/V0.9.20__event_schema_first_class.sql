-- B7 EventSchema 一等资源化（计划书 §2.3/§4.4）：tracking_plans 补齐一等资源字段——
-- compatibility 版本兼容策略（NONE/BACKWARD/FORWARD/FULL，publish 时对同 game+env 基线 ACTIVE
-- 版本做事件集兼容检查）；pii_policy 事件级 PII 策略（JSON：{email,phone,ip}，优先级
-- 环境级 Schema > ApiKey > 网关默认）；retention_days 事件数据保留天数覆盖；
-- sampling_rate 采样率覆盖；owner_id 归属（人/组）。既有 strictness/rejectUnknownEvents/
-- enableAutoValidation 语义不变；API 与 UI 以 EventSchema 命名暴露（/api/games/{gameId}/schemas）。

ALTER TABLE tracking_plans ADD COLUMN IF NOT EXISTS compatibility VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE tracking_plans ADD COLUMN IF NOT EXISTS pii_policy TEXT;
ALTER TABLE tracking_plans ADD COLUMN IF NOT EXISTS retention_days INTEGER;
ALTER TABLE tracking_plans ADD COLUMN IF NOT EXISTS sampling_rate DECIMAL(3,2);
ALTER TABLE tracking_plans ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
