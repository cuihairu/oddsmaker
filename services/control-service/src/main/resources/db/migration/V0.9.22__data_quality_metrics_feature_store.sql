-- B10 数据质量与共享 Feature（计划书 §5.3/B10 行，设计定稿 07-b10-data-quality-feature-store §4/§5）
-- data_quality_metrics：网关边缘五指标计数，5 分钟窗口，(game_id, environment, window_start, window_sec) 唯一。
--   率一律读时计算不落列（分子分母同行，落率必然出现第二份真相）；全零行不写，网关只报有流量的窗口。
-- feature_store：risk-job 特征作业与 risk_features 同源双写的摘要表，scope×窗口一行，features 为扁平 JSON 串。
--   列型用 TEXT：control 侧只存取原串、查询走 (scope, window_end) 索引，无需 json 算子；
--   出现 json 查询需求时 ALTER COLUMN features TYPE jsonb USING features::jsonb 即可。

CREATE TABLE data_quality_metrics (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_sec INT NOT NULL DEFAULT 300,
    received BIGINT NOT NULL DEFAULT 0,
    accepted BIGINT NOT NULL DEFAULT 0,
    sampled_out BIGINT NOT NULL DEFAULT 0,
    rejected_schema BIGINT NOT NULL DEFAULT 0,
    rejected_unknown_event BIGINT NOT NULL DEFAULT 0,
    rejected_invalid_timestamp BIGINT NOT NULL DEFAULT 0,
    rejected_pii_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_payload_too_large BIGINT NOT NULL DEFAULT 0,
    rejected_trust_escalation BIGINT NOT NULL DEFAULT 0,
    rejected_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_scope_mismatch BIGINT NOT NULL DEFAULT 0,
    rejected_kafka_error BIGINT NOT NULL DEFAULT 0,
    duplicates_gateway BIGINT NOT NULL DEFAULT 0,
    duplicates_enrich BIGINT NOT NULL DEFAULT 0,
    late BIGINT NOT NULL DEFAULT 0,
    dlq_other BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_data_quality_window UNIQUE (game_id, environment, window_start, window_sec)
);

-- 健康页取数路径：某游戏环境最近窗口序列
CREATE INDEX idx_data_quality_recent
    ON data_quality_metrics (game_id, environment, window_start DESC);

CREATE TABLE feature_store (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    features TEXT NOT NULL,
    as_of TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_feature_store_window UNIQUE (game_id, environment, scope_key, window_start, window_end)
);

-- 消费方取数路径：主体+特征取最近窗口行（与 risk_features 的 idx_risk_features_lookup 同构）
CREATE INDEX idx_feature_store_scope
    ON feature_store (game_id, environment, scope_key, window_end DESC);
