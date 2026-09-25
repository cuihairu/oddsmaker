-- Dashboards（P7-3 自定义仪表盘，竞品差距收敛）
-- 布局 JSON 存 Postgres；widget 数据由前端按 source 白名单调用既有报表 API 拉取

CREATE TABLE dashboards (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    layout TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_dashboards_game_name UNIQUE (game_id, name)
);

CREATE INDEX idx_dashboards_game ON dashboards(game_id);

-- 权限种子：dashboard:read / dashboard:manage（角色绑定面对齐 segment）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('dashboard:read','dashboard.read','查看仪表盘','查看自定义仪表盘布局','DASHBOARD','READ','GAME_AND_BELOW','analytics',198,'ACTIVE',TRUE,'API','dashboard','READ','GAME',TRUE,TRUE),
('dashboard:manage','dashboard.manage','管理仪表盘','创建/更新/删除自定义仪表盘布局','DASHBOARD','WRITE','GAME_AND_BELOW','analytics',199,'ACTIVE',TRUE,'API','dashboard','UPDATE','GAME',TRUE,TRUE);

-- 1) operator/game_admin：读写
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('dashboard:read','dashboard:manage')
  AND r.id IN ('role_operator','role_game_admin') ON CONFLICT DO NOTHING;

-- 2) 六只读角色：读
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'dashboard:read'
  AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer') ON CONFLICT DO NOTHING;

-- 3) analyst 扩展：仪表盘管理
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'dashboard:manage' AND r.id = 'role_analyst' ON CONFLICT DO NOTHING;
