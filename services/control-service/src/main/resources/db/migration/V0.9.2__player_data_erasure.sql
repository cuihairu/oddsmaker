-- 玩家数据删除请求（GDPR erasure）：行级明细硬删 + 风控记录匿名化占位 + 权限种子

-- 删除请求：输入任一标识，经身份图谱展开全集合后按状态机异步清洗（PG 硬删 + CH mutation）
CREATE TABLE player_erasure_requests (
    id VARCHAR(48) PRIMARY KEY,                        -- "per_" + 24hex
    game_id VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',     -- PENDING/PROCESSING/COMPLETED/PARTIAL/FAILED/CANCELLED
    request_type VARCHAR(20) NOT NULL,                 -- PLAYER_ID / USER_ID / DEVICE_ID
    request_value VARCHAR(200) NOT NULL,               -- 输入标识原值
    resolved_identities TEXT,                          -- JSON：展开后全量标识集合（创建时固化）
    execution_summary TEXT,                            -- JSON：pgDone/pg 各表行数/ch 状态与逐表确认/retries
    scheduled_for TIMESTAMP,                           -- null = 立即执行（sweep 只处理到期的）
    error_message TEXT,
    requested_by VARCHAR(64),
    cancelled_by VARCHAR(64),
    cancelled_at TIMESTAMP,
    started_at TIMESTAMP,                              -- 僵尸 PROCESSING 重置依据
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE INDEX idx_per_game ON player_erasure_requests(game_id, created_at);
CREATE INDEX idx_per_status ON player_erasure_requests(status);

-- 权限：id 用冒号（AccessGuard 按 PermissionEntity.id 精确匹配），code 沿用点号显示惯例；
-- category 新建 compliance（隐私合规独立于告警/数据管理域，display_order 接 alert 130/131 之后）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('privacy:read','privacy.read','查看隐私合规','查看玩家数据删除请求与执行结果','PRIVACY','READ','GAME_AND_BELOW','compliance',132,'ACTIVE',TRUE,'API','privacy','READ','GAME',TRUE,TRUE),
('privacy:manage','privacy.manage','管理隐私合规','创建/取消玩家数据删除请求','PRIVACY','WRITE','GAME_AND_BELOW','compliance',133,'ACTIVE',TRUE,'API','privacy','CREATE','GAME',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('privacy:read','privacy:manage') AND r.id IN ('role_operator','role_game_admin');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'privacy:read' AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer');
