-- Standard Event Types and Events
-- Phase 3: Game-specific Analytics - Standard Event Definitions

-- Standard Event Types table
CREATE TABLE standard_event_types (
    id VARCHAR(32) PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    category VARCHAR(50),
    display_order INTEGER DEFAULT 0,
    is_core BOOLEAN DEFAULT FALSE,
    enable_aggregation BOOLEAN DEFAULT TRUE,
    aggregation_window VARCHAR(20) DEFAULT '1d',
    retention_days INTEGER DEFAULT 90,
    example_events TEXT,
    required_fields TEXT,
    optional_fields TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_std_event_types_code ON standard_event_types(code);
CREATE INDEX idx_std_event_types_category ON standard_event_types(category);
CREATE INDEX idx_std_event_types_status ON standard_event_types(status);

-- Standard Events table
CREATE TABLE standard_events (
    id VARCHAR(32) PRIMARY KEY,
    event_type_id VARCHAR(32) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    subcategory VARCHAR(100),
    use_case VARCHAR(100),
    importance VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    display_order INTEGER DEFAULT 0,
    required_fields TEXT,
    optional_fields TEXT,
    recommended_fields TEXT,
    example_payload TEXT,
    enable_funnel BOOLEAN DEFAULT FALSE,
    enable_retention BOOLEAN DEFAULT FALSE,
    enable_cohort BOOLEAN DEFAULT FALSE,
    enable_revenue BOOLEAN DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (event_type_id) REFERENCES standard_event_types(id)
);

CREATE UNIQUE INDEX idx_std_events_type_name ON standard_events(event_type_id, event_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_std_events_type_id ON standard_events(event_type_id);
CREATE INDEX idx_std_events_importance ON standard_events(importance);
CREATE INDEX idx_std_events_use_case ON standard_events(use_case);

-- Event Fields table
CREATE TABLE event_fields (
    id VARCHAR(32) PRIMARY KEY,
    standard_event_id VARCHAR(32) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
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
    purpose VARCHAR(20) NOT NULL DEFAULT 'GENERAL',
    is_dimension BOOLEAN DEFAULT FALSE,
    is_metric BOOLEAN DEFAULT FALSE,
    field_group VARCHAR(100),
    display_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (standard_event_id) REFERENCES standard_events(id)
);

CREATE UNIQUE INDEX idx_event_fields_event_name ON event_fields(standard_event_id, field_name) WHERE deleted_at IS NULL;
CREATE INDEX idx_event_fields_event_id ON event_fields(standard_event_id);
CREATE INDEX idx_event_fields_purpose ON event_fields(purpose);
CREATE INDEX idx_event_fields_group ON event_fields(field_group);

-- Insert standard event types
INSERT INTO standard_event_types (id, code, name, description, category, display_order, is_core, enable_aggregation) VALUES
-- Session events (lifecycle)
('set_session', 'session', 'Session Events', 'User session lifecycle events', 'lifecycle', 1, TRUE, TRUE),
-- User events (lifecycle)
('set_user', 'user', 'User Events', 'User account and profile events', 'lifecycle', 2, TRUE, TRUE),
-- Business events (monetization)
('set_business', 'business', 'Business Events', 'Revenue and monetization events', 'monetization', 3, TRUE, TRUE),
-- Resource events (monetization/game economy)
('set_resource', 'resource', 'Resource Events', 'Virtual currency and item flow events', 'monetization', 4, TRUE, TRUE),
-- Progression events (progression)
('set_progression', 'progression', 'Progression Events', 'Player progression and achievement events', 'progression', 5, TRUE, TRUE),
-- Design events (progression)
('set_design', 'design', 'Design Events', 'Game design and balance events', 'progression', 6, TRUE, TRUE),
-- Error events (system)
('set_error', 'error', 'Error Events', 'Error and exception events', 'system', 7, TRUE, TRUE),
-- Ad events (monetization)
('set_ad', 'ad', 'Ad Events', 'Advertisement and monetization events', 'monetization', 8, TRUE, TRUE),
-- Risk events (system)
('set_risk', 'risk', 'Risk Events', 'Security and fraud detection events', 'system', 9, TRUE, TRUE);

-- Insert standard session events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_funnel, enable_retention) VALUES
('se_session_start', 'set_session', 'session_start', 'Session Start', 'First event of a user session', 'CRITICAL', 1, TRUE, TRUE),
('se_session_end', 'set_session', 'session_end', 'Session End', 'Last event of a user session before disconnect', 'HIGH', 2, TRUE, FALSE);

-- Insert standard user events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_retention, enable_cohort) VALUES
('se_user_register', 'set_user', 'user_register', 'User Registration', 'New user account created', 'CRITICAL', 1, TRUE, TRUE),
('se_user_login', 'set_user', 'user_login', 'User Login', 'User logged in', 'HIGH', 2, TRUE, TRUE),
('se_user_logout', 'set_user', 'user_logout', 'User Logout', 'User logged out', 'NORMAL', 3, FALSE, FALSE),
('se_user_profile_update', 'set_user', 'user_profile_update', 'Profile Update', 'User updated profile information', 'NORMAL', 4, FALSE, FALSE);

