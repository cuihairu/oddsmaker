-- DimensionAgentConfig（dimension-sync.md 未落地项收口 / Agent 侧 agent.properties 的 Control 下发迁移）
-- Control 侧按游戏配置 Sync Agent 的属性面（Control 面替代游戏方本地 agent.properties）：
--   props 为非密钥属性文本（java.util.Properties 格式，键名沿用 agent.properties 的 dot-key 命名），
--   密钥键（网关 API key、DB/Kafka 口令、证书库口令、状态上报 token）走 secrets_encrypted，
--   AES-GCM 整体密文（base64(iv+ciphertext)）托管，任何管理面 API 响应不回显。
-- game.id / game.environment / status.source-key 不入库：由本行 (game_id, environment, agent_key) 在
-- 下发时注入；agent.checkpoint-path 是游戏方本地文件系统路径，永远本地保留、不下发。
-- 运行态观测仍走 dimension_sync_status（source_key=agent_key，上报口径不变）。

CREATE TABLE dimension_agent_config (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    agent_key VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    props TEXT NOT NULL,
    secrets_encrypted TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_dimension_agent_config UNIQUE (game_id, environment, agent_key)
);

CREATE INDEX idx_dimension_agent_config_game ON dimension_agent_config(game_id);
