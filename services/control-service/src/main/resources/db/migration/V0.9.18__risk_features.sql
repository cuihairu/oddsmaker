-- B5 风控 Feature 层：事件→特征→规则三段解耦的特征持久层（计划书 §5.1）。
-- risk-job 特征作业分支按事件时间滑动窗口聚合后 upsert 本表；规则评估从特征取值，
-- 不再直接对原始事件开窗取数。scope_key 编码主体：PLAYER:<user_id> / DEVICE:<device_id> / IP:<client_ip>。
-- 幂等：同窗口重算（迟到事件修正）按唯一键覆盖 value/as_of。

CREATE TABLE risk_features (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    feature_name VARCHAR(100) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    value DOUBLE PRECISION NOT NULL DEFAULT 0,
    as_of TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_risk_features_window UNIQUE (game_id, environment, scope_key, feature_name, window_start, window_end)
);

-- 规则评估取数路径：主体+特征取最近窗口行
CREATE INDEX idx_risk_features_lookup
    ON risk_features (game_id, environment, scope_key, feature_name, window_end DESC);
