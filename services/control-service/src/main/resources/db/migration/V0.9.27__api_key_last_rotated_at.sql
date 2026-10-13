-- API Key 自动轮换：补最近一次轮换时间轴。
-- V0.2.0 已建 auto_rotate / rotation_days 配置列，但无任何执行端读取（死字段）；
-- V0.9.10 起由 control-service 的 rotateDueApiKeys 定时扫描到期 key 换发签名密钥，
-- 到期判定需要「上一次轮换时间」，故补本列。
-- 存量行留 NULL：首次判定按 created_at 计时（见 ControlService.rotateDueApiKeys 注释）。
ALTER TABLE api_keys ADD COLUMN last_rotated_at TIMESTAMP NULL;
