-- DimensionPullConfig（HTTP Pull 链路 / dimension-sync.md HTTP Pull 落地）
-- Control 侧按游戏配置定期拉游戏方查询接口（GET {endpoint}?updated_after=&limit=，Bearer 凭证加密托管），
-- 翻译成 RawDimensionChange(source_type=pull) 走既有 /v1/batch 事件入口。
-- 断点：cursor = 服务端 next_cursor（缺省回落本页最大 version_ts），推送成功才前进。

CREATE TABLE dimension_pull_config (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    source_key VARCHAR(100) NOT NULL,
    dim_type VARCHAR(20) NOT NULL DEFAULT 'item',
    endpoint TEXT NOT NULL,
    credential_encrypted TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    interval_seconds INT NOT NULL DEFAULT 300,
    page_limit INT NOT NULL DEFAULT 1000,
    last_cursor TEXT,
    last_pull_at TIMESTAMP,
    last_event_ts TIMESTAMP,
    pushed_count BIGINT NOT NULL DEFAULT 0,
    error_count BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_dimension_pull_config UNIQUE (game_id, environment, source_key)
);

CREATE INDEX idx_dimension_pull_config_game ON dimension_pull_config(game_id);
