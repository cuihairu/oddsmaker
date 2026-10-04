# Oddsmaker 资源事件设计

## 结论

Oddsmaker 采用 **资源流水事实模型**：

- 分析与风控主路径中，一条事件只表达一种资源的一次变动。
- 一次业务操作包含多种资源时，拆成多条资源流水事件。
- 多条资源流水通过同一个 `operation_id` 关联，保留业务操作的完整性。
- SDK 可以提供一次性上报多资源的便捷 API，但 SDK 或 Gateway 必须在进入分析主表前展开为多条资源流水。

这个设计更适合 Oddsmaker 当前定位：单公司、多游戏、多环境下的游戏分析与风控平台。资源事件是风控高频查询对象，应该优先保证 ClickHouse 聚合、实时规则和异常检测的效率。

## 为什么不采用数组作为主模型

单事件多资源数组能表达完整业务操作，例如一次奖励里同时包含金币、宝石和装备：

```json
{
  "event_name": "daily_reward_claimed",
  "resource_changes": [
    {"resource_id": "gold", "amount": 1000},
    {"resource_id": "gem", "amount": 50},
    {"resource_id": "sword_001", "amount": 1}
  ]
}
```

但它不适合作为 Oddsmaker 的分析主路径：

- ClickHouse 查询需要 `ARRAY JOIN`，资源聚合和过滤成本更高。
- 风控规则会变复杂，例如“10 分钟内金币异常增长”需要先展开数组。
- 数组元素不容易作为排序键、物化视图维度和告警维度。
- SDK、Flink、ClickHouse、BI 都要理解嵌套结构，后续扩展成本高。

数组可以保留在 `props` 中用于调试或原始上下文，但不能作为资源分析和风控的 canonical model。

## 推荐事件形态

一次业务操作产生多条资源流水，每条流水只表达一种资源。

**注意**：设计早期曾设想 `game_id`/`environment` 只在表层级体现；最终实现两者都是事件必填字段，且是 ClickHouse 的分区键（`PARTITION BY (game_id, environment, 日期)`）。

```json
{
  "event_id": "evt_001_a",
  "event_type": "resource",
  "event_name": "resource_change",
  "device_id": "device_001",
  "player_id": "player_001",
  "server_id": "server_001",
  "ts_client": 1760000000000,
  "operation_id": "op_daily_reward_001",
  "operation_type": "daily_reward",
  "sequence": 1,
  "resource_type": "currency",
  "resource_id": "gold",
  "resource_amount": 1000,
  "flow_type": "source",
  "reason": "daily_reward"
}
```

同一次奖励中的第二种资源：

```json
{
  "event_id": "evt_001_b",
  "event_type": "resource",
  "event_name": "resource_change",
  "device_id": "device_001",
  "player_id": "player_001",
  "server_id": "server_001",
  "ts_client": 1760000000000,
  "operation_id": "op_daily_reward_001",
  "operation_type": "daily_reward",
  "sequence": 2,
  "resource_type": "currency",
  "resource_id": "gem",
  "resource_amount": 50,
  "flow_type": "source",
  "reason": "daily_reward"
}
```

装备、道具、体力、活动货币也使用同一套模型。

## 字段设计

