-- 环境表 id 列宽：GameService 生成的环境 id 形如 env_{gameId}_{environmentName}，
-- 服务端生成的 gameId 为 game_+16hex（21 字符），拼 _staging 即 33 字符，超过 varchar(32)
-- —— dev(29)/prod(31) 恰好存活、staging 必炸（干净库创建游戏 500 的根因）。
-- e2e 老库用短 id（e2e_game）从未踩中。环境名最长 50（name 列宽），上限按 4+21+1+50=76
-- 取整为 100。api_keys.environment_id 有外键引用，同步扩宽保持一致。
ALTER TABLE game_environments ALTER COLUMN id TYPE varchar(100);
ALTER TABLE api_keys ALTER COLUMN environment_id TYPE varchar(100);
