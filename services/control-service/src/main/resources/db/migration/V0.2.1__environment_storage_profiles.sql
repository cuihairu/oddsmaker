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

ALTER TABLE game_environments ADD COLUMN IF NOT EXISTS storage_profile_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_storage_profiles_name ON storage_profiles(name);
CREATE INDEX IF NOT EXISTS idx_storage_profiles_active ON storage_profiles(is_active);
CREATE INDEX IF NOT EXISTS idx_game_environments_storage_profile_id ON game_environments(storage_profile_id);

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

UPDATE game_environments
SET storage_profile_id = CASE
    WHEN type = 'PRODUCTION' THEN 'shared-prod'
    ELSE 'shared-nonprod'
END
WHERE storage_profile_id IS NULL;
