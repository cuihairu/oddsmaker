-- Remote Config / LiveOps 联动（P6）
-- 游戏级远程配置：运营端 CRUD + 游戏服/SDK 拉取；环境特定配置覆盖全环境配置。

CREATE TABLE remote_configs (
    id VARCHAR(48) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(64) NOT NULL DEFAULT '',   -- 空 = 全环境（被环境特定配置覆盖）
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT NOT NULL,                        -- JSON 值
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / INACTIVE
    version BIGINT NOT NULL DEFAULT 1,                 -- 每次更新自增，客户端增量判断
    description VARCHAR(500),
    updated_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_rc_game_env_key UNIQUE (game_id, environment_id, config_key),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_rc_game_env ON remote_configs(game_id, environment_id, status);
