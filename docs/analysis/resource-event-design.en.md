# Oddsmaker Resource Event Design

> 🌐 Language: **English** · 中文 → [resource-event-design.md](resource-event-design.md)

## Conclusion

Oddsmaker adopts a **resource-flow fact model**:

- In the main analytics and risk-control path, a single event expresses only one change of one resource.
- When one business operation involves multiple resources, it is split into multiple resource-flow events.
- Multiple resource flows are linked by the same `operation_id`, preserving the integrity of the business operation.
- The SDK may provide a convenience API that reports multiple resources at once, but the SDK or Gateway must expand it into multiple resource flows before entering the main analytics table.

This design better fits Oddsmaker's current positioning: a game analytics and risk-control platform for a single company across multiple games and multiple environments. Resource events are high-frequency query targets for risk control, so the efficiency of ClickHouse aggregation, real-time rules, and anomaly detection should be prioritized.

## Why Not Use Arrays as the Primary Model

A single event with a multi-resource array can express a complete business operation, for example a single reward that contains gold, gems, and equipment at the same time:

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

But it is not suitable as Oddsmaker's main analytics path:

- ClickHouse queries require `ARRAY JOIN`, making resource aggregation and filtering more expensive.
- Risk-control rules become more complex; for example, "abnormal gold growth within 10 minutes" requires expanding the array first.
- Array elements are not easily used as sorting keys, materialized-view dimensions, or alert dimensions.
- The SDK, Flink, ClickHouse, and BI all have to understand the nested structure, making future extension costly.

Arrays can be kept in `props` for debugging or raw context, but cannot serve as the canonical model for resource analytics and risk control.

## Recommended Event Shape

One business operation produces multiple resource flows, and each flow expresses only one resource.

In the early design it was assumed that `game_id`/`environment` would only be reflected at the table level; the final implementation makes both required event fields, and they are ClickHouse partition keys (`PARTITION BY (game_id, environment, date)`).

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

The second resource in the same reward:

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

Equipment, items, stamina, and event currency also use the same model.

## Field Design

Core fields:

| Field | Type | Description |
|---|---|---|
| `event_type` | string | Fixed to `resource` |
| `event_name` | string | Recommended fixed to `resource_change` |
| `operation_id` | string | Correlation ID of one business operation |
| `operation_type` | string | `daily_reward`, `purchase_item`, `quest_reward`, etc. |
| `sequence` | int | Order of the resource flow within the same operation |
| `server_id` | string | Server/region ID, required for MMORPG games |
| `resource_type` | string | `currency`, `item`, `energy`, `material`, etc. |
| `resource_id` | string | canonical resource ID, for example `gold`, `s5_pvp_token` |
| `resource_numeric_id` | int/string | Optional, the game's internal resource ID |
| `resource_amount` | decimal/int64 | A positive number represents the change amount; direction is expressed by `flow_type` |
| `flow_type` | string | `source` or `sink` |
| `reason` | string | Reason for the resource change |
| `balance_after` | decimal/int64 | Optional, the balance after the change, used for risk-control validation |

As above: `game_id` and `environment` are event fields and partition keys, not at the table-name level.

Keeping `operation_id` is key. It resolves the conflict between "high performance of single-resource flows" and "traceability of one business operation".

**Adding `server_id` is necessary**: In MMORPG games, players have different characters and resources on different servers, and risk control needs to detect along the server dimension.

## Resource ID Design

Using only integer IDs is not recommended.

A more appropriate approach is:

- `resource_id` uses a string, serving as the primary identifier across SDKs, BI, documentation, and risk-control rules.
- `resource_numeric_id` is optional, for games that have internal numeric IDs.
- The true uniqueness boundary of all resource IDs is `game_id + environment + resource_id`.

Reasons:

- A company has multiple games, and `1001` in different games very likely represents different resources.
- Event currency, season currency, and temporary items are better suited to string IDs.
- String IDs are more readable in BI, risk-control rules, and operations configuration.
- Integer IDs can improve queries internal to a specific game, but should not become the platform's sole contract.

Recommended resource ID examples:

```json
{"resource_type": "currency", "resource_id": "gold"}
{"resource_type": "currency", "resource_id": "s5_pvp_token", "resource_numeric_id": 5001}
{"resource_type": "item", "resource_id": "sword_001"}
{"resource_type": "energy", "resource_id": "stamina"}
```

## Relationship to the Current Repository

