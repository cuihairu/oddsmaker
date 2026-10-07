-- B8 实验平台形式化：experiments 补齐 Audience/Guardrail/Decision/Variants/Allocation 显式字段
-- audience_segment_id 引用 segments(id)（V0.9.12）；status 枚举化（DRAFT/LIVE/PAUSED/ENDED）
-- 旧值映射：draft→DRAFT、running/live→LIVE、paused→PAUSED、ended→ENDED（幂等 CASE，既有行随迁移刷新）

ALTER TABLE experiments ADD COLUMN IF NOT EXISTS audience_segment_id VARCHAR(64);
ALTER TABLE experiments ADD COLUMN IF NOT EXISTS variants_json TEXT;
ALTER TABLE experiments ADD COLUMN IF NOT EXISTS allocation_info TEXT;
ALTER TABLE experiments ADD COLUMN IF NOT EXISTS guardrails_json TEXT;
ALTER TABLE experiments ADD COLUMN IF NOT EXISTS decision_json TEXT;

-- status 旧小写值 → 枚举名（running→LIVE 为唯一改名项；新值经 CASE 幂等映射自身）
UPDATE experiments SET status = CASE LOWER(status)
    WHEN 'draft' THEN 'DRAFT'
    WHEN 'running' THEN 'LIVE'
    WHEN 'live' THEN 'LIVE'
    WHEN 'paused' THEN 'PAUSED'
    WHEN 'ended' THEN 'ENDED'
    ELSE UPPER(status)
END;

ALTER TABLE experiments ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE experiments ADD CONSTRAINT fk_experiments_audience_segment FOREIGN KEY (audience_segment_id) REFERENCES segments(id);

CREATE INDEX IF NOT EXISTS idx_experiments_audience_segment_id ON experiments(audience_segment_id);
