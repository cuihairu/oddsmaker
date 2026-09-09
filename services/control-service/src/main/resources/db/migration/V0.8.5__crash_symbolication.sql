-- Crash/Error 符号化（P6 智能化）
-- symbol_mappings 增加映射规则列：JSON 数组 [{pattern, replacement}]（正则），
-- 符号化服务按 (game, platform, version) 匹配 ACTIVE 映射后对混淆堆栈执行规则替换。

ALTER TABLE symbol_mappings ADD COLUMN mapping_rules TEXT;

COMMENT ON COLUMN symbol_mappings.mapping_rules IS '符号化规则 JSON 数组 [{pattern, replacement}]，正则替换混淆符号';
