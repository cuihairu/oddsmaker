-- 策略实验室样本集（V0.4）：命名留档样本事件批次，改规则后重放对比
-- 样本为 JSON 数组（1~500 条，结构校验与试算回放 dry-run 同款）；留档不可改，删除重建

CREATE TABLE risk_sample_sets (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    samples TEXT NOT NULL,
    sample_count INT NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_risk_sample_sets_game_name UNIQUE (game_id, name)
);

CREATE INDEX idx_risk_sample_sets_game ON risk_sample_sets(game_id);
