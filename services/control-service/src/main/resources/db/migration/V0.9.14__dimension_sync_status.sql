-- DimensionSyncStatus（P4 横向 / 维度同步 Agent 落地）
-- Agent 在游戏方内网推送维度后上报同步位点/心跳；Control 侧落表供 /api/dimensions/sync-status 查询同步延迟
-- 设计详见 docs/zh/reference/dimension-sync.md

CREATE TABLE dimension_sync_status (
    id VARCHAR(64) PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    source_key VARCHAR(100) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    cursor TEXT,
    last_event_ts TIMESTAMP,
    last_push_at TIMESTAMP,
    pushed_count BIGINT NOT NULL DEFAULT 0,
    error_count BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_dimension_sync_status UNIQUE (game_id, environment, source_key)
);

CREATE INDEX idx_dimension_sync_status_game ON dimension_sync_status(game_id);

-- 权限种子：dimension:read / dimension:manage（角色绑定面对齐 segment/dashboard）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('dimension:read','dimension.read','查看维度同步','查看维度同步状态与同步延迟','DIMENSION','READ','GAME_AND_BELOW','analytics',200,'ACTIVE',TRUE,'API','dimension','READ','GAME',TRUE,TRUE),
('dimension:manage','dimension.manage','管理维度同步','上报维度同步位点/心跳与同步状态管理','DIMENSION','WRITE','GAME_AND_BELOW','analytics',201,'ACTIVE',TRUE,'API','dimension','UPDATE','GAME',TRUE,TRUE);

-- 1) operator/game_admin：读写
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('dimension:read','dimension:manage')
  AND r.id IN ('role_operator','role_game_admin') ON CONFLICT DO NOTHING;

-- 2) 六只读角色：读
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'dimension:read'
  AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer') ON CONFLICT DO NOTHING;

-- 3) analyst/developer 扩展：管理（Agent 位点上报属接入侧工作）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'dimension:manage' AND r.id IN ('role_analyst','role_developer') ON CONFLICT DO NOTHING;
