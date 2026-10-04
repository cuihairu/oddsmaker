# 游戏事件字典

更新时间：`2026-06-05`

这份字典用于把 `complier` 的第一阶段目标固定下来：统一事件名、固定核心字段、保留扩展空间。原则是“预置核心事件 + 自定义扩展字段”。

## 通用要求

- 所有事件使用 v1 统一包络：`event_id`, `event_name`, `game_id`, `environment`, `device_id`, `ts_client`
- `game_id + environment` 是采集、路由、ClickHouse 分区和看板查询的共同边界
- 账号态补 `user_id`
- 玩家态优先补 `player_id`，多角色游戏可补 `character_id`
- 会话态补 `session_id`
- 自定义业务字段放在 `props`
- 与收入直接相关的事件，优先同时填写顶层 `revenue_amount` 和 `revenue_currency`

## 契约 v2 增量字段（2026-10，B4）

在 v1 包络上只加字段、不改删（06 计划书 §4.2）。四个字段均为**网关权威**：缺省由网关回填，
发送方声明值不采信、不可自抬。

| 字段 | 类型 | 语义 | 接入方约定 |
| --- | --- | --- | --- |
| `event_version` | integer ≥1 | 事件契约主版本 | 缺省 1，SDK 可不传 |
| `source` | `client`\|`server`\|`system`\|`derived` | 生产来源 | server SDK 必带 `server`；client SDK 可不传（网关按 key 档位回填）；`system`/`derived` 为平台保留档，声明即拒收（`trust_escalation`） |
| `trust_level` | `LOW`\|`HIGH`\|`COMPUTED` | 由 source 推导（client→LOW；server/system→HIGH；derived→COMPUTED） | **任何接入方不得声明**，声明高于推导档即拒收；风控 BLOCK 级动作只认 `HIGH` |
| `event_origin` | string ≤64 | SDK 名+版本（如 `server-java/0.1.0`） | 审计回溯用；缺省网关填 `gateway` |

ClickHouse `events` 表对应列：`event_version`（DEFAULT 1）、`source`/`trust_level`/`event_origin`
（DEFAULT `''`，空串=契约 v2 前历史行；信任判定须精确匹配 `trust_level='HIGH'`）。

## 1. 身份与会话

| 事件名 | 用途 | 推荐 props |
| --- | --- | --- |
| `install` | 首次安装或首次打开 | `channel`, `campaign`, `creative_id` |
| `login` | 玩家登录 | `login_method`, `is_new_user` |
| `session_start` | 会话开始 | `scene`, `entry_point` |
| `session_end` | 会话结束 | `duration_sec`, `exit_reason` |

## 2. 新手与进度

| 事件名 | 用途 | 推荐 props |
| --- | --- | --- |
| `tutorial_start` | 教程开始 | `tutorial_id`, `step_id`, `step_index` |
| `tutorial_complete` | 教程完成 | `tutorial_id`, `duration_sec` |
| `level_start` | 关卡开始 | `level_id`, `game_mode`, `attempt`, `difficulty` |
| `level_fail` | 关卡失败 | `level_id`, `game_mode`, `attempt`, `fail_reason`, `duration_sec` |
| `level_complete` | 关卡完成 | `level_id`, `game_mode`, `attempt`, `duration_sec`, `score`, `stars` |

## 3. 经济系统

| 事件名 | 用途 | 推荐 props |
| --- | --- | --- |
| `currency_source` | 虚拟货币产出 | `currency_code`, `amount`, `source`, `reason`, `balance_after` |
| `currency_sink` | 虚拟货币消耗 | `currency_code`, `amount`, `sink`, `reason`, `balance_after` |
| `item_grant` | 道具发放 | `item_id`, `quantity`, `source`, `rarity` |
| `item_consume` | 道具消耗 | `item_id`, `quantity`, `sink`, `target_id` |

## 4. 变现

| 事件名 | 用途 | 推荐 props | 顶层收入字段 |
| --- | --- | --- | --- |
| `revenue` | 通用收入事件 | `order_id`, `product_id`, `store` | `revenue_amount`, `revenue_currency` |
| `iap_order` | 应用内购订单 | `order_id`, `product_id`, `store`, `validated` | `revenue_amount`, `revenue_currency` |
| `webshop_order` | 官网或 Webshop 订单 | `order_id`, `product_id`, `channel` | `revenue_amount`, `revenue_currency` |
| `ad_impression` | 广告展示收入 | `network`, `ad_unit_id`, `placement_id`, `ad_format`, `precision` | `revenue_amount`, `revenue_currency` |
| `rewarded_ad_complete` | 激励视频完成 | `network`, `ad_unit_id`, `placement_id`, `reward_id` | 可选 |

## 5. LiveOps 与社交

| 事件名 | 用途 | 推荐 props |
| --- | --- | --- |
| `event_entry` | 运营活动进入 | `liveops_event_id`, `entry_source`, `cohort` |
| `event_reward_claim` | 运营活动奖励领取 | `liveops_event_id`, `reward_id`, `reward_type`, `reward_amount` |
| `guild_join` | 加入公会 | `guild_id`, `guild_size` |
| `invite_sent` | 发起邀请 | `channel`, `target_platform` |

## 6. 技术健康与风控

| 事件名 | 用途 | 推荐 props |
| --- | --- | --- |
| `crash` | 崩溃事件 | `error_name`, `fatal`, `scene`, `app_build` |
| `fps_drop` | 帧率下降 | `fps`, `scene`, `device_tier` |
| `network_timeout` | 网络超时 | `endpoint`, `timeout_ms`, `network_type` |
| `cheat_flag` | 可疑作弊行为 | `rule_id`, `risk_level`, `evidence` |

## 7. 命名约束

- 事件名使用小写蛇形命名法，例如 `level_complete`
- 关键对象字段统一使用 `_id` 后缀，例如 `level_id`, `tutorial_id`, `item_id`
- 金额字段统一为 `amount`
- 虚拟货币统一为 `currency_code`
- 失败原因统一为 `fail_reason`

## 8. 当前仓库落点

- SDK helper：`sdks/web/src/index.ts`
- 原始事件 schema：`schema/avro/oddsmaker-event.avsc`, `schema/json/oddsmaker-event-schema.json`
- 游戏主题视图：`schema/sql/clickhouse/schema_game_analytics.sql`

这份字典是后续补 Android、iOS、Unity helper，以及 Flink 游戏主题作业的基线。
