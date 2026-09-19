-- 数据正确性攻坚：DB 种子列值 vs JPA 枚举映射漂移病例修复
-- 背景：全仓 @Enumerated(STRING) 演示审计发现 4 类漂移（roles.type 已在 V0.9.9 修复）

-- 病例 3：V0.2.3 的 38 行 perm_* 种子用旧列（resource/operation/applicable_scope）INSERT，
-- type/action/scope 三列 V0.8.7 才补建且无回填 → 全 NULL（getFullPermission NPE / flush 违反 NOT NULL /
-- findByResourceTypeAndAction 永远查不到）。按旧列推导回填（旧列未删，CASE 可依据）；
-- APPROVE→MANAGE 为收敛（无 guard 消费方，不为它扩 PermissionAction）
UPDATE permissions SET
  type = 'API',
  action = CASE operation
    WHEN 'READ' THEN 'READ' WHEN 'WRITE' THEN 'UPDATE' WHEN 'DELETE' THEN 'DELETE'
    WHEN 'ADMIN' THEN 'ADMIN' WHEN 'APPROVE' THEN 'MANAGE' WHEN 'EXPORT' THEN 'EXPORT' END,
  scope = CASE applicable_scope WHEN 'GAME_AND_BELOW' THEN 'GAME' ELSE 'GLOBAL' END
WHERE type IS NULL AND action IS NULL AND scope IS NULL;

-- 病例 4：V0.6.1 种子 int_1（SLACK）auth_type='WEBHOOK' 不在 AuthType 枚举 → 水化即炸。
-- SLACK incoming webhook 本就无鉴权，NONE 语义正确（IntegrationEntity.authType 无业务逻辑消费，仅展示）
UPDATE integrations SET auth_type = 'NONE' WHERE id = 'int_1' AND auth_type = 'WEBHOOK';

-- 病例 5：audit_logs.status V0.8.8 补列无默认 → 存量行 NULL（isSuccess() 拆箱 NPE 风险）。
-- 按写路径同款 result→status 映射回填（AuditLogService：result=FAILURE→FAILURE，否则 SUCCESS）
UPDATE audit_logs SET status = CASE WHEN result = 'FAILURE' THEN 'FAILURE' ELSE 'SUCCESS' END
WHERE status IS NULL;

-- 病例 6：api_keys.key_type DDL 默认 'PRODUCTION' 不在 ApiKeyType 枚举（防患，无种子行；
-- Java 写路径 ControlService 显式设值不受影响，仅封死绕过 Hibernate 的 INSERT 种雷路径）
ALTER TABLE api_keys ALTER COLUMN key_type SET DEFAULT 'CLIENT';