> **Implementation status (2026-10)**: Per-game database separation is not implemented; the shared ClickHouse table is partitioned by `(game_id, environment)`, and the isolation strength is configured by the storage profile's `isolationStrategy` (currently only `SHARED` is wired). There is also no separate `resource_changes` table — resource flows land in the `events` main table (`resource_id`/`resource_amount`/`flow_type`/`operation_id` and other columns; see `schema/sql/clickhouse/schema.sql` for the DDL). The diagram below is the target shape from the design phase.

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

**The current ClickHouse main table already has resource-related flat columns**:

- `virtual_currency`
- `virtual_amount`
- `flow_type`
- `item_id`

This shows the underlying direction already leans toward single-resource flows rather than an array model.

But the current contract is still incomplete:

- The Web / Android / iOS SDKs only have the generic `track()` and a partial `revenue()`, and lack a convenience API for resource events.
- ClickHouse already has generic `resource_id` / `resource_amount` columns coexisting with `virtual_currency` / `virtual_amount`, and the view layer `v_economy_flows` already normalizes with `coalesce(resource_id, virtual_currency, props.currency_code)`; it still lacks `resource_type` / `sequence` / `balance_after`.

It is suggested to later upgrade the resource fields to generic naming:

| Current field | Recommended field |
|---|---|
| `virtual_currency` | `resource_id` |
| `virtual_amount` | `resource_amount` |
| `item_id` | `resource_item_id` or merged into `resource_id` |
| `flow_type` | Keep |
| (new) | `server_id` |
| (new) | `operation_id` |
| (new) | `operation_type` |
| (new) | `sequence` |

During the compatibility period, both the old and new fields can be written at the same time.

## SDK API Recommendations

The SDK should provide two levels:

1. Single-resource change:

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

2. Multi-resource business operation:

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

`resourceBatch()` is only an SDK usability API. When sending to the Gateway or writing to the main analytics table, it must be expanded into multiple `resource_change` events.

## ClickHouse Query Shapes

Currently it is a shared table, so queries must filter on `game_id` and `environment` (partition pruning depends on these two conditions); they can be omitted only after per-game database separation is in place.

Single player's resource surge:

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

Whole-server source / sink:

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

Trace by business operation:

```sql
SELECT *
FROM events
WHERE operation_id = 'op_daily_reward_001'
ORDER BY sequence;
```

## Risk-Control Design Benefits

This model directly supports the following rules:

- A single player's source of a resource exceeds the threshold within a short window.
- The source/sink ratio of a resource is abnormal.
- A newly appearing resource ID is produced in large quantities within a short time.
- Duplicate rewards occur under the same `operation_id`.
- The balance is discontinuous after a resource change, suspected of client forgery or duplicate requests.
- High-value resources are produced from an abnormal `operation_type`.

These rules can all be implemented based on flat fields and materialized views, without expanding arrays first.

## Design Trade-offs

| Dimension | Choice | Reason |
|---|---|---|
| Analytics main model | Single-resource flow | Highest efficiency for ClickHouse and risk control |
| Business integrity | `operation_id` correlation | One business operation can be reconstructed |
| SDK usability | Support batch API | The game side can report multiple resources in one call |
| Storage model | Flat fields | Convenient for sorting keys, materialized views, and BI |
| Resource identity | String primary, optional numeric ID | Balances flexibility and internal system mapping |
| Array resources | Raw context only | Does not enter the canonical analytics path |

## Follow-up Improvement Suggestions (with Implementation Status)

1. **Database architecture**: Implement per-game database separation, with each game having its own ClickHouse database. → Not implemented; the isolation configuration slot is in place (storage profile `isolationStrategy`).
2. Update the JSON Schema and Avro Schema, add resource fields, and **remove `game_id` and `environment`**. → The fields have been added (`server_id`/`operation_id`/`operation_type`/`resource_id`/`resource_amount`/`flow_type`, etc., are already in the schema and the events table); `game_id`/`environment` are kept as required fields (not removed per this suggestion).
3. ClickHouse adds generic resource fields, keeping `virtual_currency` / `virtual_amount` for compatibility. → Implemented.
4. SDK adds `resource()` and `resourceBatch()`. → Not implemented.
5. Gateway performs basic validation on resource events (amount is positive, `flow_type ∈ source|sink`, `resource_id` is non-empty). → No resource-specific validation (only generic schema validation).
6. Flink or ClickHouse MV adds resource risk-control aggregation views. → No resource-specific MV; risk control goes through the risk-job rule pipeline.
7. Control API supports database routing, routing to the corresponding database based on `game_id + environment`. → Not implemented (the storage profile is a configuration slot; routing is not wired).
8. Tracking Plan supports per-game configuration of resource ID whitelists, allowed `operation_type`s, and single-change thresholds. → The Tracking Plan management plane is in place; resource-specific constraints are not implemented.

