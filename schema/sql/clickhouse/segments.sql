-- Segments（P7-2 可复用用户分群）：成员物化表
-- 主体口径与平台一致：player_id > user_id > device_id（见 OnlineMetricsService.SUBJECT）
-- ReplacingMergeTree(computed_at)：同一 (game, env, segment, subject) 重复物化按时间新替旧
CREATE TABLE IF NOT EXISTS segment_members
(
  segment_id String,
  game_id LowCardinality(String),
  environment LowCardinality(String),
  subject_id String,
  computed_at DateTime64(3)
)
ENGINE = ReplacingMergeTree(computed_at)
PARTITION BY (game_id, environment, segment_id)
ORDER BY (game_id, environment, segment_id, subject_id);

-- 报表侧过滤口径（示例）：
--   AND if(player_id != '', player_id, if(user_id != '', user_id, device_id)) IN (
--     SELECT subject_id FROM segment_members
--     WHERE game_id = ? AND environment = ? AND segment_id = ?)
