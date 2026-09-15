-- 本地登录补齐：password_hash 列 V0.2.0 建表时就有，但实体此前未映射、
-- 登录端点缺失，控制面前端 /api/auth/login 一直是死接口。
-- 初始口令 admin/admin123（bcrypt），生产环境登录后必须立即修改。

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

-- user_roles 重建为 UserEntity.roles 的 @ElementCollection 形态（user_id + role）。
-- V0.2.0 建的是 14 列重表（id/scope NOT NULL 无默认），Hibernate 插入只写
-- user_id+role 两列必炸 NOT NULL；此前 users 表为空从未写入，坑未暴露。
-- 历史数据无（表空），直接重建；环境级角色授权在 user_role_assignments（V0.8.8）。
-- v_user_permissions 视图依赖旧表结构（无迁移来源、无代码引用的孤儿视图），
-- drop 后按新语义从 user_role_assignments 重建。
DROP VIEW IF EXISTS v_user_permissions;
DROP TABLE IF EXISTS user_roles;
CREATE TABLE user_roles (
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);

-- seed 初始管理员，两条路径均幂等：
-- 1) 老库存在历史半成品管理员（username 为 NULL、email=admin@oddsmaker.local，
--    如 e2e 库的 user_admin 行）→ 归一化收编：补 username/口令/状态；
-- 2) 干净库无此行 → INSERT；ON CONFLICT 谓词必须与部分唯一索引
--    uk_users_username (WHERE username IS NOT NULL) 匹配。
-- 初始口令 admin/admin123（bcrypt），生产环境登录后必须立即修改。
UPDATE users
SET username = 'admin',
    password_hash = '$2a$10$YTqLJcRz7u6KIFkooEkX/egGxX91Agy0wjqKTRrJPo8Tufy0pn5cS',
    status = 'ACTIVE',
    deleted_at = NULL,
    updated_at = now()
WHERE email = 'admin@oddsmaker.local'
  AND (username IS NULL OR username = '');

INSERT INTO users (id, username, email, status, display_name, created_at, updated_at, login_count, password_hash)
SELECT 'seed-admin-0001', 'admin', 'admin@oddsmaker.local', 'ACTIVE', 'Administrator',
        now(), now(), 0,
        '$2a$10$YTqLJcRz7u6KIFkooEkX/egGxX91Agy0wjqKTRrJPo8Tufy0pn5cS'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin')
ON CONFLICT (username) WHERE username IS NOT NULL DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    status = 'ACTIVE',
    deleted_at = NULL,
    updated_at = now();

-- 角色挂到实际 admin 行（老库收编后 id 保留原值，如 user_admin）
INSERT INTO user_roles (user_id, role)
SELECT u.id, 'ADMIN'
FROM users u
WHERE u.username = 'admin'
  AND NOT EXISTS (
    SELECT 1 FROM user_roles ur
    WHERE ur.user_id = u.id AND ur.role = 'ADMIN'
  );

-- 环境级权限视图（重建）：全局角色取 users，游戏/环境级取 user_role_assignments
CREATE VIEW v_user_permissions AS
SELECT u.id AS user_id,
       u.email,
       u.name,
       u.global_role,
       ura.game_id,
       ura.environment AS environment_id,
       ura.role_id AS role,
       g.name AS game_name
FROM users u
LEFT JOIN user_role_assignments ura
       ON u.id = ura.user_id
      AND (ura.expires_at IS NULL OR ura.expires_at > now())
LEFT JOIN roles r ON ura.role_id = r.id
LEFT JOIN games g ON ura.game_id = g.id
WHERE u.deleted_at IS NULL;
