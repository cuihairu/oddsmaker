-- 烟测专用迁移：只证明 Flyway 自动配置真的跑起来了（写 H2 内存库，与 PG 迁移集无关）。
-- 真实迁移集由 E2E_PG 档在真 PostgreSQL 上验证。
CREATE TABLE smoke_probe (id INTEGER PRIMARY KEY);
INSERT INTO smoke_probe (id) VALUES (1);
