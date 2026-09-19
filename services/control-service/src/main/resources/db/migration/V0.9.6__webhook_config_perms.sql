-- Webhook 配置 CRUD 权限（id 用冒号——AccessGuard 按 PermissionEntity.id 精确匹配；code 沿用点号显示惯例）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('webhook:read','webhook.read','查看 Webhook 配置','查看 Webhook 配置、发送日志与统计','WEBHOOK','READ','GAME_AND_BELOW','integration',136,'ACTIVE',TRUE,'API','webhook','READ','GAME',TRUE,TRUE),
('webhook:manage','webhook.manage','管理 Webhook 配置','创建/修改/删除 Webhook 配置并测试发送','WEBHOOK','WRITE','GAME_AND_BELOW','integration',137,'ACTIVE',TRUE,'API','webhook','UPDATE','GAME',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('webhook:read','webhook:manage') AND r.id IN ('role_operator','role_game_admin');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'webhook:read' AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer');
