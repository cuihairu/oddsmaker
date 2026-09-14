-- Risk Management System
-- Phase 4: Risk Control Loop - Risk Rules and Cases

-- Risk Rules table
CREATE TABLE risk_rules (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    category VARCHAR(20) NOT NULL DEFAULT 'BEHAVIOR',
    rule_type VARCHAR(20) NOT NULL DEFAULT 'THRESHOLD',
    rule_conditions TEXT,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    risk_score INTEGER DEFAULT 50,
    action_type VARCHAR(20) NOT NULL DEFAULT 'ALERT',
    action_params TEXT,
    enable_auto_block BOOLEAN DEFAULT FALSE,
    block_duration INTEGER,
    enable_webhook BOOLEAN DEFAULT FALSE,
    webhook_url VARCHAR(500),
    enable_review_queue BOOLEAN DEFAULT TRUE,
    trigger_threshold INTEGER DEFAULT 1,
    time_window_minutes INTEGER DEFAULT 60,
    cooldown_minutes INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    total_triggered_count BIGINT DEFAULT 0,
    total_blocked_count BIGINT DEFAULT 0,
    total_review_count BIGINT DEFAULT 0,
    last_triggered_at TIMESTAMP,
    version VARCHAR(20) DEFAULT '1.0',
    parent_rule_id VARCHAR(32),
    test_mode BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    activated_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id),
    FOREIGN KEY (parent_rule_id) REFERENCES risk_rules(id)
);

CREATE INDEX idx_risk_rules_game_id ON risk_rules(game_id);
CREATE INDEX idx_risk_rules_environment_id ON risk_rules(environment_id);
CREATE INDEX idx_risk_rules_category ON risk_rules(category);
CREATE INDEX idx_risk_rules_risk_level ON risk_rules(risk_level);
CREATE INDEX idx_risk_rules_status ON risk_rules(status);
CREATE INDEX idx_risk_rules_priority ON risk_rules(priority DESC);
CREATE UNIQUE INDEX idx_risk_rules_name ON risk_rules(game_id, name) WHERE deleted_at IS NULL;

-- Risk Cases table
CREATE TABLE risk_cases (
    id VARCHAR(32) PRIMARY KEY,
    risk_rule_id VARCHAR(32) NOT NULL,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    case_number VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    target_name VARCHAR(200),
    trigger_event_id VARCHAR(100),
    trigger_event_type VARCHAR(50),
    trigger_event_name VARCHAR(100),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    risk_score INTEGER DEFAULT 50,
    action_taken VARCHAR(20) NOT NULL DEFAULT 'ALERT',
    action_description VARCHAR(500),
    execution_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    executed_at TIMESTAMP,
    execution_error TEXT,
    evidence_data TEXT,
    context_data TEXT,
    review_status VARCHAR(50),
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    disposition VARCHAR(50),
    unblocked_at TIMESTAMP,
    unblocked_by VARCHAR(64),
    unblock_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    FOREIGN KEY (risk_rule_id) REFERENCES risk_rules(id),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_risk_cases_rule_id ON risk_cases(risk_rule_id);
CREATE INDEX idx_risk_cases_game_id ON risk_cases(game_id);
CREATE INDEX idx_risk_cases_environment_id ON risk_cases(environment_id);
CREATE INDEX idx_risk_cases_target ON risk_cases(target_type, target_id);
CREATE INDEX idx_risk_cases_risk_level ON risk_cases(risk_level);
CREATE INDEX idx_risk_cases_status ON risk_cases(execution_status);
CREATE INDEX idx_risk_cases_review ON risk_cases(review_status);
CREATE INDEX idx_risk_cases_created_at ON risk_cases(created_at DESC);
CREATE UNIQUE INDEX idx_risk_cases_number ON risk_cases(case_number);

-- Index for pending execution
CREATE INDEX idx_risk_cases_pending ON risk_cases(execution_status) WHERE execution_status = 'PENDING';

-- Index for active blocks
CREATE INDEX idx_risk_cases_active_blocks ON risk_cases(action_taken, execution_status, unblocked_at) WHERE action_taken = 'BLOCK' AND execution_status = 'EXECUTED' AND (unblocked_at IS NULL OR unblocked_at < executed_at);

-- Insert default risk rules for common scenarios
-- PG 严格外键：先种 DEFAULT 游戏行（H2 开发库中为手工数据，从未纳入迁移）
INSERT INTO games (id, name, status) VALUES ('DEFAULT', 'Platform Default', 'PUBLISHED') ON CONFLICT (id) DO NOTHING;
INSERT INTO risk_rules (id, game_id, name, display_name, description, category, rule_type, risk_level, risk_score, action_type, priority, status, rule_conditions, trigger_threshold, time_window_minutes) VALUES
-- High frequency events (script behavior)
('rule_high_freq_events', 'DEFAULT', 'high_frequency_events', 'High Frequency Events', 'Detect abnormally high event frequency from single source', 'BEHAVIOR', 'FREQUENCY', 'HIGH', 75, 'BLOCK', 100, 'ACTIVE', '{"event_count": ">1000", "time_window": "1minute"}', 10, 1),

-- Suspicious payment pattern
('rule_suspicious_payment', 'DEFAULT', 'suspicious_payment_pattern', 'Suspicious Payment Pattern', 'Detect unusual payment patterns', 'PAYMENT', 'PATTERN', 'HIGH', 80, 'REVIEW', 90, 'ACTIVE', '{"amount": ">1000", "frequency": ">5", "time_window": "1hour", "different_cards": true}', 3, 60),

-- Multiple accounts from same device
('rule_multi_account_device', 'DEFAULT', 'multiple_accounts_one_device', 'Multiple Accounts One Device', 'Detect multiple accounts from same device', 'ACCOUNT', 'THRESHOLD', 'MEDIUM', 60, 'ALERT', 70, 'ACTIVE', '{"account_count": ">5", "time_window": "1day"}', 6, 1440),

-- IP location changes (impossible travel)
('rule_impossible_travel', 'DEFAULT', 'impossible_travel', 'Impossible Travel', 'Detect rapid location changes', 'NETWORK', 'VELOCITY', 'HIGH', 85, 'REVIEW', 95, 'ACTIVE', '{"location_change_distance_km": ">1000", "time_minutes": "<30"}', 1, 30),

-- Unusual resource spike
('rule_resource_spike', 'DEFAULT', 'unusual_resource_spike', 'Unusual Resource Spike', 'Detect abnormal resource acquisition', 'ECONOMY', 'ANOMALY', 'MEDIUM', 65, 'ALERT', 60, 'ACTIVE', '{"resource_increase": ">10000%", "time_window": "1hour"}', 5, 60),

-- Ad reward abuse
('rule_ad_reward_abuse', 'DEFAULT', 'ad_reward_abuse', 'Ad Reward Abuse', 'Detect ad reward farming', 'AUTOMATION', 'FREQUENCY', 'LOW', 40, 'THROTTLE', 50, 'ACTIVE', '{"ad_impressions": ">100", "reward_claims": ">100", "time_window": "1hour", "conversion_rate": ">95%"}', 20, 60);

-- Note: These are template rules with game_id='DEFAULT'. Actual games will have their own copies with specific game_id.
