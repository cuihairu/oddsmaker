-- MLModelArtifact（P4.4 收尾 / ml 训练产物写回链路）
-- ml/ 训练管线的版本化 JSON 产物（feature_names + coefficients + intercept / multiplier）
-- 注册进 Control 后供批量打分优先使用（校验不过或未注册则回落启发式，两条路径以
-- predictions.model_id / model_version 与返回体 path 字段区分）。
-- 产物 schema 详见 ml/README.md（schema_version=1）。

CREATE TABLE ml_model_artifacts (
    id VARCHAR(32) PRIMARY KEY,
    game_id VARCHAR(64) NOT NULL,
    model_type VARCHAR(20) NOT NULL,
    model_version VARCHAR(40) NOT NULL,
    source VARCHAR(20),
    trained_at VARCHAR(40),
    feature_names TEXT,
    coefficients TEXT,
    intercept DECIMAL(12,8),
    multiplier DECIMAL(12,6),
    metrics TEXT,
    heuristic_baseline TEXT,
    artifact_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_ml_model_artifact UNIQUE (game_id, model_type, model_version)
);

CREATE INDEX idx_ml_model_artifacts_game_type ON ml_model_artifacts(game_id, model_type);
