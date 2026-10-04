-- B3 Server SDK 骨架：游戏级 server 事件能力开关。
-- SERVER 型 API key（持有 secret、强制 HMAC）只允许发放给启用该能力的游戏：
-- ControlService.createKey 对 SERVER 档位校验此开关，未启用直接拒绝创建
-- （06 计划书 §4.3：充值/经济类结算只认 server 事件，能力默认收口）。
ALTER TABLE games ADD COLUMN IF NOT EXISTS server_events_enabled BOOLEAN NOT NULL DEFAULT FALSE;
