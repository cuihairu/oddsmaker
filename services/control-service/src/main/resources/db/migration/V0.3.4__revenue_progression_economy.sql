-- Revenue Aggregation, Level Progression, Virtual Economy Monitoring
-- Phase 3: Additional Game-specific Analytics

-- Revenue Aggregations table
CREATE TABLE revenue_aggregations (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    revenue_type VARCHAR(20) NOT NULL DEFAULT 'IAP',
    group_by_dimensions TEXT,
    include_refunds BOOLEAN DEFAULT TRUE,
    include_chargebacks BOOLEAN DEFAULT TRUE,
    base_currency VARCHAR(10) DEFAULT 'USD',
    exchange_rate_source VARCHAR(50) DEFAULT 'daily',
    time_granularity VARCHAR(20) DEFAULT 'daily',
    enable_realtime BOOLEAN DEFAULT FALSE,
    enable_ltv BOOLEAN DEFAULT TRUE,
    ltv_time_windows TEXT,
    enable_forecast BOOLEAN DEFAULT FALSE,
    forecast_days INTEGER DEFAULT 30,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enable_auto_calc BOOLEAN DEFAULT TRUE,
    calc_frequency VARCHAR(20) DEFAULT 'daily',
    last_calculated_at TIMESTAMP,
    result_table VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_revenue_agg_game_id ON revenue_aggregations(game_id);
CREATE INDEX idx_revenue_agg_type ON revenue_aggregations(revenue_type);
CREATE UNIQUE INDEX idx_revenue_agg_name ON revenue_aggregations(game_id, name) WHERE deleted_at IS NULL;

-- Level Progressions table
CREATE TABLE level_progressions (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    level_identifier VARCHAR(100),
    level_start_event VARCHAR(100) DEFAULT 'level_start',
    level_complete_event VARCHAR(100) DEFAULT 'level_complete',
    level_fail_event VARCHAR(100) DEFAULT 'level_fail',
    track_pass_rate BOOLEAN DEFAULT TRUE,
    track_fail_rate BOOLEAN DEFAULT TRUE,
    track_retry_count BOOLEAN DEFAULT TRUE,
    track_completion_time BOOLEAN DEFAULT TRUE,
    track_first_completion BOOLEAN DEFAULT TRUE,
    enable_anomaly_detection BOOLEAN DEFAULT TRUE,
    anomaly_threshold DECIMAL(5,2) DEFAULT 2.0,
    min_attempts_for_analysis INTEGER DEFAULT 100,
    group_by_dimensions TEXT,
    enable_difficulty_analysis BOOLEAN DEFAULT TRUE,
    difficulty_metric VARCHAR(50) DEFAULT 'pass_rate',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enable_auto_calc BOOLEAN DEFAULT TRUE,
    calc_frequency VARCHAR(20) DEFAULT 'daily',
    last_calculated_at TIMESTAMP,
    result_table VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_level_prog_game_id ON level_progressions(game_id);
CREATE INDEX idx_level_prog_status ON level_progressions(status);
CREATE UNIQUE INDEX idx_level_prog_name ON level_progressions(game_id, name) WHERE deleted_at IS NULL;

-- Virtual Economies table
CREATE TABLE virtual_economies (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    currency_id VARCHAR(100) NOT NULL,
    currency_name VARCHAR(200),
    currency_type VARCHAR(20) NOT NULL DEFAULT 'PREMIUM',
    enable_source_tracking BOOLEAN DEFAULT TRUE,
    enable_sink_tracking BOOLEAN DEFAULT TRUE,
    source_events TEXT,
    sink_events TEXT,
    enable_inflation_monitoring BOOLEAN DEFAULT TRUE,
    inflation_calc_method VARCHAR(50) DEFAULT 'circulating_supply',
    inflation_threshold DECIMAL(5,4) DEFAULT 0.05,
    enable_flow_analysis BOOLEAN DEFAULT TRUE,
    flow_balance_threshold DECIMAL(5,4) DEFAULT 0.8,
    enable_alerts BOOLEAN DEFAULT TRUE,
    alert_threshold_low DECIMAL(5,4) DEFAULT 0.2,
    alert_threshold_high DECIMAL(5,4) DEFAULT 0.8,
    group_by_dimensions TEXT,
    time_granularity VARCHAR(20) DEFAULT 'daily',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enable_auto_calc BOOLEAN DEFAULT TRUE,
    calc_frequency VARCHAR(20) DEFAULT 'daily',
    last_calculated_at TIMESTAMP,
    result_table VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_virtual_econ_game_id ON virtual_economies(game_id);
CREATE INDEX idx_virtual_econ_currency ON virtual_economies(currency_id);
CREATE UNIQUE INDEX idx_virtual_econ_name ON virtual_economies(game_id, name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_virtual_econ_currency ON virtual_economies(game_id, currency_id) WHERE deleted_at IS NULL;
