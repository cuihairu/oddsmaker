-- B2 权限单真源：把 users.roles（user_roles 集合表，@ElementCollection）回填为全局作用域
-- user_role_assignments（PermissionService.findValidByUserId 是权限门唯一读取对象；
-- users.roles 自 V0.9.16 起只作展示投影，不再被任何权限判定读取）。
--
-- 映射规则：role_id = 'role_' || lower(枚举名)，且 JOIN roles 表——roles 表不存在该 id
-- 的（ADMIN/MANAGER/SUPER_ADMIN：登录即有 ROLE_ADMIN 直通，roles 表无对应行，见 V0.2.3 种子）
-- 直接不产生行，避免指向不存在角色的哑行（与 UserService.syncGlobalRoleAssignments 同口径）。
--
-- 幂等：目标角色已有任一「启用且全局」行即跳过重新插入；可安全重跑。
INSERT INTO user_role_assignments (user_id, role_id, game_id, environment, enabled, assigned_by, assigned_at)
SELECT ur.user_id,
       'role_' || lower(ur.role),
       NULL,
       NULL,
       TRUE,
       'backfill-v0.9.16',
       now()
FROM user_roles ur
JOIN roles r ON r.id = 'role_' || lower(ur.role)
WHERE NOT EXISTS (
    SELECT 1
    FROM user_role_assignments a
    WHERE a.user_id = ur.user_id
      AND a.role_id = 'role_' || lower(ur.role)
      AND a.game_id IS NULL
      AND a.environment IS NULL
      AND a.enabled IS TRUE
);