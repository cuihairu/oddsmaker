-- 唯一约束并发幂等回归专用（H2 兼容，省略 games FK）：op_mails/op_mail_claims/player_payments。
CREATE TABLE op_mails (
    id varchar(32) NOT NULL,
    game_id varchar(32) NOT NULL,
    environment_id varchar(64),
    title varchar(200) NOT NULL,
    content TEXT NOT NULL,
    attachments TEXT,
    recipients varchar(4000),
    scope varchar(20) NOT NULL CHECK (scope IN ('ALL','INDIVIDUAL')),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','SENT','EXPIRED')),
    created_by varchar(64),
    expire_at timestamp(6),
    sent_at timestamp(6),
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL,
    deleted_at timestamp(6),
    PRIMARY KEY (id)
);
CREATE TABLE op_mail_claims (
    id varchar(48) NOT NULL,
    mail_id varchar(32) NOT NULL,
    player_key varchar(128) NOT NULL,
    game_id varchar(32) NOT NULL,
    claimed_attachments TEXT,
    claimed_at timestamp(6) NOT NULL,
    created_at timestamp(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_claim_mail_player UNIQUE (mail_id, player_key)
);
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
    CONSTRAINT uk_pp_game_order UNIQUE (game_id, order_id)
);
CREATE INDEX idx_pp_player ON player_payments(game_id, player_id);