-- Insert standard business events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_revenue) VALUES
('se_purchase', 'set_business', 'purchase', 'Purchase', 'User completed a purchase transaction', 'CRITICAL', 1, TRUE),
('se_purchase_attempt', 'set_business', 'purchase_attempt', 'Purchase Attempt', 'User attempted a purchase', 'HIGH', 2, FALSE),
('se_purchase_fail', 'set_business', 'purchase_fail', 'Purchase Failed', 'Purchase transaction failed', 'HIGH', 3, FALSE),
('se_refund', 'set_business', 'refund', 'Refund', 'Purchase was refunded', 'NORMAL', 4, TRUE),
('se_subscription_start', 'set_business', 'subscription_start', 'Subscription Start', 'User started a subscription', 'HIGH', 5, TRUE),
('se_subscription_cancel', 'set_business', 'subscription_cancel', 'Subscription Cancel', 'User cancelled subscription', 'HIGH', 6, FALSE);

-- Insert standard resource events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_revenue) VALUES
('se_currency_grant', 'set_resource', 'currency_grant', 'Currency Grant', 'Virtual currency granted to user', 'HIGH', 1, TRUE),
('se_currency_spend', 'set_resource', 'currency_spend', 'Currency Spend', 'Virtual currency spent by user', 'HIGH', 2, TRUE),
('se_item_grant', 'set_resource', 'item_grant', 'Item Grant', 'Item granted to user', 'HIGH', 3, FALSE),
('se_item_consume', 'set_resource', 'item_consume', 'Item Consume', 'Item consumed by user', 'HIGH', 4, FALSE),
('se_item_craft', 'set_resource', 'item_craft', 'Item Craft', 'User crafted an item', 'NORMAL', 5, FALSE);

-- Insert standard progression events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_funnel, enable_retention) VALUES
('se_level_start', 'set_progression', 'level_start', 'Level Start', 'User started a level', 'HIGH', 1, TRUE, TRUE),
('se_level_complete', 'set_progression', 'level_complete', 'Level Complete', 'User completed a level', 'CRITICAL', 2, TRUE, TRUE),
('se_level_fail', 'set_progression', 'level_fail', 'Level Failed', 'User failed a level', 'HIGH', 3, TRUE, FALSE),
('se_level_skip', 'set_progression', 'level_skip', 'Level Skip', 'User skipped a level', 'NORMAL', 4, FALSE, FALSE),
('se_achievement_unlock', 'set_progression', 'achievement_unlock', 'Achievement Unlock', 'User unlocked an achievement', 'HIGH', 5, FALSE, TRUE),
('se_tutorial_complete', 'set_progression', 'tutorial_complete', 'Tutorial Complete', 'User completed tutorial', 'HIGH', 6, FALSE, TRUE);

-- Insert standard design events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_retention) VALUES
('se_balance_update', 'set_design', 'balance_update', 'Balance Update', 'Game balance was updated', 'NORMAL', 1, FALSE),
('se difficulty_change', 'set_design', 'difficulty_change', 'Difficulty Change', 'Game difficulty was changed', 'NORMAL', 2, FALSE),
('se_reward_claim', 'set_design', 'reward_claim', 'Reward Claim', 'User claimed a reward', 'HIGH', 3, TRUE),
('se_challenge_start', 'set_design', 'challenge_start', 'Challenge Start', 'User started a challenge', 'NORMAL', 4, TRUE),
('se_challenge_complete', 'set_design', 'challenge_complete', 'Challenge Complete', 'User completed a challenge', 'HIGH', 5, TRUE);

-- Insert standard error events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order) VALUES
('se_app_error', 'set_error', 'app_error', 'App Error', 'Application error occurred', 'HIGH', 1),
('se_app_crash', 'set_error', 'app_crash', 'App Crash', 'Application crashed', 'CRITICAL', 2),
('se_network_error', 'set_error', 'network_error', 'Network Error', 'Network request failed', 'NORMAL', 3),
('se_api_error', 'set_error', 'api_error', 'API Error', 'API call failed', 'HIGH', 4);

-- Insert standard ad events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order, enable_revenue) VALUES
('se_ad_impression', 'set_ad', 'ad_impression', 'Ad Impression', 'Ad was shown to user', 'HIGH', 1, TRUE),
('se_ad_click', 'set_ad', 'ad_click', 'Ad Click', 'User clicked on ad', 'HIGH', 2, TRUE),
('se_ad_complete', 'set_ad', 'ad_complete', 'Ad Complete', 'User watched ad completely', 'HIGH', 3, TRUE),
('se_ad_skip', 'set_ad', 'ad_skip', 'Ad Skip', 'User skipped ad', 'NORMAL', 4, FALSE),
('se_ad_reward', 'set_ad', 'ad_reward', 'Ad Reward', 'User received ad reward', 'HIGH', 5, FALSE);

-- Insert standard risk events
INSERT INTO standard_events (id, event_type_id, event_name, display_name, description, importance, display_order) VALUES
('se_suspicious_activity', 'set_risk', 'suspicious_activity', 'Suspicious Activity', 'Suspicious user activity detected', 'CRITICAL', 1),
('se_rate_limit_exceeded', 'set_risk', 'rate_limit_exceeded', 'Rate Limit Exceeded', 'API rate limit was exceeded', 'HIGH', 2),
('se_fraud_detected', 'set_risk', 'fraud_detected', 'Fraud Detected', 'Potential fraud was detected', 'CRITICAL', 3),
('se_account_takeover', 'set_risk', 'account_takeover', 'Account Takeover', 'Potential account takeover detected', 'CRITICAL', 4);
