-- Player Data Query (P4 运营工具)
-- 游戏服上报充值流水与登录日志，控制面按 playerId 跨游戏查询

CREATE TABLE player_payments (
    id VARCHAR(48) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(64),
    player_id VARCHAR(128) NOT NULL,
    order_id VARCHAR(128) NOT NULL,
    platform VARCHAR(30),
    product_id VARCHAR(100),
    amount DECIMAL(18,4) NOT NULL,
    currency VARCHAR(10),
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    paid_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pp_game_order UNIQUE (game_id, order_id),
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_pp_player ON player_payments(game_id, player_id);

CREATE TABLE player_login_logs (
    id VARCHAR(48) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(64),
    player_id VARCHAR(128) NOT NULL,
    device_id VARCHAR(200),
    device_type VARCHAR(50),
    platform VARCHAR(50),
    app_version VARCHAR(50),
    ip_address VARCHAR(50),
    country VARCHAR(10),
    login_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_pll_player ON player_login_logs(game_id, player_id, login_at);
CREATE INDEX idx_pll_device ON player_login_logs(device_id);
