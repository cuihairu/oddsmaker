-- Rate Limit and Quota Tables
-- Phase 6: API Rate Limiting - Rate limits and resource quotas

-- Rate Limits table
CREATE TABLE rate_limits (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32),
    api_key_id VARCHAR(32),
    endpoint VARCHAR(200),
    user_id VARCHAR(100),
    scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    limit_value INTEGER NOT NULL DEFAULT 100,
    window_type VARCHAR(20) NOT NULL DEFAULT 'MINUTE',
    window_size INTEGER DEFAULT 1,
    algorithm VARCHAR(30) NOT NULL DEFAULT 'SLIDING_WINDOW',
    burst INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT true,
    description VARCHAR(500),
    config TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(64),
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (api_key_id) REFERENCES api_keys(api_key)
);

-- Indexes for efficient lookups
CREATE INDEX idx_rate_limits_game_id ON rate_limits(game_id);
CREATE INDEX idx_rate_limits_api_key_id ON rate_limits(api_key_id);
CREATE INDEX idx_rate_limits_endpoint ON rate_limits(endpoint);
CREATE INDEX idx_rate_limits_user_id ON rate_limits(user_id);
CREATE INDEX idx_rate_limits_scope ON rate_limits(scope);
CREATE INDEX idx_rate_limits_enabled ON rate_limits(enabled);
CREATE INDEX idx_rate_limits_priority ON rate_limits(priority DESC);
CREATE INDEX idx_rate_limits_deleted_at ON rate_limits(deleted_at);

-- Index for active rules
CREATE INDEX idx_rate_limits_active ON rate_limits(scope, enabled, deleted_at) WHERE enabled = true AND deleted_at IS NULL;

-- Rate Limit Usage table
CREATE TABLE rate_limit_usage (
    id VARCHAR(32) PRIMARY KEY,
    rate_limit_id VARCHAR(32) NOT NULL,
    game_id VARCHAR(32),
    api_key_id VARCHAR(32),
    endpoint VARCHAR(200),
    user_id VARCHAR(100),
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    request_count INTEGER DEFAULT 0,
    blocked_count INTEGER DEFAULT 0,
    last_request_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rate_limit_id) REFERENCES rate_limits(id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_rate_limit_usage_rate_limit_id ON rate_limit_usage(rate_limit_id);
CREATE INDEX idx_rate_limit_usage_game_id ON rate_limit_usage(game_id);
CREATE INDEX idx_rate_limit_usage_api_key_id ON rate_limit_usage(api_key_id);
CREATE INDEX idx_rate_limit_usage_window_start ON rate_limit_usage(window_start);
CREATE INDEX idx_rate_limit_usage_window_end ON rate_limit_usage(window_end);
CREATE INDEX idx_rate_limit_usage_active_window ON rate_limit_usage(rate_limit_id, window_start, window_end);

-- Quotas table
CREATE TABLE quotas (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    resource_type VARCHAR(50) NOT NULL,
    quota_limit BIGINT NOT NULL,
    current_usage BIGINT DEFAULT 0,
    usage_percent DECIMAL(5,2),
    warning_threshold DECIMAL(5,2) DEFAULT 80.0,
    alert_threshold DECIMAL(5,2) DEFAULT 95.0,
    warning_sent BOOLEAN DEFAULT false,
    alert_sent BOOLEAN DEFAULT false,
    hard_limit BOOLEAN DEFAULT false,
    grace_period_days INTEGER DEFAULT 0,
    reset_at TIMESTAMP,
    last_calculated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(64),
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_quotas_game_id ON quotas(game_id);
CREATE INDEX idx_quotas_environment_id ON quotas(environment_id);
CREATE INDEX idx_quotas_resource_type ON quotas(resource_type);
CREATE INDEX idx_quotas_deleted_at ON quotas(deleted_at);

-- Index for quotas needing attention
CREATE INDEX idx_quotas_warning ON quotas(game_id, usage_percent) WHERE usage_percent >= warning_threshold;
CREATE INDEX idx_quotas_alert ON quotas(game_id, usage_percent) WHERE usage_percent >= alert_threshold;
CREATE INDEX idx_quotas_reset ON quotas(reset_at) WHERE reset_at IS NOT NULL;

-- Insert example rate limits
INSERT INTO rate_limits (id, game_id, scope, limit_value, window_type, algorithm, enabled, priority) VALUES
('rl_1', NULL, 'GLOBAL', 10000, 'MINUTE', 'SLIDING_WINDOW', true, 100),
('rl_2', 'DEFAULT', 'GAME', 1000, 'MINUTE', 'SLIDING_WINDOW', true, 50),
('rl_3', NULL, 'GLOBAL', 100000, 'HOUR', 'FIXED_WINDOW', true, 90);

-- Insert example quotas
INSERT INTO quotas (id, game_id, resource_type, quota_limit, current_usage, warning_threshold, alert_threshold) VALUES
('quota_1', 'DEFAULT', 'EVENTS_PER_DAY', 1000000, 500000, 80.0, 95.0),
('quota_2', 'DEFAULT', 'STORAGE_GB', 100, 45, 80.0, 95.0),
('quota_3', 'DEFAULT', 'API_CALLS_PER_DAY', 50000, 35000, 80.0, 95.0),
('quota_4', 'DEFAULT', 'USERS', 10000, 7500, 80.0, 95.0);
