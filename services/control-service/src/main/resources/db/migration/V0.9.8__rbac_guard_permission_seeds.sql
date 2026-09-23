-- 死鉴权换血配套权限种子（id 用冒号——AccessGuard 按 PermissionEntity.id 精确匹配；code 沿用点号显示惯例）
-- —— 前置修正：V0.2.3 遗留的 perm_* 样式 id ——
-- game.read/user.read/audit.read/system.read 四个 code 在 V0.2.3 已落库(id=perm_*)，与本迁移撞 code；
-- AccessGuard 按冒号 id 精确匹配，故原地换 id 并同步 role_permissions 绑定（FK 临时降级；本库 POSTGRES_USER 为 superuser）。
SET session_replication_role = replica;
UPDATE role_permissions SET permission_id='game:read'   WHERE permission_id='perm_game_read';
UPDATE role_permissions SET permission_id='user:read'   WHERE permission_id='perm_user_read';
UPDATE role_permissions SET permission_id='audit:read'  WHERE permission_id='perm_audit_read';
UPDATE role_permissions SET permission_id='system:read' WHERE permission_id='perm_system_read';
UPDATE permissions SET id='game:read', name='查看游戏数据', description='查看游戏维度数据（看板/指标/明细查询）', resource='GAME', operation='READ', applicable_scope='GAME_AND_BELOW', category='game_management', display_order=140, status='ACTIVE', is_system=TRUE, type='API', resource_type='game', action='READ', scope='GAME', enabled=TRUE, system=TRUE WHERE id='perm_game_read' AND code='game.read';
UPDATE permissions SET id='user:read', name='查看用户', description='查看用户列表、详情与统计', resource='USER', operation='READ', applicable_scope='GLOBAL', category='security', display_order=146, status='ACTIVE', is_system=TRUE, type='API', resource_type='user', action='READ', scope='GLOBAL', enabled=TRUE, system=TRUE WHERE id='perm_user_read' AND code='user.read';
UPDATE permissions SET id='audit:read', name='查看审计日志', description='查询审计日志与统计', resource='AUDIT_LOG', operation='READ', applicable_scope='GLOBAL', category='audit', display_order=177, status='ACTIVE', is_system=TRUE, type='API', resource_type='audit', action='READ', scope='GLOBAL', enabled=TRUE, system=TRUE WHERE id='perm_audit_read' AND code='audit.read';
UPDATE permissions SET id='system:read', name='查看系统配置', description='查看系统配置/功能开关/系统状态', resource='SYSTEM', operation='READ', applicable_scope='GLOBAL', category='system', display_order=188, status='ACTIVE', is_system=TRUE, type='API', resource_type='system', action='READ', scope='GLOBAL', enabled=TRUE, system=TRUE WHERE id='perm_system_read' AND code='system.read';
SET session_replication_role = default;
-- Part A：23 个已转换控制器已引用但从未落库的 id（initializeDefaults() 仅测试调用，生产库此前不存在 → 非 admin 恒 403 死锁）
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('game:update','game.update','更新游戏数据','写入游戏维度数据（兑换码/配置等）','GAME','WRITE','GAME_AND_BELOW','game_management',141,'ACTIVE',TRUE,'API','game','UPDATE','GAME',TRUE,TRUE),
('risk_rule:read','riskrule.read','查看风控规则','查看风控规则列表与详情','RISK_RULE','READ','GAME_AND_BELOW','risk',142,'ACTIVE',TRUE,'API','risk_rule','READ','GAME',TRUE,TRUE),
('risk_rule:create','riskrule.create','创建风控规则','创建风控规则','RISK_RULE','CREATE','GAME_AND_BELOW','risk',143,'ACTIVE',TRUE,'API','risk_rule','CREATE','GAME',TRUE,TRUE),
('risk_rule:update','riskrule.update','更新风控规则','修改/启停风控规则','RISK_RULE','UPDATE','GAME_AND_BELOW','risk',144,'ACTIVE',TRUE,'API','risk_rule','UPDATE','GAME',TRUE,TRUE),
('risk_rule:delete','riskrule.delete','删除风控规则','删除风控规则','RISK_RULE','DELETE','GAME_AND_BELOW','risk',145,'ACTIVE',TRUE,'API','risk_rule','DELETE','GAME',TRUE,TRUE),
('user:update','user.update','管理用户','创建/更新/删除用户、角色、锁定与 2FA','USER','UPDATE','GLOBAL','security',147,'ACTIVE',TRUE,'API','user','UPDATE','GLOBAL',TRUE,TRUE);

