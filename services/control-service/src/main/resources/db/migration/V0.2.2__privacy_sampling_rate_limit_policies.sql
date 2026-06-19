-- Privacy, Sampling, and Rate Limiting Policies
-- Phase 2: PII/Sampling/RateLimiting policies

-- Privacy Policies table
CREATE TABLE privacy_policies (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    pii_handling VARCHAR(20) NOT NULL DEFAULT 'MASK',
    retention_days INTEGER,
    enable_pii_detection BOOLEAN DEFAULT TRUE,
    mask_method VARCHAR(50) DEFAULT 'sha256',
    hash_salt VARCHAR(100),
    enable_gdpr BOOLEAN DEFAULT FALSE,
    data_deletion_days INTEGER,
    right_to_deletion BOOLEAN DEFAULT TRUE,
    total_masked_count BIGINT DEFAULT 0,
    last_masked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_privacy_policies_game_id ON privacy_policies(game_id);
CREATE INDEX idx_privacy_policies_environment_id ON privacy_policies(environment_id);
CREATE INDEX idx_privacy_policies_status ON privacy_policies(status);

-- PII Field Mappings table
CREATE TABLE pii_field_mappings (
    id VARCHAR(32) PRIMARY KEY,
    privacy_policy_id VARCHAR(32) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    field_type VARCHAR(50),
    pii_category VARCHAR(20) NOT NULL DEFAULT 'PERSONAL',
    pii_sensitivity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    handling VARCHAR(20) NOT NULL DEFAULT 'MASK',
    mask_pattern VARCHAR(200),
    retention_days INTEGER,
    enable_anonymization BOOLEAN DEFAULT TRUE,
    anonymization_method VARCHAR(50) DEFAULT 'hash',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (privacy_policy_id) REFERENCES privacy_policies(id)
);

CREATE UNIQUE INDEX idx_pii_fields_unique ON pii_field_mappings(privacy_policy_id, field_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_pii_fields_policy_id ON pii_field_mappings(privacy_policy_id);
CREATE INDEX idx_pii_fields_pii_category ON pii_field_mappings(pii_category);

-- Sampling Policies table
CREATE TABLE sampling_policies (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    strategy VARCHAR(20) NOT NULL DEFAULT 'UNIFORM',
    default_sample_rate DOUBLE DEFAULT 1.0,
    max_events_per_second INTEGER,
    max_events_per_day BIGINT,
    sampling_algorithm VARCHAR(50) DEFAULT 'consistent_hash',
    hash_key VARCHAR(100) DEFAULT 'user_id',
    enable_dynamic_sampling BOOLEAN DEFAULT FALSE,
    dynamic_sampling_rules TEXT,
    enable_priority_sampling BOOLEAN DEFAULT FALSE,
    priority_events TEXT,
    total_sampled_count BIGINT DEFAULT 0,
    total_dropped_count BIGINT DEFAULT 0,
    effective_sample_rate DOUBLE DEFAULT 1.0,
    last_calculated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_sampling_policies_game_id ON sampling_policies(game_id);
CREATE INDEX idx_sampling_policies_environment_id ON sampling_policies(environment_id);
CREATE INDEX idx_sampling_policies_status ON sampling_policies(status);

-- Event Sampling Rules table
CREATE TABLE event_sampling_rules (
    id VARCHAR(32) PRIMARY KEY,
    sampling_policy_id VARCHAR(32) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50),
    sample_rate DOUBLE DEFAULT 1.0,
    priority INTEGER DEFAULT 0,
    min_sample_rate DOUBLE DEFAULT 0.0,
    max_sample_rate DOUBLE DEFAULT 1.0,
    condition_expression TEXT,
    sampled_count BIGINT DEFAULT 0,
    dropped_count BIGINT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (sampling_policy_id) REFERENCES sampling_policies(id)
);

CREATE UNIQUE INDEX idx_event_sampling_rules_unique ON event_sampling_rules(sampling_policy_id, event_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_event_sampling_rules_policy_id ON event_sampling_rules(sampling_policy_id);
CREATE INDEX idx_event_sampling_rules_event_type ON event_sampling_rules(event_type);
CREATE INDEX idx_event_sampling_rules_status ON event_sampling_rules(status);

-- Rate Limit Policies table
CREATE TABLE rate_limit_policies (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    requests_per_second INTEGER,
    requests_per_minute INTEGER,
    requests_per_hour INTEGER,
    requests_per_day INTEGER,
    events_per_second INTEGER,
    events_per_minute INTEGER,
    events_per_day BIGINT,
    burst_size INTEGER DEFAULT 100,
    burst_window_seconds INTEGER DEFAULT 10,
    limit_algorithm VARCHAR(50) DEFAULT 'token_bucket',
    over_limit_action VARCHAR(20) NOT NULL DEFAULT 'REJECT',
    retry_after_seconds INTEGER DEFAULT 60,
    enable_rate_limit_headers BOOLEAN DEFAULT TRUE,
    enable_whitelist BOOLEAN DEFAULT FALSE,
    whitelist TEXT,
    total_limited_count BIGINT DEFAULT 0,
    current_usage BIGINT DEFAULT 0,
    last_reset_at TIMESTAMP,
    limit_exceeded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_rate_limit_policies_game_id ON rate_limit_policies(game_id);
CREATE INDEX idx_rate_limit_policies_environment_id ON rate_limit_policies(environment_id);
CREATE INDEX idx_rate_limit_policies_status ON rate_limit_policies(status);

-- Rate Limit Rules table
CREATE TABLE rate_limit_rules (
    id VARCHAR(32) PRIMARY KEY,
    rate_limit_policy_id VARCHAR(32) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50),
    event_name VARCHAR(100),
    condition_expression TEXT,
    override_requests_per_second INTEGER,
    override_events_per_second INTEGER,
    override_events_per_day BIGINT,
    enable_whitelist BOOLEAN DEFAULT FALSE,
    whitelist TEXT,
    triggered_count BIGINT DEFAULT 0,
    last_triggered_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (rate_limit_policy_id) REFERENCES rate_limit_policies(id)
);

CREATE INDEX idx_rate_limit_rules_policy_id ON rate_limit_rules(rate_limit_policy_id);
CREATE INDEX idx_rate_limit_rules_event_type ON rate_limit_rules(event_type);
CREATE INDEX idx_rate_limit_rules_status ON rate_limit_rules(status);
