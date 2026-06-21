-- 维度表 DDL（P4 横向 / dimension-sync.md 落地）
-- 维度数据和事实数据分离：物品/关卡定义从游戏侧同步过来，分析查询时在 ClickHouse 内 join。
-- 设计详见 docs/zh/reference/dimension-sync.md

-- 物品维度表（ReplacingMergeTree 保留最新版本）
-- 同一 resource_id 重复写入时，version_ts 最大的行胜出
CREATE TABLE IF NOT EXISTS item_dim
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  resource_id String,
  name String DEFAULT '',
  type String DEFAULT '',
  rarity String DEFAULT '',
  category String DEFAULT '',
  description String DEFAULT '',
  version_ts DateTime64(3),
  is_current UInt8 DEFAULT 1
)
ENGINE = ReplacingMergeTree(version_ts)
PARTITION BY (game_id, environment, toYYYYMM(version_ts))
ORDER BY (game_id, environment, resource_id);

-- 关卡维度表
CREATE TABLE IF NOT EXISTS level_dim
(
  game_id LowCardinality(String),
  environment LowCardinality(String),
  level_id String,
  name String DEFAULT '',
  difficulty String DEFAULT '',
  chapter String DEFAULT '',
  version_ts DateTime64(3),
  is_current UInt8 DEFAULT 1
)
ENGINE = ReplacingMergeTree(version_ts)
PARTITION BY (game_id, environment, toYYYYMM(version_ts))
ORDER BY (game_id, environment, level_id);

-- 查询当前版本物品（去掉被替换的旧版本）
-- SELECT * FROM item_dim FINAL WHERE game_id = 'game_demo' AND environment = 'prod';
--
-- 事实表 join 维度表：
-- SELECT e.event_name, d.name AS item_name, d.rarity, sum(e.resource_amount)
-- FROM events e
-- ANY LEFT JOIN item_dim d ON e.resource_id = d.resource_id
--     AND e.game_id = d.game_id AND e.environment = d.environment
-- WHERE e.event_type = 'resource' AND d.is_current = 1
-- GROUP BY e.event_name, d.name, d.rarity;
