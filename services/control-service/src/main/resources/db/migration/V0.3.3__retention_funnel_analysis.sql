-- Retention and Funnel Analysis
-- Phase 3: Retention Analysis and Funnel Analysis

-- Retention Analyses table
CREATE TABLE retention_analyses (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    retention_type VARCHAR(20) NOT NULL DEFAULT 'N_DAY',
    trigger_event VARCHAR(100) NOT NULL,
    trigger_condition TEXT,
    return_event VARCHAR(100) NOT NULL,
    return_condition TEXT,
    time_windows TEXT,
    rolling_window_days INTEGER DEFAULT 7,
    rolling_interval_days INTEGER DEFAULT 1,
    cohort_type VARCHAR(20) NOT NULL DEFAULT 'ALL_USERS',
    cohort_filter TEXT,
    include_same_day BOOLEAN DEFAULT FALSE,
    minimum_cohort_size INTEGER DEFAULT 100,
    confidence_level DECIMAL(5,4) DEFAULT 0.95,
    group_by_dimensions TEXT,
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

CREATE INDEX idx_retention_game_id ON retention_analyses(game_id);
CREATE INDEX idx_retention_status ON retention_analyses(status);
CREATE UNIQUE INDEX idx_retention_name ON retention_analyses(game_id, name) WHERE deleted_at IS NULL;

-- Funnel Analyses table
CREATE TABLE funnel_analyses (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    funnel_type VARCHAR(20) NOT NULL DEFAULT 'SEQUENTIAL',
    window_type VARCHAR(50) NOT NULL DEFAULT 'fixed',
    window_size INTEGER DEFAULT 7,
    funnel_steps TEXT,
    total_steps INTEGER DEFAULT 0,
    cohort_filter TEXT,
    entry_event VARCHAR(100),
    exit_event VARCHAR(100),
    max_completion_time INTEGER,
    step_timeout INTEGER,
    allow_backtracking BOOLEAN DEFAULT FALSE,
    strict_order BOOLEAN DEFAULT TRUE,
    allow_repeats BOOLEAN DEFAULT FALSE,
    group_by_dimensions TEXT,
    conversion_calculation VARCHAR(20) DEFAULT 'linear',
    include_drop_off_analysis BOOLEAN DEFAULT TRUE,
    include_time_analysis BOOLEAN DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enable_auto_calc BOOLEAN DEFAULT TRUE,
    calc_frequency VARCHAR(20) DEFAULT 'daily',
    last_calculated_at TIMESTAMP,
    result_table VARCHAR(100),
    chart_type VARCHAR(50) DEFAULT 'bar',
    color_scheme VARCHAR(50) DEFAULT 'default',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_funnel_game_id ON funnel_analyses(game_id);
CREATE INDEX idx_funnel_status ON funnel_analyses(status);
CREATE UNIQUE INDEX idx_funnel_name ON funnel_analyses(game_id, name) WHERE deleted_at IS NULL;

-- Funnel Steps table
CREATE TABLE funnel_steps (
    id VARCHAR(32) PRIMARY KEY,
    funnel_analysis_id VARCHAR(32) NOT NULL,
    step_order INTEGER NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    step_condition TEXT,
    event_filter TEXT,
    time_from_previous INTEGER,
    time_from_start INTEGER,
    time_to_next INTEGER,
    is_optional BOOLEAN DEFAULT FALSE,
    allow_skip BOOLEAN DEFAULT FALSE,
    branch_condition TEXT,
    is_branch_point BOOLEAN DEFAULT FALSE,
    target_conversion_rate DECIMAL(5,4),
    custom_attributes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    conversion_rate DECIMAL(5,4),
    drop_off_rate DECIMAL(5,4),
    median_time BIGINT,
    average_time BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (funnel_analysis_id) REFERENCES funnel_analyses(id)
);

CREATE UNIQUE INDEX idx_funnel_steps_order ON funnel_steps(funnel_analysis_id, step_order) WHERE deleted_at IS NULL;
CREATE INDEX idx_funnel_steps_analysis_id ON funnel_steps(funnel_analysis_id);
CREATE INDEX idx_funnel_steps_event_name ON funnel_steps(event_name);
