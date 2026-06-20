-- Analytics Enhancements
-- Phase 8: Revenue, Ad, Session, Performance, Social Analytics

-- Revenue Analysis table
CREATE TABLE revenue_analysis (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(50),
    analysis_date DATE NOT NULL,
    revenue_type VARCHAR(30),
    total_revenue DECIMAL(18,4) DEFAULT 0,
    iap_revenue DECIMAL(18,4) DEFAULT 0,
    ad_revenue DECIMAL(18,4) DEFAULT 0,
    subscription_revenue DECIMAL(18,4) DEFAULT 0,
    total_users BIGINT DEFAULT 0,
    paying_users BIGINT DEFAULT 0,
    new_paying_users BIGINT DEFAULT 0,
    arpu DECIMAL(18,4) DEFAULT 0,
    arppu DECIMAL(18,4) DEFAULT 0,
    total_transactions BIGINT DEFAULT 0,
    avg_transaction_value DECIMAL(18,4) DEFAULT 0,
    platform VARCHAR(30),
    country VARCHAR(10),
    currency VARCHAR(10),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_revenue_analysis_game_date ON revenue_analysis(game_id, analysis_date);
CREATE INDEX idx_revenue_analysis_platform ON revenue_analysis(platform);

-- Ad Analysis table
CREATE TABLE ad_analysis (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(50),
    analysis_date DATE NOT NULL,
    ad_network VARCHAR(30),
    ad_format VARCHAR(30),
    ad_placement VARCHAR(100),
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    rewards BIGINT DEFAULT 0,
    revenue DECIMAL(18,4) DEFAULT 0,
    ecpm DECIMAL(18,4) DEFAULT 0,
    requests BIGINT DEFAULT 0,
    fills BIGINT DEFAULT 0,
    fill_rate DECIMAL(5,4) DEFAULT 0,
    ctr DECIMAL(5,4) DEFAULT 0,
    unique_users BIGINT DEFAULT 0,
    platform VARCHAR(30),
    country VARCHAR(10),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_ad_analysis_game_date ON ad_analysis(game_id, analysis_date);
CREATE INDEX idx_ad_analysis_network ON ad_analysis(ad_network);

-- Session Analysis table
CREATE TABLE session_analysis (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(50),
    analysis_date DATE NOT NULL,
    total_sessions BIGINT DEFAULT 0,
    unique_users BIGINT DEFAULT 0,
    avg_session_duration BIGINT DEFAULT 0,
    median_session_duration BIGINT DEFAULT 0,
    p95_session_duration BIGINT DEFAULT 0,
    avg_events_per_session DECIMAL(10,2) DEFAULT 0,
    avg_pages_per_session DECIMAL(10,2) DEFAULT 0,
    bounce_sessions BIGINT DEFAULT 0,
    bounce_rate DECIMAL(5,4) DEFAULT 0,
    high_quality_sessions BIGINT DEFAULT 0,
    medium_quality_sessions BIGINT DEFAULT 0,
    low_quality_sessions BIGINT DEFAULT 0,
    platform VARCHAR(30),
    session_source VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_session_analysis_game_date ON session_analysis(game_id, analysis_date);
CREATE INDEX idx_session_analysis_platform ON session_analysis(platform);

-- Performance Metrics table
CREATE TABLE performance_metrics (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(50),
    metric_type VARCHAR(30) NOT NULL,
    severity VARCHAR(20),
    user_id VARCHAR(128),
    device_id VARCHAR(128),
    session_id VARCHAR(128),
    platform VARCHAR(30),
    device_model VARCHAR(100),
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    metric_value DECIMAL(18,4),
    metric_unit VARCHAR(20),
    crash_type VARCHAR(50),
    crash_message TEXT,
    stack_trace TEXT,
    crash_hash VARCHAR(64),
    context_data TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_performance_metrics_game_type ON performance_metrics(game_id, metric_type);
CREATE INDEX idx_performance_metrics_created ON performance_metrics(created_at);
CREATE INDEX idx_performance_metrics_crash_hash ON performance_metrics(crash_hash);

-- Social Analytics table
CREATE TABLE social_analytics (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(50),
    analysis_date DATE NOT NULL,
    social_event_type VARCHAR(30),
    total_friendships BIGINT DEFAULT 0,
    new_friendships BIGINT DEFAULT 0,
    avg_friends_per_user DECIMAL(10,2) DEFAULT 0,
    total_guilds BIGINT DEFAULT 0,
    new_guilds BIGINT DEFAULT 0,
    avg_guild_size DECIMAL(10,2) DEFAULT 0,
    guild_members BIGINT DEFAULT 0,
    chat_messages BIGINT DEFAULT 0,
    gifts_sent BIGINT DEFAULT 0,
    invites_sent BIGINT DEFAULT 0,
    invites_accepted BIGINT DEFAULT 0,
    viral_coefficient DECIMAL(5,4) DEFAULT 0,
    social_users_retention_d1 DECIMAL(5,4) DEFAULT 0,
    social_users_retention_d7 DECIMAL(5,4) DEFAULT 0,
    non_social_users_retention_d1 DECIMAL(5,4) DEFAULT 0,
    non_social_users_retention_d7 DECIMAL(5,4) DEFAULT 0,
    platform VARCHAR(30),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_social_analytics_game_date ON social_analytics(game_id, analysis_date);
CREATE INDEX idx_social_analytics_event_type ON social_analytics(social_event_type);