核心字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event_type` | string | 固定为 `resource` |
| `event_name` | string | 推荐固定为 `resource_change` |
| `operation_id` | string | 一次业务操作的关联 ID |
| `operation_type` | string | `daily_reward`、`purchase_item`、`quest_reward` 等 |
| `sequence` | int | 同一次操作内的资源流水顺序 |
| `server_id` | string | 服务器/大区 ID，MMORPG 游戏必需 |
| `resource_type` | string | `currency`、`item`、`energy`、`material` 等 |
| `resource_id` | string | canonical 资源 ID，例如 `gold`、`s5_pvp_token` |
| `resource_numeric_id` | int/string | 可选，游戏内部资源 ID |
| `resource_amount` | decimal/int64 | 正数表示变动数量，方向由 `flow_type` 表达 |
| `flow_type` | string | `source` 或 `sink` |
| `reason` | string | 资源变化原因 |
| `balance_after` | decimal/int64 | 可选，变化后的余额，用于风控校验 |

**注意**：同上——`game_id` 和 `environment` 是事件字段与分区键，不在表名层级。

保留 `operation_id` 是关键。它解决了”单资源流水高性能”和”一次业务操作可追溯”之间的矛盾。

**新增 `server_id` 是必要的**：MMORPG 游戏玩家在不同服务器有不同的角色和资源，风控需要按服务器维度检测。

## 资源 ID 设计

不建议只使用整数 ID。

更合适的方式是：

- `resource_id` 使用字符串，作为跨 SDK、BI、文档和风控规则的主标识。
- `resource_numeric_id` 可选，用于有内部数值 ID 的游戏。
- 所有资源 ID 的真实唯一边界是 `game_id + environment + resource_id`。

原因：

- 一个公司有多个游戏，不同游戏的 `1001` 很可能表示不同资源。
- 活动货币、赛季货币、临时道具更适合字符串 ID。
- BI、风控规则和运营配置里字符串 ID 可读性更高。
- 整数 ID 可以提升特定游戏内部查询，但不应该成为平台唯一契约。

推荐资源 ID 示例：

```json
{"resource_type": "currency", "resource_id": "gold"}
{"resource_type": "currency", "resource_id": "s5_pvp_token", "resource_numeric_id": 5001}
{"resource_type": "item", "resource_id": "sword_001"}
{"resource_type": "energy", "resource_id": "stamina"}
```

## 与当前仓库的关系

> **实现状态（2026-10）**：按游戏分库未实现；共享 ClickHouse 表按 `(game_id, environment)` 分区，隔离强度由存储 profile 的 `isolationStrategy` 配置（当前只有 `SHARED` 接线）。也没有独立的 `resource_changes` 表——资源流水落在 `events` 主表（`resource_id`/`resource_amount`/`flow_type`/`operation_id` 等列，DDL 见 `schema/sql/clickhouse/schema.sql`）。下图为设计期的目标形态。

```
oddsmaker_meta (元数据库)
├─ games
├─ environments
├─ api_keys
└─ audit_logs

game_demo_prod (游戏数据库，目标形态)
├─ events
├─ resource_changes    -- 资源流水专用表（未建，流水在 events 主表）
├─ sessions
└─ risk_events

game_demo_staging
└─ (同样的表结构)
```

**当前 ClickHouse 主表已经有资源相关扁平列**：

- `virtual_currency`
- `virtual_amount`
- `flow_type`
- `item_id`

这说明底层方向已经偏向单资源流水，而不是数组模型。

但当前契约仍不完整：

- JSON Schema 只显式支持 `revenue_amount` / `revenue_currency`，缺少资源字段。
- Avro Schema 缺少资源字段。
- Web / Android / iOS SDK 只有通用 `track()` 和部分 `revenue()`，缺少资源事件便捷 API。
- ClickHouse 字段命名仍偏 `virtual_currency` / `virtual_amount`，不能完整覆盖 item、energy、material 等资源。
- 缺少 `server_id` 字段。

建议后续把资源字段升级为通用命名：

| 当前字段 | 推荐字段 |
|---|---|
| `virtual_currency` | `resource_id` |
| `virtual_amount` | `resource_amount` |
| `item_id` | `resource_item_id` 或并入 `resource_id` |
| `flow_type` | 保留 |
| (新增) | `server_id` |
| (新增) | `operation_id` |
| (新增) | `operation_type` |
| (新增) | `sequence` |

兼容期可以同时写入旧字段和新字段。

## SDK API 建议

SDK 应提供两个层级：

1. 单资源变动：

```ts
oddsmaker.resource({
  operationId: "op_daily_reward_001",
  operationType: "daily_reward",
  resourceType: "currency",
  resourceId: "gold",
  amount: 1000,
  flowType: "source",
  reason: "daily_reward"
});
```

2. 多资源业务操作：

```ts
oddsmaker.resourceBatch({
  operationId: "op_daily_reward_001",
  operationType: "daily_reward",
  reason: "daily_reward",
  changes: [
    {resourceType: "currency", resourceId: "gold", amount: 1000, flowType: "source"},
    {resourceType: "currency", resourceId: "gem", amount: 50, flowType: "source"},
    {resourceType: "item", resourceId: "sword_001", amount: 1, flowType: "source"}
  ]
});
```

`resourceBatch()` 只是 SDK 易用性 API。发送到 Gateway 或写入分析主表时，必须展开成多条 `resource_change` 事件。

## ClickHouse 查询形态

**注意**：当前是共享表，查询必须过滤 `game_id` 和 `environment`（分区裁剪依赖这两个条件）；按游戏分库落地后才可省略。

单玩家资源暴增：

```sql
SELECT
  server_id,
  player_id,
  resource_id,
  sum(resource_amount) AS gained
