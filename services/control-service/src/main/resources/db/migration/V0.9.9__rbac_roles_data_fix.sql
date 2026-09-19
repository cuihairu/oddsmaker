-- RBAC roles 表数据修复：使角色分配链路（assign/list/鉴权）真正可运转
-- 1) type 枚举炸弹：V0.2.3 种子 8 行的 type 列值（OPERATOR/GAME_ADMIN/ANALYST/MARKETING/
--    FINANCE/DEVELOPER/VIEWER/QA）不在 RoleEntity.RoleType 枚举（SYSTEM/CUSTOM）内，
--    @Enumerated(STRING) 水化即抛——existsById/findById 对 role_* 行全部失败，
--    assignRole/listAssignments/hasPermission（鉴权链路）分配了也过不了。
--    改为 SYSTEM：与 is_system=TRUE 语义一致；RoleRepo 的 @Query 仅按 'CUSTOM'/'SYSTEM'
--    字面量过滤（findCustomRoles/getRoleStatistics），统计归 systemRoles 无副作用。
UPDATE roles SET type = 'SYSTEM'
WHERE id IN ('role_operator', 'role_game_admin', 'role_analyst', 'role_marketing',
             'role_finance', 'role_developer', 'role_viewer', 'role_qa')
  AND type <> 'SYSTEM';

-- 2) V0.8.8 补列未带 DEFAULT：存量行 enabled/system 为 NULL——isEnabled() 拆箱 NPE、
--    findByEnabledTrue 不命中（角色下拉/可分配白名单为空）。统一置 TRUE（种子角色均启用内置）。
UPDATE roles SET enabled = TRUE WHERE enabled IS NULL;

UPDATE roles SET "system" = TRUE WHERE "system" IS NULL;
