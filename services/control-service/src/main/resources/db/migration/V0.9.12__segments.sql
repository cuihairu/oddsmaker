-- Segments（P7-2 可复用用户分群，竞品差距收敛）
-- 定义一次、处处可用：定义存 Postgres（JSON），成员物化在 ClickHouse segment_members（见 schema/sql/clickhouse/segments.sql）

CREATE TABLE segments (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    description VARCHAR(1000),
    definition TEXT NOT NULL,
    subject VARCHAR(10) NOT NULL DEFAULT 'player',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    member_count BIGINT NOT NULL DEFAULT 0,
    last_computed_at TIMESTAMP,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_segments_game_name UNIQUE (game_id, name)
);

CREATE INDEX idx_segments_game ON segments(game_id);

-- 权限种子：segment:read / segment:manage（对齐 cohort:manage 的角色绑定面）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('segment:read','segment.read','查看分群','查看用户分群定义、状态与成员规模','SEGMENT','READ','GAME_AND_BELOW','analytics',196,'ACTIVE',TRUE,'API','segment','READ','GAME',TRUE,TRUE),
('segment:manage','segment.manage','管理分群','创建/更新/删除用户分群并触发生成员物化','SEGMENT','WRITE','GAME_AND_BELOW','analytics',197,'ACTIVE',TRUE,'API','segment','UPDATE','GAME',TRUE,TRUE);

-- 1) operator/game_admin：读写
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('segment:read','segment:manage')
  AND r.id IN ('role_operator','role_game_admin') ON CONFLICT DO NOTHING;

-- 2) 六只读角色：读
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'segment:read'
  AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer') ON CONFLICT DO NOTHING;

-- 3) analyst 扩展：分群管理（对齐 cohort:manage）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'segment:manage' AND r.id = 'role_analyst' ON CONFLICT DO NOTHING;
