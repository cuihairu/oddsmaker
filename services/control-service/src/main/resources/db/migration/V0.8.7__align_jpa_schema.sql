-- 对齐 JPA 实体与迁移 schema，使 ddl-auto=validate 可通过（此前 e2e 靠 ddl-auto=none 压制）
-- 裁决原则：schema 真源是迁移——缺表缺列在此补齐；列名错位/类型不符的改实体对齐（不入迁移）

-- 1) UserEntity.scopes @ElementCollection 落表（实体声明了 user_scopes，迁移从未建过）
CREATE TABLE user_scopes (
    user_id varchar(64) NOT NULL,
    scope varchar(255),
    CONSTRAINT fk_user_scopes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 2) users 补实体已有列（UserService/UserPortal 在用）
ALTER TABLE users ADD COLUMN username varchar(100);
ALTER TABLE users ADD COLUMN language varchar(10);
ALTER TABLE users ADD COLUMN login_count bigint NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN keycloak_id varchar(100);

CREATE UNIQUE INDEX uk_users_username ON users (username) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX uk_users_keycloak_id ON users (keycloak_id) WHERE keycloak_id IS NOT NULL;

-- 3) permissions 补实体已有枚举列（PermissionEntity.type/action/scope，STRING 存储）
ALTER TABLE permissions ADD COLUMN type varchar(255);
ALTER TABLE permissions ADD COLUMN action varchar(255);
ALTER TABLE permissions ADD COLUMN scope varchar(255);