-- Part B：18 控制器死 @PreAuthorize 换 AccessGuard 的新域词汇
INSERT INTO permissions (id, code, name, description, resource, operation, applicable_scope,
                         category, display_order, status, is_system,
                         type, resource_type, action, scope, enabled, system) VALUES
('ml:read','ml.read','查看 ML 模型','查看模型/训练/预测与统计','ML_MODEL','READ','GAME_AND_BELOW','ml',148,'ACTIVE',TRUE,'API','ml','READ','GAME',TRUE,TRUE),
('ml:manage','ml.manage','管理 ML 模型','创建/更新/归档/删除模型与 A/B 配置','ML_MODEL','WRITE','GAME_AND_BELOW','ml',149,'ACTIVE',TRUE,'API','ml','UPDATE','GAME',TRUE,TRUE),
('ml:train','ml.train','训练 ML 模型','创建与推进训练任务','ML_MODEL','EXECUTE','GAME_AND_BELOW','ml',150,'ACTIVE',TRUE,'API','ml','EXECUTE','GAME',TRUE,TRUE),
('ml:deploy','ml.deploy','部署 ML 模型','模型部署与下线','ML_MODEL','EXECUTE','GAME_AND_BELOW','ml',151,'ACTIVE',TRUE,'API','ml','DEPLOY','GAME',TRUE,TRUE),
('ml:use','ml.use','使用 ML 模型','记录预测/反馈等推理侧调用','ML_MODEL','EXECUTE','GAME_AND_BELOW','ml',152,'ACTIVE',TRUE,'API','ml','PREDICT','GAME',TRUE,TRUE),
('sdkkey:read','sdkkey.read','查看 SDK 密钥','查看 SDK 密钥与统计','SDK_KEY','READ','GAME_AND_BELOW','developer',153,'ACTIVE',TRUE,'API','sdk_key','READ','GAME',TRUE,TRUE),
('sdkkey:manage','sdkkey.manage','管理 SDK 密钥','创建/更新/吊销 SDK 密钥','SDK_KEY','WRITE','GAME_AND_BELOW','developer',154,'ACTIVE',TRUE,'API','sdk_key','UPDATE','GAME',TRUE,TRUE),
('sdkversion:read','sdkversion.read','查看 SDK 版本','查看 SDK 版本与平台最新版','SDK_VERSION','READ','GAME_AND_BELOW','developer',155,'ACTIVE',TRUE,'API','sdk_version','READ','GAME',TRUE,TRUE),
('sdkversion:manage','sdkversion.manage','管理 SDK 版本','创建/发布/弃用/退役 SDK 版本','SDK_VERSION','WRITE','GAME_AND_BELOW','developer',156,'ACTIVE',TRUE,'API','sdk_version','UPDATE','GAME',TRUE,TRUE),
('telemetry:read','telemetry.read','查看遥测配置','查看遥测配置与生效配置','TELEMETRY','READ','GAME_AND_BELOW','developer',157,'ACTIVE',TRUE,'API','telemetry','READ','GAME',TRUE,TRUE),
('telemetry:manage','telemetry.manage','管理遥测配置','创建/更新/启停/删除遥测配置','TELEMETRY','WRITE','GAME_AND_BELOW','developer',158,'ACTIVE',TRUE,'API','telemetry','UPDATE','GAME',TRUE,TRUE),
('integration:read','integration.read','查看集成','查看集成配置、日志与统计','INTEGRATION','READ','GAME_AND_BELOW','integration',159,'ACTIVE',TRUE,'API','integration','READ','GAME',TRUE,TRUE),
('integration:manage','integration.manage','管理集成','创建/更新/启停/删除集成并验证','INTEGRATION','WRITE','GAME_AND_BELOW','integration',160,'ACTIVE',TRUE,'API','integration','UPDATE','GAME',TRUE,TRUE),
('integration:trigger','integration.trigger','触发集成','手动触发集成调用','INTEGRATION','EXECUTE','GAME_AND_BELOW','integration',161,'ACTIVE',TRUE,'API','integration','EXECUTE','GAME',TRUE,TRUE),
('pipeline:read','pipeline.read','查看数据管线','查看管线/任务与统计','PIPELINE','READ','GAME_AND_BELOW','pipeline',162,'ACTIVE',TRUE,'API','pipeline','READ','GAME',TRUE,TRUE),
('pipeline:manage','pipeline.manage','管理数据管线','创建/更新/启停管线','PIPELINE','WRITE','GAME_AND_BELOW','pipeline',163,'ACTIVE',TRUE,'API','pipeline','UPDATE','GAME',TRUE,TRUE),
('pipeline:execute','pipeline.execute','执行数据管线','手动执行管线','PIPELINE','EXECUTE','GAME_AND_BELOW','pipeline',164,'ACTIVE',TRUE,'API','pipeline','EXECUTE','GAME',TRUE,TRUE),
('qualityrule:read','qualityrule.read','查看质量规则','查看数据质量规则','QUALITY_RULE','READ','GAME_AND_BELOW','pipeline',165,'ACTIVE',TRUE,'API','quality_rule','READ','GAME',TRUE,TRUE),
('qualityrule:manage','qualityrule.manage','管理质量规则','创建数据质量规则','QUALITY_RULE','WRITE','GAME_AND_BELOW','pipeline',166,'ACTIVE',TRUE,'API','quality_rule','UPDATE','GAME',TRUE,TRUE),
('ratelimit:read','ratelimit.read','查看限流规则','查看限流规则与统计','RATE_LIMIT','READ','GAME_AND_BELOW','platform',167,'ACTIVE',TRUE,'API','rate_limit','READ','GAME',TRUE,TRUE),
('ratelimit:manage','ratelimit.manage','管理限流规则','创建/更新/删除限流规则','RATE_LIMIT','WRITE','GAME_AND_BELOW','platform',168,'ACTIVE',TRUE,'API','rate_limit','UPDATE','GAME',TRUE,TRUE),
('quota:read','quota.read','查看配额','查看配额与统计','QUOTA','READ','GAME_AND_BELOW','platform',169,'ACTIVE',TRUE,'API','quota','READ','GAME',TRUE,TRUE),
('quota:manage','quota.manage','管理配额','创建/更新/删除配额并更新用量','QUOTA','WRITE','GAME_AND_BELOW','platform',170,'ACTIVE',TRUE,'API','quota','UPDATE','GAME',TRUE,TRUE),
('risk:manage','risk.manage','管理风控','解除封禁/升级/取消与分配评审','RISK','WRITE','GAME_AND_BELOW','risk',171,'ACTIVE',TRUE,'API','risk','UPDATE','GAME',TRUE,TRUE),
('risk:review','risk.review','评审风控','领取/处理评审队列条目','RISK','EXECUTE','GAME_AND_BELOW','risk',172,'ACTIVE',TRUE,'API','risk','REVIEW','GAME',TRUE,TRUE),
('cohort:manage','cohort.manage','管理用户分群','创建与计算用户分群','COHORT','WRITE','GAME_AND_BELOW','analytics',173,'ACTIVE',TRUE,'API','cohort','UPDATE','GAME',TRUE,TRUE),
('export:execute','export.execute','执行数据导出','创建/处理/取消导出任务','EXPORT','EXECUTE','GAME_AND_BELOW','analytics',174,'ACTIVE',TRUE,'API','export','EXECUTE','GAME',TRUE,TRUE),
('funnel:read','funnel.read','查看漏斗','查看漏斗配置与统计','FUNNEL','READ','GAME_AND_BELOW','analytics',175,'ACTIVE',TRUE,'API','funnel','READ','GAME',TRUE,TRUE),
('funnel:manage','funnel.manage','管理漏斗','创建/更新/删除漏斗与步骤','FUNNEL','WRITE','GAME_AND_BELOW','analytics',176,'ACTIVE',TRUE,'API','funnel','UPDATE','GAME',TRUE,TRUE),
('audit:sensitive','audit.sensitive','查看敏感审计','查看失败/认证/敏感操作审计','AUDIT_LOG','READ','GLOBAL','audit',178,'ACTIVE',TRUE,'API','audit','READ_SENSITIVE','GLOBAL',TRUE,TRUE),
('audit:manage','audit.manage','管理审计日志','清理历史审计日志','AUDIT_LOG','WRITE','GLOBAL','audit',179,'ACTIVE',TRUE,'API','audit','UPDATE','GLOBAL',TRUE,TRUE),
('security:read','security.read','查看安全配置','查看 MFA/SSO/会话/安全策略','SECURITY','READ','GLOBAL','security',180,'ACTIVE',TRUE,'API','security','READ','GLOBAL',TRUE,TRUE),
('security:manage','security.manage','管理安全配置','管理 SSO 配置与会话','SECURITY','WRITE','GLOBAL','security',181,'ACTIVE',TRUE,'API','security','UPDATE','GLOBAL',TRUE,TRUE),
('metrics:read','metrics.read','查看性能指标','查看 API/事件/风控/业务指标','METRICS','READ','GLOBAL','monitoring',182,'ACTIVE',TRUE,'API','metrics','READ','GLOBAL',TRUE,TRUE),
('metrics:infra','metrics.infra','查看基础设施指标','查看数据库/Kafka/系统指标','METRICS','READ','GLOBAL','monitoring',183,'ACTIVE',TRUE,'API','metrics','READ_INFRA','GLOBAL',TRUE,TRUE),
('health:read','health.read','查看健康检查','查看健康检查与指标','HEALTH','READ','GLOBAL','monitoring',184,'ACTIVE',TRUE,'API','health','READ','GLOBAL',TRUE,TRUE),
('health:manage','health.manage','管理健康检查','手动执行健康检查','HEALTH','WRITE','GLOBAL','monitoring',185,'ACTIVE',TRUE,'API','health','UPDATE','GLOBAL',TRUE,TRUE),
('maintenance:read','maintenance.read','查看维护窗口','查看维护窗口','MAINTENANCE','READ','GLOBAL','system',186,'ACTIVE',TRUE,'API','maintenance','READ','GLOBAL',TRUE,TRUE),
('maintenance:manage','maintenance.manage','管理维护窗口','创建/启动/完成/取消维护窗口','MAINTENANCE','WRITE','GLOBAL','system',187,'ACTIVE',TRUE,'API','maintenance','UPDATE','GLOBAL',TRUE,TRUE),
('system:manage','system.manage','管理系统配置','修改系统配置','SYSTEM','WRITE','GLOBAL','system',189,'ACTIVE',TRUE,'API','system','UPDATE','GLOBAL',TRUE,TRUE),
('featureflag:read','featureflag.read','查看功能开关','查看功能开关列表与状态','FEATURE_FLAG','READ','GLOBAL','system',190,'ACTIVE',TRUE,'API','feature_flag','READ','GLOBAL',TRUE,TRUE),
('featureflag:manage','featureflag.manage','管理功能开关','启用/禁用功能开关与灰度设置','FEATURE_FLAG','WRITE','GLOBAL','system',191,'ACTIVE',TRUE,'API','feature_flag','UPDATE','GLOBAL',TRUE,TRUE);