FROM events
WHERE event_type = 'resource'
  AND flow_type = 'source'
  AND ts_server >= now() - INTERVAL 10 MINUTE
GROUP BY server_id, player_id, resource_id
HAVING gained > 100000;
```

全服 source / sink：

```sql
SELECT
  toDate(ts_server) AS day,
  resource_id,
  flow_type,
  sum(resource_amount) AS amount
FROM events
WHERE event_type = 'resource'
GROUP BY day, resource_id, flow_type;
```

按业务操作追溯：

```sql
SELECT *
FROM events
WHERE operation_id = 'op_daily_reward_001'
ORDER BY sequence;
```

## 风控设计收益

这个模型直接支持以下规则：

- 单玩家在短窗口内某资源 source 超阈值。
- 某资源 source/sink 比例异常。
- 新出现的资源 ID 在短时间内大量产出。
- 同一个 `operation_id` 出现重复发奖。
- 资源变化后余额不连续，疑似客户端伪造或重复请求。
- 高价值资源从异常 `operation_type` 产出。

这些规则都能基于扁平字段和物化视图实现，不需要先展开数组。

## 设计取舍

| 维度 | 选择 | 原因 |
|---|---|---|
| 分析主模型 | 单资源流水 | ClickHouse 和风控效率最高 |
| 业务完整性 | `operation_id` 关联 | 可重构一次业务操作 |
| SDK 易用性 | 支持 batch API | 游戏侧一次调用即可上报多资源 |
| 存储模型 | 扁平字段 | 便于排序键、物化视图、BI |
| 资源标识 | 字符串为主，可选数字 ID | 兼顾灵活性和内部系统映射 |
| 数组资源 | 只做原始上下文 | 不进入 canonical 分析路径 |

## 后续改造建议（落地情况标注）

1. **数据库架构**：实现按游戏分库，每个游戏独立 ClickHouse 数据库。→ 未实现；隔离配置位已建（storage profile `isolationStrategy`）。
2. 更新 JSON Schema 和 Avro Schema，加入资源字段，**移除 `game_id` 和 `environment`**。→ 字段已加入（`server_id`/`operation_id`/`operation_type`/`resource_id`/`resource_amount`/`flow_type` 等已在 schema 与 events 表）；`game_id`/`environment` 保留为必填字段（不随此建议移除）。
3. ClickHouse 增加通用资源字段，兼容保留 `virtual_currency` / `virtual_amount`。→ 已实现。
4. SDK 增加 `resource()` 和 `resourceBatch()`。→ 未实现。
5. Gateway 对资源事件做基础校验（金额为正、`flow_type ∈ source|sink`、`resource_id` 非空）。→ 未做资源专项校验（仅通用 schema 校验）。
6. Flink 或 ClickHouse MV 增加资源风控聚合视图。→ 资源专项 MV 未建；风控走 risk-job 规则链路。
7. Control API 支持数据库路由，根据 `game_id + environment` 路由到对应的数据库。→ 未实现（storage profile 是配置位，路由未接线）。
8. Tracking Plan 支持按游戏配置资源 ID 白名单、允许的 `operation_type` 和单次变动阈值。→ Tracking Plan 管理面已建，资源专项约束未实现。

