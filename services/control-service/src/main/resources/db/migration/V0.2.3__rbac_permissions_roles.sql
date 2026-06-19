-- RBAC: Permissions and Roles
-- Phase 2: Company RBAC implementation

-- Permissions table
CREATE TABLE permissions (
    id VARCHAR(32) PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    resource VARCHAR(50) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    applicable_scope VARCHAR(20) NOT NULL DEFAULT 'ALL',
    category VARCHAR(100),
    display_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_permissions_resource ON permissions(resource);
CREATE INDEX idx_permissions_category ON permissions(category);
CREATE INDEX idx_permissions_status ON permissions(status);

-- Roles table
CREATE TABLE roles (
    id VARCHAR(32) PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    type VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    scope VARCHAR(20) NOT NULL DEFAULT 'GAME',
    parent_role_id VARCHAR(32),
    level INTEGER DEFAULT 0,
    inherit_permissions BOOLEAN DEFAULT TRUE,
    preset_permissions TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_system BOOLEAN DEFAULT FALSE,
    is_default BOOLEAN DEFAULT FALSE,
    user_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by VARCHAR(64),
    FOREIGN KEY (parent_role_id) REFERENCES roles(id)
);

CREATE INDEX idx_roles_type ON roles(type);
CREATE INDEX idx_roles_scope ON roles(scope);
CREATE INDEX idx_roles_level ON roles(level);
CREATE INDEX idx_roles_status ON roles(status);

-- Role-Permission junction table
CREATE TABLE role_permissions (
    role_id VARCHAR(32) NOT NULL,
    permission_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

CREATE INDEX idx_role_permissions_permission ON role_permissions(permission_id);

-- Update user_roles table to support new RBAC system
ALTER TABLE user_roles ADD COLUMN role_id VARCHAR(32);
ALTER TABLE user_roles ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES roles(id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);

-- Insert default system permissions
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope, category, display_order, status, is_system) VALUES
-- Game management permissions
('perm_game_read', 'game.read', '查看游戏', '查看游戏基本信息和配置', 'GAME', 'READ', 'ALL', 'game_management', 1, 'ACTIVE', TRUE),
('perm_game_write', 'game.write', '编辑游戏', '编辑游戏配置和设置', 'GAME', 'WRITE', 'GAME_AND_BELOW', 'game_management', 2, 'ACTIVE', TRUE),
('perm_game_delete', 'game.delete', '删除游戏', '删除游戏（仅全局）', 'GAME', 'DELETE', 'GLOBAL_ONLY', 'game_management', 3, 'ACTIVE', TRUE),
('perm_game_admin', 'game.admin', '游戏管理', '完整游戏管理权限', 'GAME', 'ADMIN', 'ALL', 'game_management', 4, 'ACTIVE', TRUE),

-- Environment permissions
('perm_env_read', 'environment.read', '查看环境', '查看环境配置', 'ENVIRONMENT', 'READ', 'ALL', 'game_management', 10, 'ACTIVE', TRUE),
('perm_env_write', 'environment.write', '编辑环境', '编辑环境配置', 'ENVIRONMENT', 'WRITE', 'GAME_AND_BELOW', 'game_management', 11, 'ACTIVE', TRUE),
('perm_env_delete', 'environment.delete', '删除环境', '删除环境', 'ENVIRONMENT', 'DELETE', 'GAME_AND_BELOW', 'game_management', 12, 'ACTIVE', TRUE),

-- API Key permissions
('perm_key_read', 'apikey.read', '查看API密钥', '查看API密钥列表和详情', 'API_KEY', 'READ', 'ALL', 'game_management', 20, 'ACTIVE', TRUE),
('perm_key_write', 'apikey.write', '管理API密钥', '创建和编辑API密钥', 'API_KEY', 'WRITE', 'GAME_AND_BELOW', 'game_management', 21, 'ACTIVE', TRUE),
('perm_key_delete', 'apikey.delete', '删除API密钥', '删除API密钥', 'API_KEY', 'DELETE', 'GAME_AND_BELOW', 'game_management', 22, 'ACTIVE', TRUE),

-- Tracking Plan permissions
('perm_tp_read', 'trackingplan.read', '查看追踪计划', '查看追踪计划定义', 'TRACKING_PLAN', 'READ', 'ALL', 'tracking_plan', 30, 'ACTIVE', TRUE),
('perm_tp_write', 'trackingplan.write', '编辑追踪计划', '创建和编辑追踪计划', 'TRACKING_PLAN', 'WRITE', 'GAME_AND_BELOW', 'tracking_plan', 31, 'ACTIVE', TRUE),
('perm_tp_delete', 'trackingplan.delete', '删除追踪计划', '删除追踪计划', 'TRACKING_PLAN', 'DELETE', 'GAME_AND_BELOW', 'tracking_plan', 32, 'ACTIVE', TRUE),
('perm_tp_approve', 'trackingplan.approve', '激活追踪计划', '激活追踪计划', 'TRACKING_PLAN', 'APPROVE', 'GAME_AND_BELOW', 'tracking_plan', 33, 'ACTIVE', TRUE),

-- Event Definition permissions
('perm_event_read', 'event.read', '查看事件定义', '查看事件定义详情', 'EVENT_DEFINITION', 'READ', 'ALL', 'tracking_plan', 40, 'ACTIVE', TRUE),
('perm_event_write', 'event.write', '编辑事件定义', '创建和编辑事件定义', 'EVENT_DEFINITION', 'WRITE', 'GAME_AND_BELOW', 'tracking_plan', 41, 'ACTIVE', TRUE),
('perm_event_delete', 'event.delete', '删除事件定义', '删除事件定义', 'EVENT_DEFINITION', 'DELETE', 'GAME_AND_BELOW', 'tracking_plan', 42, 'ACTIVE', TRUE),

-- Analytics permissions
('perm_analytics_read', 'analytics.read', '查看分析', '查看分析报告和图表', 'ANALYTICS', 'READ', 'ALL', 'analytics', 50, 'ACTIVE', TRUE),
('perm_analytics_write', 'analytics.write', '创建分析', '创建自定义分析图表', 'ANALYTICS', 'WRITE', 'GAME_AND_BELOW', 'analytics', 51, 'ACTIVE', TRUE),
('perm_analytics_export', 'analytics.export', '导出数据', '导出分析数据', 'ANALYTICS', 'EXPORT', 'ALL', 'analytics', 52, 'ACTIVE', TRUE),

-- Events Data permissions
('perm_events_read', 'events.read', '查看事件数据', '查看原始事件数据', 'EVENTS_DATA', 'READ', 'ALL', 'data_management', 60, 'ACTIVE', TRUE),
('perm_events_export', 'events.export', '导出事件数据', '导出原始事件数据', 'EVENTS_DATA', 'EXPORT', 'GAME_AND_BELOW', 'data_management', 61, 'ACTIVE', TRUE),

-- Policy permissions
('perm_policy_read', 'policy.read', '查看策略', '查看隐私、采样、限流策略', 'PRIVACY_POLICY', 'READ', 'ALL', 'policy_management', 70, 'ACTIVE', TRUE),
('perm_policy_write', 'policy.write', '编辑策略', '创建和编辑策略', 'PRIVACY_POLICY', 'WRITE', 'GAME_AND_BELOW', 'policy_management', 71, 'ACTIVE', TRUE),
('perm_policy_delete', 'policy.delete', '删除策略', '删除策略', 'PRIVACY_POLICY', 'DELETE', 'GAME_AND_BELOW', 'policy_management', 72, 'ACTIVE', TRUE),

-- User management permissions
('perm_user_read', 'user.read', '查看用户', '查看用户列表和详情', 'USER', 'READ', 'ALL', 'user_management', 80, 'ACTIVE', TRUE),
('perm_user_write', 'user.write', '管理用户', '创建和编辑用户', 'USER', 'WRITE', 'ALL', 'user_management', 81, 'ACTIVE', TRUE),
('perm_user_delete', 'user.delete', '删除用户', '删除用户', 'USER', 'DELETE', 'ALL', 'user_management', 82, 'ACTIVE', TRUE),
('perm_user_invite', 'user.invite', '邀请用户', '邀请新用户', 'USER', 'WRITE', 'ALL', 'user_management', 83, 'ACTIVE', TRUE),

-- Role management permissions
('perm_role_read', 'role.read', '查看角色', '查看角色列表和详情', 'ROLE', 'READ', 'ALL', 'user_management', 90, 'ACTIVE', TRUE),
('perm_role_write', 'role.write', '编辑角色', '创建和编辑自定义角色', 'ROLE', 'WRITE', 'ALL', 'user_management', 91, 'ACTIVE', TRUE),
('perm_role_delete', 'role.delete', '删除角色', '删除自定义角色', 'ROLE', 'DELETE', 'ALL', 'user_management', 92, 'ACTIVE', TRUE),
('perm_role_assign', 'role.assign', '分配角色', '给用户分配角色', 'ROLE', 'WRITE', 'ALL', 'user_management', 93, 'ACTIVE', TRUE),

-- System permissions
('perm_system_read', 'system.read', '查看系统配置', '查看系统配置', 'SYSTEM', 'READ', 'GLOBAL_ONLY', 'system_management', 100, 'ACTIVE', TRUE),
('perm_system_write', 'system.write', '编辑系统配置', '编辑系统配置', 'SYSTEM', 'WRITE', 'GLOBAL_ONLY', 'system_management', 101, 'ACTIVE', TRUE),
('perm_audit_read', 'audit.read', '查看审计日志', '查看审计日志', 'AUDIT_LOG', 'READ', 'ALL', 'system_management', 110, 'ACTIVE', TRUE),
('perm_storage_read', 'storage.read', '查看存储策略', '查看存储策略配置', 'STORAGE_PROFILE', 'READ', 'GLOBAL_ONLY', 'system_management', 120, 'ACTIVE', TRUE),
('perm_storage_write', 'storage.write', '编辑存储策略', '编辑存储策略配置', 'STORAGE_PROFILE', 'WRITE', 'GLOBAL_ONLY', 'system_management', 121, 'ACTIVE', TRUE);

-- Insert default system roles
INSERT INTO roles (id, code, name, description, type, scope, parent_role_id, level, inherit_permissions, status, is_system, is_default) VALUES
-- Global roles
('role_operator', 'operator', '运营管理员', '公司级运营管理员，拥有所有权限', 'OPERATOR', 'GLOBAL', NULL, 0, TRUE, 'ACTIVE', TRUE, FALSE),
('role_game_admin', 'game_admin', '游戏管理员', '游戏级管理员，管理特定游戏', 'GAME_ADMIN', 'GAME', 'role_operator', 1, TRUE, 'ACTIVE', TRUE, FALSE),

-- Game-level roles
('role_analyst', 'analyst', '数据分析师', '查看分析报告，创建图表', 'ANALYST', 'GAME', 'role_game_admin', 2, FALSE, 'ACTIVE', TRUE, FALSE),
('role_marketing', 'marketing', '市场营销', '用户分析和A/B测试', 'MARKETING', 'GAME', 'role_game_admin', 2, FALSE, 'ACTIVE', TRUE, FALSE),
('role_finance', 'finance', '财务', '收入和商业化数据', 'FINANCE', 'GAME', 'role_game_admin', 2, FALSE, 'ACTIVE', TRUE, FALSE),
('role_developer', 'developer', '开发者', '技术配置权限', 'DEVELOPER', 'GAME', 'role_game_admin', 2, FALSE, 'ACTIVE', TRUE, FALSE),

-- Environment-level roles
('role_viewer', 'viewer', '观察者', '只读访问', 'VIEWER', 'ENVIRONMENT', 'role_analyst', 3, FALSE, 'ACTIVE', TRUE, TRUE),
('role_qa', 'qa', '测试', '测试环境权限', 'QA', 'ENVIRONMENT', 'role_developer', 3, FALSE, 'ACTIVE', TRUE, FALSE);

-- Assign permissions to Operator role (all permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_operator', id FROM permissions WHERE deleted_at IS NULL;

-- Assign permissions to Game Admin role (exclude global-only)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_game_admin', id FROM permissions
WHERE deleted_at IS NULL
AND applicable_scope IN ('ALL', 'GAME_AND_BELOW')
AND resource NOT IN ('SYSTEM', 'STORAGE_PROFILE');

-- Assign permissions to Analyst role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_analyst', id FROM permissions
WHERE deleted_at IS NULL
AND resource IN ('GAME', 'ENVIRONMENT', 'TRACKING_PLAN', 'EVENT_DEFINITION', 'ANALYTICS', 'EVENTS_DATA')
AND operation IN ('READ', 'WRITE', 'EXPORT');

-- Assign permissions to Marketing role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_marketing', id FROM permissions
WHERE deleted_at IS NULL
AND resource IN ('GAME', 'ANALYTICS', 'EVENTS_DATA')
AND operation IN ('READ', 'WRITE', 'EXPORT');

-- Assign permissions to Finance role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_finance', id FROM permissions
WHERE deleted_at IS NULL
AND resource IN ('GAME', 'ANALYTICS', 'EVENTS_DATA')
AND operation IN ('READ', 'EXPORT');

-- Assign permissions to Developer role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_developer', id FROM permissions
WHERE deleted_at IS NULL
AND applicable_scope IN ('ALL', 'GAME_AND_BELOW')
AND resource IN ('GAME', 'ENVIRONMENT', 'API_KEY', 'TRACKING_PLAN', 'EVENT_DEFINITION', 'EVENTS_DATA')
AND operation IN ('READ', 'WRITE');

-- Assign permissions to Viewer role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_viewer', id FROM permissions
WHERE deleted_at IS NULL
AND resource IN ('GAME', 'ENVIRONMENT', 'TRACKING_PLAN', 'EVENT_DEFINITION', 'ANALYTICS', 'EVENTS_DATA')
AND operation = 'READ';

-- Assign permissions to QA role
INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role_qa', id FROM permissions
WHERE deleted_at IS NULL
AND applicable_scope IN ('ALL', 'GAME_AND_BELOW')
AND resource IN ('GAME', 'ENVIRONMENT', 'TRACKING_PLAN', 'EVENT_DEFINITION', 'EVENTS_DATA')
AND operation IN ('READ', 'WRITE');
