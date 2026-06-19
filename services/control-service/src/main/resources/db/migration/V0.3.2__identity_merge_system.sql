-- Identity Merge System
-- Phase 3: Identity Merge for devices, users, players, characters

-- Identities table (unified identity)
CREATE TABLE identities (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(32),
    primary_identity_type VARCHAR(20) NOT NULL DEFAULT 'DEVICE',
    primary_id VARCHAR(200) NOT NULL,
    user_id VARCHAR(100),
    player_id VARCHAR(100),
    character_id VARCHAR(100),
    device_id VARCHAR(200),
    device_type VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    first_seen_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    session_count INTEGER DEFAULT 0,
    event_count BIGINT DEFAULT 0,
    merged_from TEXT,
    merge_reason VARCHAR(200),
    confidence_score DECIMAL(5,4) DEFAULT 1.0,
    attributes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    FOREIGN KEY (environment_id) REFERENCES game_environments(id)
);

CREATE INDEX idx_identities_game_id ON identities(game_id);
CREATE INDEX idx_identities_environment_id ON identities(environment_id);
CREATE INDEX idx_identities_user_id ON identities(user_id);
CREATE INDEX idx_identities_player_id ON identities(player_id);
CREATE INDEX idx_identities_device_id ON identities(device_id);
CREATE INDEX idx_identities_status ON identities(status);
CREATE INDEX idx_identities_last_seen ON identities(last_seen_at DESC);

-- Composite index for unique user lookup
CREATE UNIQUE INDEX idx_identities_user_unique ON identities(game_id, user_id) WHERE user_id IS NOT NULL AND deleted_at IS NULL;

-- Composite index for unique player lookup
CREATE UNIQUE INDEX idx_identities_player_unique ON identities(game_id, player_id) WHERE player_id IS NOT NULL AND deleted_at IS NULL;

-- Composite index for device uniqueness (allows multiple identities per device before merge)
CREATE INDEX idx_identities_device_game ON identities(game_id, device_id) WHERE device_id IS NOT NULL;

-- Identity Links table (relationships between identifiers)
CREATE TABLE identity_links (
    id VARCHAR(32) PRIMARY KEY,
    identity_id VARCHAR(32) NOT NULL,
    linked_identity_type VARCHAR(50) NOT NULL,
    linked_id VARCHAR(200) NOT NULL,
    link_type VARCHAR(20) NOT NULL DEFAULT 'ASSOCIATED',
    link_strength DECIMAL(5,4) DEFAULT 1.0,
    first_linked_at TIMESTAMP,
    last_confirmed_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_at TIMESTAMP,
    verification_method VARCHAR(50),
    link_source VARCHAR(100),
    source_event_id VARCHAR(100),
    usage_count BIGINT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    expired_at TIMESTAMP,
    FOREIGN KEY (identity_id) REFERENCES identities(id)
);

CREATE INDEX idx_identity_links_identity_id ON identity_links(identity_id);
CREATE INDEX idx_identity_links_type_id ON identity_links(linked_identity_type, linked_id);
CREATE INDEX idx_identity_links_status ON identity_links(status);
CREATE INDEX idx_identity_links_verification ON identity_links(verification_status);
CREATE INDEX idx_identity_links_expired_at ON identity_links(expired_at);

-- Unique constraint for active links
CREATE UNIQUE INDEX idx_identity_links_unique ON identity_links(identity_id, linked_identity_type, linked_id) WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- Index for finding links to expire
CREATE INDEX idx_identity_links_expire ON identity_links(expired_at) WHERE expired_at IS NOT NULL;