-- 显式角色绑定（V0.2.3 的 INSERT..SELECT 只覆盖当时已有权限，新权限需自行绑定）
-- 1) operator/game_admin：全部 52 条
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('game:read','game:update','risk_rule:read','risk_rule:create','risk_rule:update','risk_rule:delete',
  'user:read','user:update','ml:read','ml:manage','ml:train','ml:deploy','ml:use',
  'sdkkey:read','sdkkey:manage','sdkversion:read','sdkversion:manage','telemetry:read','telemetry:manage',
  'integration:read','integration:manage','integration:trigger',
  'pipeline:read','pipeline:manage','pipeline:execute','qualityrule:read','qualityrule:manage',
  'ratelimit:read','ratelimit:manage','quota:read','quota:manage','risk:manage','risk:review',
  'cohort:manage','export:execute','funnel:read','funnel:manage',
  'audit:read','audit:sensitive','audit:manage','security:read','security:manage',
  'metrics:read','metrics:infra','health:read','health:manage',
  'maintenance:read','maintenance:manage','system:read','system:manage','featureflag:read','featureflag:manage')
  AND r.id IN ('role_operator','role_game_admin') ON CONFLICT DO NOTHING;

-- 2) 六只读角色：read 类 19 条
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('game:read','risk_rule:read','ml:read','sdkkey:read','sdkversion:read','telemetry:read',
  'integration:read','pipeline:read','qualityrule:read','ratelimit:read','quota:read',
  'funnel:read','audit:read','security:read','metrics:read','health:read',
  'maintenance:read','system:read','featureflag:read')
  AND r.id IN ('role_analyst','role_finance','role_viewer','role_marketing','role_qa','role_developer') ON CONFLICT DO NOTHING;

-- 3) analyst 扩展：分群/管线执行/模型使用/导出/集成触发/风控评审
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('cohort:manage','pipeline:execute','ml:use','export:execute','integration:trigger','risk:review')
  AND r.id = 'role_analyst' ON CONFLICT DO NOTHING;

-- 4) finance 扩展：数据导出
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id = 'export:execute' AND r.id = 'role_finance' ON CONFLICT DO NOTHING;

-- 5) developer 扩展：模型训练/使用、SDK 版本与遥测管理
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE p.id IN ('ml:train','ml:use','sdkversion:manage','telemetry:manage')
  AND r.id = 'role_developer';
