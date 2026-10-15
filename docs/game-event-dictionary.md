# Game Event Dictionary

> 🌐 Language: **English** · 中文 → [game-event-dictionary.zh.md](game-event-dictionary.zh.md)

Updated: `2026-10-05`

This dictionary pins down the first-stage goal of `complier`: unify event names, fix core fields, keep room for extension. The principle is "preset core events + custom extension fields".

## General Requirements

- All events use the v1 unified envelope: `event_id`, `event_name`, `game_id`, `environment`, `device_id`, `ts_client`
- `game_id + environment` is the shared boundary for collection, routing, ClickHouse partitioning, and dashboard queries
- Account state adds `user_id`
- Player state prefers `player_id`; multi-character games may add `character_id`
- Session state adds `session_id`
- Custom business fields go in `props`
- For events directly tied to revenue, prefer filling both top-level `revenue_amount` and `revenue_currency`

## Contract v2 Incremental Fields (2026-10, B4)

On the v1 envelope only fields are added — nothing changed or removed (06 plan §4.2). All four fields are **gateway-authoritative**: defaults are backfilled by the gateway,
sender-declared values are not trusted and cannot self-escalate.

| Field | Type | Semantics | Integrator convention |
| --- | --- | --- | --- |
| `event_version` | integer ≥1 | Event contract major version | Defaults to 1, SDK may omit |
| `source` | `client`\|`server`\|`system`\|`derived` | Production source | server SDK must carry `server`; client SDK may omit (gateway backfills by key tier); `system`/`derived` are platform-reserved tiers, declaring them is rejected (`trust_escalation`) |
| `trust_level` | `LOW`\|`HIGH`\|`COMPUTED` | Derived from source (client→LOW; server/system→HIGH; derived→COMPUTED) | **No integrator may declare this**; declaring a tier higher than the derived one is rejected; risk-control BLOCK-level actions only accept `HIGH` |
| `event_origin` | string ≤64 | SDK name + version (e.g. `server-java/0.1.0`) | For audit tracing; defaults to `gateway` filled by the gateway |

ClickHouse `events` table corresponding columns: `event_version` (DEFAULT 1), `source`/`trust_level`/`event_origin`
(DEFAULT `''`, empty string = historical rows before contract v2; trust determination must exactly match `trust_level='HIGH'`).

## 1. Identity and Session

| Event name | Purpose | Recommended props |
| --- | --- | --- |
| `install` | First install or first open | `channel`, `campaign`, `creative_id` |
| `login` | Player login | `login_method`, `is_new_user` |
| `session_start` | Session start | `scene`, `entry_point` |
| `session_end` | Session end | `duration_sec`, `exit_reason` |

## 2. Tutorial and Progression

| Event name | Purpose | Recommended props |
| --- | --- | --- |
| `tutorial_start` | Tutorial start | `tutorial_id`, `step_id`, `step_index` |
| `tutorial_complete` | Tutorial complete | `tutorial_id`, `duration_sec` |
| `level_start` | Level start | `level_id`, `game_mode`, `attempt`, `difficulty` |
| `level_fail` | Level fail | `level_id`, `game_mode`, `attempt`, `fail_reason`, `duration_sec` |
| `level_complete` | Level complete | `level_id`, `game_mode`, `attempt`, `duration_sec`, `score`, `stars` |

## 3. Economy

| Event name | Purpose | Recommended props |
| --- | --- | --- |
| `currency_source` | Virtual currency production | `currency_code`, `amount`, `source`, `reason`, `balance_after` |
| `currency_sink` | Virtual currency consumption | `currency_code`, `amount`, `sink`, `reason`, `balance_after` |
| `item_grant` | Item grant | `item_id`, `quantity`, `source`, `rarity` |
| `item_consume` | Item consumption | `item_id`, `quantity`, `sink`, `target_id` |

## 4. Monetization

| Event name | Purpose | Recommended props | Top-level revenue fields |
| --- | --- | --- | --- |
| `revenue` | Generic revenue event | `order_id`, `product_id`, `store` | `revenue_amount`, `revenue_currency` |
| `iap_order` | In-app purchase order | `order_id`, `product_id`, `store`, `validated` | `revenue_amount`, `revenue_currency` |
| `webshop_order` | Official website or Webshop order | `order_id`, `product_id`, `channel` | `revenue_amount`, `revenue_currency` |
| `ad_impression` | Ad impression revenue | `network`, `ad_unit_id`, `placement_id`, `ad_format`, `precision` | `revenue_amount`, `revenue_currency` |
| `rewarded_ad_complete` | Rewarded video complete | `network`, `ad_unit_id`, `placement_id`, `reward_id` | Optional |

## 5. LiveOps and Social

| Event name | Purpose | Recommended props |
| --- | --- | --- |
| `event_entry` | LiveOps event entry | `liveops_event_id`, `entry_source`, `cohort` |
| `event_reward_claim` | LiveOps event reward claim | `liveops_event_id`, `reward_id`, `reward_type`, `reward_amount` |
| `guild_join` | Join guild | `guild_id`, `guild_size` |
| `invite_sent` | Send invite | `channel`, `target_platform` |

## 6. Technical Health and Risk Control

| Event name | Purpose | Recommended props |
| --- | --- | --- |
| `crash` | Crash event | `error_name`, `fatal`, `scene`, `app_build` |
| `fps_drop` | Frame rate drop | `fps`, `scene`, `device_tier` |
| `network_timeout` | Network timeout | `endpoint`, `timeout_ms`, `network_type` |
| `cheat_flag` | Suspicious cheating behavior | `rule_id`, `risk_level`, `evidence` |

## 7. Naming Constraints

- Event names use lower-case snake_case, e.g. `level_complete`
- Key object fields uniformly use the `_id` suffix, e.g. `level_id`, `tutorial_id`, `item_id`
- Amount fields are uniformly `amount`
- Virtual currency is uniformly `currency_code`
- Failure reason is uniformly `fail_reason`

## 8. Current Repository Landing Points

- SDK helper: `sdks/web` dual entry — the experiment helper is at `sdks/web/src/index.ts` (`hash32`/`assignVariant`/`fetchExperiments`/targeting series), the game event helper is at `sdks/web/src/game-events.ts` (`GameEvents` 19 methods + `GameEventType` and other constants, `export * from './game-events.ts'` already exported from the index barrel file); server-side settlement events go through `sdks/server` (automatically declares `source=server`, `event_origin=server-java/{VERSION}`)
- Raw event schema: `schema/avro/oddsmaker-event.avsc`, `schema/json/oddsmaker-event-schema.json`, `services/gateway-service/src/main/resources/schemas/oddsmaker-event-schema.json`
- Game-themed views: `schema/sql/clickhouse/schema_game_analytics.sql`

This dictionary is the baseline for later adding Android, iOS, and Unity helpers, as well as Flink game-themed jobs.
