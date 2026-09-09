-- Player Data Export (P4 运营工具)
-- 按 (game_id, player_id) 打包导出玩家数据（档案/充值/登录/兑换），文件落盘、到期清理

CREATE TABLE player_export_jobs (
    id VARCHAR(48) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(64),
    player_id VARCHAR(128) NOT NULL,
    export_format VARCHAR(20) NOT NULL DEFAULT 'json',   -- json: 单文件；csv: 按分区打包 zip
    sections TEXT,                                        -- 导出分区 JSON 数组：profile/payments/login-logs/redeem-records
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',       -- PENDING/PROCESSING/COMPLETED/FAILED/EXPIRED
    file_name VARCHAR(255),
    file_path VARCHAR(500),
    file_size_bytes BIGINT,
    row_count BIGINT,
    error_message TEXT,
    requested_by VARCHAR(64),
    expires_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_pex_game_player ON player_export_jobs(game_id, player_id, created_at);
