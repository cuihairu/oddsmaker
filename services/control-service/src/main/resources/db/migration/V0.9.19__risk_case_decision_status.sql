-- B6 判定状态机（计划书 §5.2）：risk_cases 落判定状态列。
-- 状态机 OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED：分带单向推进——目标带高于当前带才可流转
-- （跨带前进合法），同带横向（REVIEW→ALERT、THROTTLE→BLOCK）、回退（BLOCK→REVIEW）、终态复活（RESOLVED→*）非法；
-- 同值幂等。判定由 RiskEventConsumer.decide 产出（Decision 决定动作，BLOCK 信任门槛 fail-closed），
-- 流转合法性由 RiskCaseEntity.canTransition 把关（合法/非法表入 RiskDecisionStateMachineTest）。
-- 存量行回填 OPEN（历史案件无判定语义，由后续人工流转推进）。
ALTER TABLE risk_cases ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'OPEN';

-- 判定状态检索（案件按判定带过滤）
CREATE INDEX IF NOT EXISTS idx_risk_cases_decision_status ON risk_cases(status);
