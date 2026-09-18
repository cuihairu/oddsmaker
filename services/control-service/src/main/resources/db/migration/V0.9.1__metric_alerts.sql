-- 业务指标告警：规则表 + 告警归属（game_id/rule_id）+ 权限种子

-- 告警规则：按游戏配置 DAU/收入/崩溃率等业务指标的阈值或同比偏差告警
CREATE TABLE metric_alert_rules (
    id VARCHAR(32) PRIMARY KEY,                       -- "alr_" + 24hex
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    metric_type VARCHAR(20) NOT NULL,                 -- DAU / REVENUE / CRASH_RATE
    condition_type VARCHAR(20) NOT NULL,              -- ABSOLUTE / BASELINE_DEVIATION
    comparison VARCHAR(10) NOT NULL,                  -- GT / LT / BOTH(仅 BASELINE_DEVIATION)
    threshold DECIMAL(20,4),                          -- ABSOLUTE 必填
    deviation_pct DECIMAL(10,4),                      -- BASELINE_DEVIATION 必填（正数）
    environment VARCHAR(32),                          -- NULL = 全部环境
    eval_window VARCHAR(20) NOT NULL DEFAULT 'TODAY', -- TODAY / HOUR_1（window 是 PG 保留字，故加前缀）
    severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_webhook BOOLEAN NOT NULL DEFAULT TRUE,
    snooze_minutes INTEGER,                           -- 预留：静默期，v1 不做 UI
    last_evaluated_at TIMESTAMP,
    last_value DECIMAL(20,4),
    last_baseline DECIMAL(20,4),
    last_state VARCHAR(20) NOT NULL DEFAULT 'OK',     -- OK / FIRING
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_metric_alert_rules_game ON metric_alert_rules(game_id);
CREATE INDEX idx_metric_alert_rules_enabled ON metric_alert_rules(enabled);
CREATE UNIQUE INDEX idx_metric_alert_rules_name ON metric_alert_rules(game_id, name) WHERE deleted_at IS NULL;

-- 告警归属：业务告警按游戏过滤 + 沿触发去重定位
ALTER TABLE system_alerts ADD COLUMN game_id VARCHAR(32);
ALTER TABLE system_alerts ADD COLUMN rule_id VARCHAR(32);
CREATE INDEX idx_system_alerts_game_id ON system_alerts(game_id);
CREATE INDEX idx_system_alerts_rule_id ON system_alerts(rule_id);

-- 权限：id 用冒号（AccessGuard 按 PermissionEntity.id 精确匹配），code 沿用点号显示惯例
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('alert:read','alert.read','查看业务告警','查看告警规则与告警历史','ALERT','READ','GAME_AND_BELOW','alerting',130,'ACTIVE',TRUE,'API','alert','READ','GAME',TRUE,TRUE),
('alert:manage','alert.manage','管理业务告警','创建/修改/删除告警规则并处理告警','ALERT','WRITE','GAME_AND_BELOW','alerting',131,'ACTIVE',TRUE,'API','alert','UPDATE','GAME',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('alert:read','alert:manage') AND r.id IN ('role_operator','role_game_admin');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'alert:read' AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer');
