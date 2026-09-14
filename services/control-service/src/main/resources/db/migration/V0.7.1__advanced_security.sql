-- Advanced Security Tables
-- Phase 7: Advanced Security - MFA, SSO, and Security Policies

-- MFA Configs table
CREATE TABLE mfa_configs (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    mfa_method VARCHAR(30) NOT NULL,
    mfa_status VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    is_primary BOOLEAN DEFAULT false,
    secret_key VARCHAR(100),
    qr_code_url VARCHAR(500),
    phone_number VARCHAR(50),
    email_address VARCHAR(200),
    device_id VARCHAR(100),
    device_name VARCHAR(100),
    verification_attempts INTEGER DEFAULT 0,
    last_verified_at TIMESTAMP,
    last_used_at TIMESTAMP,
    backup_codes TEXT,
    backup_codes_used TEXT,
    metadata TEXT,
    enrolled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Indexes for efficient lookups
CREATE INDEX idx_mfa_configs_user_id ON mfa_configs(user_id);
CREATE INDEX idx_mfa_configs_mfa_method ON mfa_configs(mfa_method);
CREATE INDEX idx_mfa_configs_mfa_status ON mfa_configs(mfa_status);
CREATE INDEX idx_mfa_configs_is_primary ON mfa_configs(is_primary);
CREATE INDEX idx_mfa_configs_deleted_at ON mfa_configs(deleted_at);

-- Index for enabled MFA
CREATE INDEX idx_mfa_configs_enabled ON mfa_configs(user_id, mfa_status) WHERE mfa_status = 'ENABLED';

-- SSO Configs table
CREATE TABLE sso_configs (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    sso_protocol VARCHAR(20) NOT NULL,
    sso_status VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    is_default BOOLEAN DEFAULT false,
    saml_idp_entity_id VARCHAR(500),
    saml_idp_sso_url VARCHAR(500),
    saml_idp_slo_url VARCHAR(500),
    saml_idp_cert TEXT,
    saml_sp_entity_id VARCHAR(500),
    saml_acs_url VARCHAR(500),
    saml_slo_url VARCHAR(500),
    saml_sp_cert TEXT,
    saml_sp_key TEXT,
    saml_name_id_format VARCHAR(100),
    oauth_provider VARCHAR(50),
    oauth_client_id VARCHAR(255),
    oauth_client_secret TEXT,
    oauth_authorization_url VARCHAR(500),
    oauth_token_url VARCHAR(500),
    oauth_user_info_url VARCHAR(500),
    oauth_scopes VARCHAR(500),
    oauth_callback_url VARCHAR(500),
    oauth_response_type VARCHAR(20),
    oauth_grant_type VARCHAR(50),
    attribute_mapping TEXT,
    role_mapping TEXT,
    allowed_domains TEXT,
    auto_provision BOOLEAN DEFAULT false,
    auto_provision_role VARCHAR(100),
    force_authn BOOLEAN DEFAULT false,
    sign_requests BOOLEAN DEFAULT true,
    encrypt_assertions BOOLEAN DEFAULT true,
    last_tested_at TIMESTAMP,
    last_test_result TEXT,
    error_message TEXT,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Indexes for efficient lookups
CREATE INDEX idx_sso_configs_sso_protocol ON sso_configs(sso_protocol);
CREATE INDEX idx_sso_configs_sso_status ON sso_configs(sso_status);
CREATE INDEX idx_sso_configs_is_default ON sso_configs(is_default);
CREATE INDEX idx_sso_configs_oauth_provider ON sso_configs(oauth_provider);
CREATE INDEX idx_sso_configs_deleted_at ON sso_configs(deleted_at);

-- Index for active SSO
CREATE INDEX idx_sso_configs_active ON sso_configs(sso_status, is_default) WHERE sso_status = 'ACTIVE';

-- Security Sessions table
CREATE TABLE security_sessions (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_token VARCHAR(255) NOT NULL,
    refresh_token VARCHAR(255),
    session_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    auth_method VARCHAR(30) NOT NULL DEFAULT 'PASSWORD',
    mfa_verified BOOLEAN DEFAULT false,
    mfa_method VARCHAR(30),
    sso_provider VARCHAR(100),
    sso_config_id VARCHAR(32),
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    device_fingerprint VARCHAR(100),
    device_type VARCHAR(50),
    browser VARCHAR(100),
    os VARCHAR(100),
    location_country VARCHAR(100),
    location_city VARCHAR(100),
    login_at TIMESTAMP NOT NULL,
    last_activity_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    max_renewal_times INTEGER DEFAULT 0,
    renewal_count INTEGER DEFAULT 0,
    concurrent_session_id VARCHAR(32),
    is_current BOOLEAN DEFAULT false,
    terminated_by VARCHAR(64),
    terminated_at TIMESTAMP,
    termination_reason VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Indexes for efficient lookups
CREATE INDEX idx_security_sessions_user_id ON security_sessions(user_id);
CREATE INDEX idx_security_sessions_session_token ON security_sessions(session_token);
CREATE INDEX idx_security_sessions_session_status ON security_sessions(session_status);
CREATE INDEX idx_security_sessions_last_activity_at ON security_sessions(last_activity_at DESC);
CREATE INDEX idx_security_sessions_expires_at ON security_sessions(expires_at);
CREATE INDEX idx_security_sessions_device_fingerprint ON security_sessions(device_fingerprint);

-- Index for active sessions
CREATE INDEX idx_security_sessions_active ON security_sessions(user_id, session_status) WHERE session_status = 'ACTIVE';

-- Security Policies table
CREATE TABLE security_policies (
    id VARCHAR(32) PRIMARY KEY,
    policy_type VARCHAR(20) NOT NULL,
    policy_scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    game_id VARCHAR(32),
    environment_id VARCHAR(32),
    user_id VARCHAR(64),
    policy_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN DEFAULT true,
    priority INTEGER DEFAULT 0,
    min_password_length INTEGER DEFAULT 8,
    max_password_length INTEGER DEFAULT 128,
    require_uppercase BOOLEAN DEFAULT true,
    require_lowercase BOOLEAN DEFAULT true,
    require_numbers BOOLEAN DEFAULT true,
    require_special_chars BOOLEAN DEFAULT true,
    forbidden_passwords TEXT,
    password_history INTEGER DEFAULT 5,
    password_expiry_days INTEGER DEFAULT 90,
    password_expiry_warning_days INTEGER DEFAULT 7,
    allow_password_reuse BOOLEAN DEFAULT false,
    session_timeout_minutes INTEGER DEFAULT 30,
    max_concurrent_sessions INTEGER DEFAULT 5,
    session_renewal_allowed BOOLEAN DEFAULT true,
    max_renewal_times INTEGER DEFAULT 3,
    idle_timeout_minutes INTEGER DEFAULT 15,
    absolute_timeout_minutes INTEGER DEFAULT 480,
    mfa_required BOOLEAN DEFAULT false,
    mfa_methods TEXT,
    mfa_trust_device_days INTEGER DEFAULT 30,
    allowed_ip_ranges TEXT,
    denied_ip_ranges TEXT,
    allowed_countries TEXT,
    blocked_countries TEXT,
    require_ip_whitelist BOOLEAN DEFAULT false,
    api_rate_limit INTEGER DEFAULT 100,
    api_burst_limit INTEGER DEFAULT 200,
    require_https BOOLEAN DEFAULT true,
    api_key_required BOOLEAN DEFAULT false,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_security_policies_policy_type ON security_policies(policy_type);
CREATE INDEX idx_security_policies_policy_scope ON security_policies(policy_scope);
CREATE INDEX idx_security_policies_game_id ON security_policies(game_id);
CREATE INDEX idx_security_policies_enabled ON security_policies(enabled);
CREATE INDEX idx_security_policies_priority ON security_policies(priority DESC);
CREATE INDEX idx_security_policies_deleted_at ON security_policies(deleted_at);

-- Index for active policies
CREATE INDEX idx_security_policies_active ON security_policies(policy_type, policy_scope, enabled) WHERE enabled = true AND deleted_at IS NULL;

-- Insert example MFA configs
