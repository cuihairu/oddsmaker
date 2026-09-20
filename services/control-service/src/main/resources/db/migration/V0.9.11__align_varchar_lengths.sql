-- 实体 @Column(length) 与迁移 VARCHAR 列宽漂移对齐（16 轮侦察：12 处）
-- 背景：实体注解 length 只影响 H2 建表（test profile flyway disabled + create-drop 按实体注解），
-- 生产 PG 按迁移建列——漂移列上超长写入在测试全绿、生产 500（value too long for type character varying）。
-- V0.9.0 的 staging 环境创建必炸是同族实锤：env_{gameId}_staging=33 字符 > varchar(32)，
-- dev/prod 恰好存活、e2e 老库短 id 双盲。
-- 修复方向：扩迁移列宽到实体声明（实体 length 是业务上限；收缩实体 = 砍功能，不做）。
-- users 五个资料字段（name/company/title/phone/display_name）是自由输入且前端无 maxlength，超长即 500；
-- two_factor_secret 当前 Base32(20B)=32 字符恰好占满零余量；id/审计列为防御一致性对齐。

ALTER TABLE users ALTER COLUMN id TYPE VARCHAR(64);
ALTER TABLE users ALTER COLUMN name TYPE VARCHAR(200);
ALTER TABLE users ALTER COLUMN display_name TYPE VARCHAR(200);
ALTER TABLE users ALTER COLUMN company TYPE VARCHAR(200);
ALTER TABLE users ALTER COLUMN title TYPE VARCHAR(100);
ALTER TABLE users ALTER COLUMN phone TYPE VARCHAR(50);
ALTER TABLE users ALTER COLUMN two_factor_secret TYPE VARCHAR(100);

ALTER TABLE audit_logs ALTER COLUMN user_id TYPE VARCHAR(64);
ALTER TABLE audit_logs ALTER COLUMN resource_id TYPE VARCHAR(64);
ALTER TABLE audit_logs ALTER COLUMN request_id TYPE VARCHAR(100);

ALTER TABLE roles ALTER COLUMN id TYPE VARCHAR(50);
ALTER TABLE permissions ALTER COLUMN id TYPE VARCHAR(100);
