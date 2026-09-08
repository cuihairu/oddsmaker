-- Redeem Code System (P4 运营工具)
-- 三表结构：批次（奖励/限领/有效期/总量）、码（UNIQUE 一次性 / SHARED 通用）、兑换记录（发放凭据 + 防刷计数）

CREATE TABLE redeem_batches (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment_id VARCHAR(64),
    name VARCHAR(100) NOT NULL,
    reward TEXT NOT NULL,
    code_type VARCHAR(10) NOT NULL,
    total INT NOT NULL,
    per_user_limit INT NOT NULL,
    expires_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_rb_game ON redeem_batches(game_id);

CREATE TABLE redeem_codes (
    id VARCHAR(40) PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    code VARCHAR(32) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    redeemed_by VARCHAR(128),
    redeemed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_code UNIQUE (code)
);

CREATE INDEX idx_rc_batch ON redeem_codes(batch_id);

CREATE TABLE redeem_records (
    id VARCHAR(48) PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    game_id VARCHAR(32) NOT NULL,
    player_key VARCHAR(128) NOT NULL,
    seq INT NOT NULL,
    code VARCHAR(32) NOT NULL,
    reward TEXT NOT NULL,
    redeemed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rr_batch_player_seq UNIQUE (batch_id, player_key, seq)
);

CREATE INDEX idx_rr_batch ON redeem_records(batch_id);
CREATE INDEX idx_rr_player ON redeem_records(game_id, player_key);
