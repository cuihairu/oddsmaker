-- 数据保留策略自动化：ClickHouse TTL 对账状态表 + 权限种子

-- 一行一 CH 表的对账状态（upsert：每次 sweep 全量刷新受管表）；
-- actual/desired 天数、状态机 IN_SYNC/UPDATING/FAILED/SKIPPED_NO_CONFIG/UNKNOWN
CREATE TABLE retention_enforcements (
    ch_table VARCHAR(100) PRIMARY KEY,                 -- CH 表名（受管白名单）
    ttl_column VARCHAR(100) NOT NULL,                  -- TTL 基准列
    actual_days INTEGER,                               -- 当前生效 TTL 天数（null=无 TTL）
    desired_days INTEGER,                              -- 配置期望天数（null=无有效配置）
    status VARCHAR(24) NOT NULL,                       -- 见上
    last_checked_at TIMESTAMP NOT NULL,
    last_updated_at TIMESTAMP,                         -- 最近一次实际下发 ALTER 的时间
    error_message TEXT,
    update_count INTEGER NOT NULL DEFAULT 0            -- 累计下发 ALTER 次数
);

-- 权限：id 用冒号（AccessGuard 按 PermissionEntity.id 精确匹配），code 沿用点号显示惯例；
-- 挂 compliance 类目，display_order 接 privacy 132/133 之后
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('retention:read','retention.read','查看数据保留','查看 ClickHouse 数据保留（TTL）对账状态','RETENTION','READ','GLOBAL','compliance',134,'ACTIVE',TRUE,'API','retention','READ','GAME',TRUE,TRUE),
('retention:manage','retention.manage','管理数据保留','手动触发 ClickHouse TTL 对账修正','RETENTION','WRITE','GLOBAL','compliance',135,'ACTIVE',TRUE,'API','retention','UPDATE','GAME',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('retention:read','retention:manage') AND r.id IN ('role_operator','role_game_admin');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'retention:read' AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer');
