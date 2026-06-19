-- Audit Logging
-- Phase 2: Audit logging

-- Audit Logs table
CREATE TABLE audit_logs (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32),
    user_name VARCHAR(100),
    user_email VARCHAR(200),
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(32),
    resource_name VARCHAR(200),
    scope_game_id VARCHAR(32),
    scope_environment_id VARCHAR(32),
    action_description VARCHAR(500),
    changes TEXT,
    request_id VARCHAR(64),
    request_method VARCHAR(10),
    request_path VARCHAR(500),
    client_ip VARCHAR(50),
    user_agent VARCHAR(500),
    result VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP,
    alerted BOOLEAN DEFAULT FALSE
);

-- Indexes for common queries
CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);
CREATE INDEX idx_audit_logs_game_id ON audit_logs(scope_game_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_logs_result ON audit_logs(result);
CREATE INDEX idx_audit_logs_expire_at ON audit_logs(expire_at);

-- Composite index for security event queries
CREATE INDEX idx_audit_logs_security ON audit_logs(action, result) WHERE action IN ('SECURITY_ALERT', 'RATE_LIMIT_EXCEEDED', 'UNAUTHORIZED_ACCESS', 'SUSPICIOUS_ACTIVITY');

-- Partitioning strategy (for ClickHouse or large-scale deployments)
-- Consider partitioning by created_at monthly for better performance
