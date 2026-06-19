-- Oddsmaker Control Service baseline schema
-- Model: one company deployment, many games, many environments.
-- Compatibility: PostgreSQL 12+ / H2 development profile.

CREATE TABLE IF NOT EXISTS games (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description TEXT,
    genre VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'DEVELOPMENT',

    current_version VARCHAR(20),
    min_supported_version VARCHAR(20),
    release_date TIMESTAMP,

    app_store_url VARCHAR(500),
    google_play_url VARCHAR(500),
    steam_url VARCHAR(500),

    default_currency VARCHAR(10) DEFAULT 'USD',
    virtual_currencies TEXT,
    max_level INTEGER,
    has_multiplayer BOOLEAN DEFAULT FALSE,
    has_guilds BOOLEAN DEFAULT FALSE,
    has_pvp BOOLEAN DEFAULT FALSE,

    data_retention_days INTEGER DEFAULT 90,
    enable_real_time_analytics BOOLEAN DEFAULT TRUE,
    enable_crash_reporting BOOLEAN DEFAULT TRUE,
    sample_rate DECIMAL(3,2) DEFAULT 1.0,

    pii_detection_enabled BOOLEAN DEFAULT TRUE,
    gdpr_compliance BOOLEAN DEFAULT FALSE,
    coppa_compliance BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS game_platforms (
    game_id VARCHAR(32) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    CONSTRAINT fk_game_platforms_game FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE TABLE IF NOT EXISTS storage_profiles (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(150),
    description VARCHAR(500),
    isolation_strategy VARCHAR(32) NOT NULL DEFAULT 'SHARED',
    kafka_cluster VARCHAR(100),
    clickhouse_cluster VARCHAR(100),
    redis_cluster VARCHAR(100),
    archive_bucket VARCHAR(200),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS game_environments (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(50) NOT NULL,
    display_name VARCHAR(100),
    description VARCHAR(500),
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    storage_profile_id VARCHAR(64),

    api_endpoint VARCHAR(500),
    data_namespace VARCHAR(100),
    kafka_topic_prefix VARCHAR(50),

    data_retention_days INTEGER,
    max_events_per_day BIGINT,

    enable_debug_mode BOOLEAN DEFAULT FALSE,
    enable_sampling BOOLEAN DEFAULT TRUE,
    sample_rate DECIMAL(3,2) DEFAULT 1.0,
    enable_real_time BOOLEAN DEFAULT TRUE,

    require_https BOOLEAN DEFAULT TRUE,
    allowed_origins TEXT,
    ip_whitelist TEXT,

    enable_alerts BOOLEAN DEFAULT TRUE,
    alert_email VARCHAR(255),
    error_threshold DECIMAL(3,2) DEFAULT 0.05,

    schema_version VARCHAR(20),
    config_version VARCHAR(20),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    CONSTRAINT fk_game_environments_game FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT fk_game_environments_storage_profile FOREIGN KEY (storage_profile_id) REFERENCES storage_profiles(id),
    CONSTRAINT uk_game_environment_name UNIQUE (game_id, name)
);

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(32) PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(100),
    display_name VARCHAR(100),
    avatar_url VARCHAR(500),

    password_hash VARCHAR(255),
    email_verified BOOLEAN DEFAULT FALSE,
    email_verification_token VARCHAR(255),
    password_reset_token VARCHAR(255),
    password_reset_expires TIMESTAMP,

    global_role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    company VARCHAR(100),
    title VARCHAR(50),
    phone VARCHAR(20),
    time_zone VARCHAR(50) DEFAULT 'UTC',
    locale VARCHAR(10) DEFAULT 'en-US',

    notification_email BOOLEAN DEFAULT TRUE,
    notification_sms BOOLEAN DEFAULT FALSE,
    dashboard_theme VARCHAR(20) DEFAULT 'light',

    two_factor_enabled BOOLEAN DEFAULT FALSE,
    two_factor_secret VARCHAR(32),
    login_attempts INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    last_login TIMESTAMP,
    last_login_ip VARCHAR(45),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS user_roles (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    game_id VARCHAR(32),
    environment_id VARCHAR(32),
    role VARCHAR(20) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    permissions TEXT,

    invited_by VARCHAR(32),
    invitation_accepted BOOLEAN DEFAULT FALSE,
    invitation_token VARCHAR(255),
    invitation_expires TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,

    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_game FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT fk_user_roles_environment FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE TABLE IF NOT EXISTS user_invitations (
    id VARCHAR(32) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    inviter_id VARCHAR(32) NOT NULL,
    token VARCHAR(255) UNIQUE NOT NULL,

    game_id VARCHAR(32),
    environment_id VARCHAR(32),
    role VARCHAR(20) NOT NULL,
    scope VARCHAR(20) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    message VARCHAR(500),
    subject VARCHAR(200),

    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    rejected_at TIMESTAMP,
    created_user_id VARCHAR(32),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_user_invitations_inviter FOREIGN KEY (inviter_id) REFERENCES users(id),
    CONSTRAINT fk_user_invitations_game FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT fk_user_invitations_environment FOREIGN KEY (environment_id) REFERENCES game_environments(id),
    CONSTRAINT fk_user_invitations_user FOREIGN KEY (created_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS api_keys (
    api_key VARCHAR(64) PRIMARY KEY,
    secret VARCHAR(64) NOT NULL,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    key_type VARCHAR(20) DEFAULT 'PRODUCTION',
    status VARCHAR(20) DEFAULT 'ACTIVE',

    rpm INTEGER DEFAULT 600,
    ip_rpm INTEGER DEFAULT 300,
    daily_quota BIGINT,
    monthly_quota BIGINT,

    props_allowlist TEXT,
    pii_email VARCHAR(10) DEFAULT 'allow',
    pii_phone VARCHAR(10) DEFAULT 'allow',
    pii_ip VARCHAR(10) DEFAULT 'allow',
    deny_keys TEXT,
    mask_keys TEXT,

    require_hmac BOOLEAN DEFAULT FALSE,
    allowed_origins TEXT,
    ip_whitelist TEXT,
    user_agent_whitelist TEXT,

    can_write BOOLEAN DEFAULT TRUE,
    can_read BOOLEAN DEFAULT FALSE,
    can_export BOOLEAN DEFAULT FALSE,

    total_requests BIGINT DEFAULT 0,
    total_events BIGINT DEFAULT 0,
    last_used_at TIMESTAMP,
    last_used_ip VARCHAR(45),

    expires_at TIMESTAMP,
    auto_rotate BOOLEAN DEFAULT FALSE,
    rotation_days INTEGER DEFAULT 90,

    created_by VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,

    CONSTRAINT fk_api_keys_game FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT fk_api_keys_environment FOREIGN KEY (environment_id) REFERENCES game_environments(id),
    CONSTRAINT fk_api_keys_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS experiments (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    salt VARCHAR(128),
    config_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_experiments_game FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT fk_experiments_environment FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX IF NOT EXISTS idx_games_status ON games(status);
CREATE INDEX IF NOT EXISTS idx_games_genre ON games(genre);
CREATE INDEX IF NOT EXISTS idx_games_created_at ON games(created_at);

CREATE INDEX IF NOT EXISTS idx_game_platforms_game_id ON game_platforms(game_id);
CREATE INDEX IF NOT EXISTS idx_storage_profiles_name ON storage_profiles(name);
CREATE INDEX IF NOT EXISTS idx_storage_profiles_active ON storage_profiles(is_active);
CREATE INDEX IF NOT EXISTS idx_game_environments_game_id ON game_environments(game_id);
CREATE INDEX IF NOT EXISTS idx_game_environments_type ON game_environments(type);
CREATE INDEX IF NOT EXISTS idx_game_environments_status ON game_environments(status);
CREATE INDEX IF NOT EXISTS idx_game_environments_storage_profile_id ON game_environments(storage_profile_id);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
CREATE INDEX IF NOT EXISTS idx_users_global_role ON users(global_role);
CREATE INDEX IF NOT EXISTS idx_users_last_login ON users(last_login);

CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_game_id ON user_roles(game_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_environment_id ON user_roles(environment_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_role ON user_roles(role);
CREATE INDEX IF NOT EXISTS idx_user_roles_scope ON user_roles(scope);

CREATE INDEX IF NOT EXISTS idx_user_invitations_email ON user_invitations(email);
CREATE INDEX IF NOT EXISTS idx_user_invitations_token ON user_invitations(token);
CREATE INDEX IF NOT EXISTS idx_user_invitations_game_id ON user_invitations(game_id);
CREATE INDEX IF NOT EXISTS idx_user_invitations_environment_id ON user_invitations(environment_id);
CREATE INDEX IF NOT EXISTS idx_user_invitations_status ON user_invitations(status);
CREATE INDEX IF NOT EXISTS idx_user_invitations_expires_at ON user_invitations(expires_at);

CREATE INDEX IF NOT EXISTS idx_api_keys_game_id ON api_keys(game_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_environment_id ON api_keys(environment_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_status ON api_keys(status);
CREATE INDEX IF NOT EXISTS idx_api_keys_created_by ON api_keys(created_by);
CREATE INDEX IF NOT EXISTS idx_api_keys_last_used_at ON api_keys(last_used_at);

CREATE INDEX IF NOT EXISTS idx_experiments_game_env ON experiments(game_id, environment_id);
CREATE INDEX IF NOT EXISTS idx_experiments_status ON experiments(status);

INSERT INTO users (id, email, name, global_role, status, email_verified)
VALUES ('user_admin', 'admin@oddsmaker.local', 'System Admin', 'SUPER_ADMIN', 'ACTIVE', TRUE)
ON CONFLICT (email) DO NOTHING;

INSERT INTO storage_profiles (
    id, name, display_name, description, isolation_strategy,
    kafka_cluster, clickhouse_cluster, redis_cluster, archive_bucket, is_active
) VALUES
    (
        'shared-nonprod',
        'shared-nonprod',
        'Shared Non-Production',
        'Default backend for dev, qa, staging and other non-production environments.',
        'SHARED',
        'nonprod',
        'nonprod',
        'nonprod',
        'oddsmaker-nonprod-archive',
        TRUE
    ),
    (
        'shared-prod',
        'shared-prod',
        'Shared Production',
        'Default production backend isolated from non-production environments.',
        'PROD_ISOLATED',
        'prod',
        'prod',
        'prod',
        'oddsmaker-prod-archive',
        TRUE
    )
ON CONFLICT (id) DO NOTHING;

CREATE OR REPLACE VIEW v_user_permissions AS
SELECT
    u.id as user_id,
    u.email,
    u.name,
    u.global_role,
    ur.game_id,
    ur.environment_id,
    ur.role,
    ur.scope,
    g.name as game_name
FROM users u
LEFT JOIN user_roles ur ON u.id = ur.user_id
LEFT JOIN games g ON ur.game_id = g.id
WHERE u.deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(20) PRIMARY KEY,
    description TEXT,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_migrations (version, description)
VALUES ('v0.2.0', 'Single-company game model baseline')
ON CONFLICT (version) DO UPDATE SET applied_at = CURRENT_TIMESTAMP;
