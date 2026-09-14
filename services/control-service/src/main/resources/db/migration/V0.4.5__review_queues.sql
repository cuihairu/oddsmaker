-- Review Queues Table
-- Phase 4: Review Queue System - Human review workflow for risk cases

-- Review Queues table
CREATE TABLE review_queues (
    id VARCHAR(32) PRIMARY KEY,
    risk_case_id VARCHAR(32) NOT NULL,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    case_number VARCHAR(100),
    target_type VARCHAR(50),
    target_id VARCHAR(200),
    target_name VARCHAR(200),
    risk_level VARCHAR(20),
    risk_score INTEGER,
    action_type VARCHAR(20),
    priority INTEGER DEFAULT 50,
    queue_type VARCHAR(50) DEFAULT 'default',
    category VARCHAR(50),
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    assigned_to VARCHAR(64),
    assigned_at TIMESTAMP,
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMP,
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    disposition VARCHAR(50),
    resolution TEXT,
    escalated BOOLEAN DEFAULT FALSE,
    escalated_to VARCHAR(64),
    escalated_at TIMESTAMP,
    escalation_reason VARCHAR(500),
    sla_due_at TIMESTAMP,
    sla_breached BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    FOREIGN KEY (risk_case_id) REFERENCES risk_cases(id),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_review_queues_risk_case_id ON review_queues(risk_case_id);
CREATE INDEX idx_review_queues_game_id ON review_queues(game_id);
CREATE INDEX idx_review_queues_environment_id ON review_queues(environment_id);
CREATE INDEX idx_review_queues_review_status ON review_queues(review_status);
CREATE INDEX idx_review_queues_priority ON review_queues(priority DESC);
CREATE INDEX idx_review_queues_queue_type ON review_queues(queue_type);
CREATE INDEX idx_review_queues_category ON review_queues(category);
CREATE INDEX idx_review_queues_assigned_to ON review_queues(assigned_to);
CREATE INDEX idx_review_queues_claimed_by ON review_queues(claimed_by);
CREATE INDEX idx_review_queues_sla_due_at ON review_queues(sla_due_at);
CREATE INDEX idx_review_queues_created_at ON review_queues(created_at ASC);

-- Unique constraint on risk case (one queue item per case)
CREATE UNIQUE INDEX idx_review_queues_risk_case ON review_queues(risk_case_id);

-- Composite index for pending items by priority
CREATE INDEX idx_review_queues_pending_priority ON review_queues(game_id, priority DESC, created_at ASC) WHERE review_status IN ('PENDING', 'ASSIGNED', 'CLAIMED', 'IN_REVIEW');

-- Index for SLA monitoring
CREATE INDEX idx_review_queues_sla_breach ON review_queues(sla_due_at, review_status) WHERE review_status NOT IN ('COMPLETED', 'CANCELLED');

