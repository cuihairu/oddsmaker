-- Flink 作业管理权限（id 用冒号——AccessGuard 按 PermissionEntity.id 精确匹配；code 沿用点号显示惯例）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('flink:read','flink.read','查看 Flink 作业','查看 Flink 作业列表、配置、关联规则与统计','FLINK','READ','GAME_AND_BELOW','platform',138,'ACTIVE',TRUE,'API','flink','READ','GAME',TRUE,TRUE),
('flink:manage','flink.manage','管理 Flink 作业','创建/部署/停止 Flink 作业并同步集群状态','FLINK','WRITE','GAME_AND_BELOW','platform',139,'ACTIVE',TRUE,'API','flink','UPDATE','GAME',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('flink:read','flink:manage') AND r.id IN ('role_operator','role_game_admin');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'flink:read' AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer');
