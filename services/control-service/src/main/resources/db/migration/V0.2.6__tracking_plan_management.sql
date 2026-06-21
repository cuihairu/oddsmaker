-- Tracking Plan Management Tables
-- Phase 2: Tracking Plan Management

-- Tracking Plans table
CREATE TABLE tracking_plans (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    version VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    environment_id VARCHAR(32),
    strictness VARCHAR(20) NOT NULL DEFAULT 'STRICT',
    enable_auto_validation BOOLEAN DEFAULT TRUE,
    reject_unknown_events BOOLEAN DEFAULT FALSE,
    total_events INTEGER DEFAULT 0,
    active_events INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    activated_at TIMESTAMP,
    deactivated_at TIMESTAMP,
    created_by VARCHAR(64),
    activated_by VARCHAR(64),
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_tracking_plans_game_id ON tracking_plans(game_id);
CREATE INDEX idx_tracking_plans_status ON tracking_plans(status);
CREATE INDEX idx_tracking_plans_environment_id ON tracking_plans(environment_id);

-- Event Definitions table
CREATE TABLE event_definitions (
    id VARCHAR(32) PRIMARY KEY,
    tracking_plan_id VARCHAR(32) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    category VARCHAR(100),
    subcategory VARCHAR(100),
    identity VARCHAR(20) NOT NULL DEFAULT 'DEVICE',
    require_user_id BOOLEAN DEFAULT FALSE,
    require_session_id BOOLEAN DEFAULT FALSE,
    require_player_id BOOLEAN DEFAULT FALSE,
    validation_rules TEXT,
    example_payload TEXT,
    doc_url VARCHAR(500),
    importance VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    usage_count BIGINT DEFAULT 0,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (tracking_plan_id) REFERENCES tracking_plans(id)
);

CREATE UNIQUE INDEX idx_event_defs_unique_name ON event_definitions(tracking_plan_id, event_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_event_defs_tracking_plan_id ON event_definitions(tracking_plan_id);
CREATE INDEX idx_event_defs_event_type ON event_definitions(event_type);
CREATE INDEX idx_event_defs_status ON event_definitions(status);

-- Event Property Definitions table
CREATE TABLE event_property_definitions (
    id VARCHAR(32) PRIMARY KEY,
    event_definition_id VARCHAR(32) NOT NULL,
    property_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(500),
    type VARCHAR(20) NOT NULL DEFAULT 'STRING',
    array_element_type VARCHAR(50),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    default_value VARCHAR(50),
    allowed_values TEXT,
    min_value DECIMAL(20,6),
    max_value DECIMAL(20,6),
    min_length INTEGER,
    max_length INTEGER,
    regex_pattern VARCHAR(500),
    is_pii BOOLEAN DEFAULT FALSE,
    pii_type VARCHAR(50),
    is_indexed BOOLEAN DEFAULT TRUE,
    property_group VARCHAR(100),
    display_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (event_definition_id) REFERENCES event_definitions(id)
);

CREATE UNIQUE INDEX idx_event_props_unique_name ON event_property_definitions(event_definition_id, property_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_event_props_event_def_id ON event_property_definitions(event_definition_id);
CREATE INDEX idx_event_props_is_pii ON event_property_definitions(is_pii);
CREATE INDEX idx_event_props_property_group ON event_property_definitions(property_group);
