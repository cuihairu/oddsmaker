# Flink Jobs

> 🌐 Language: **English** · 中文 → [jobs.md](jobs.md)

Contains 7 jobs:
- events-enrich-job: validation/deduplication/enrichment (UA/GeoIP) → ClickHouse events
- sessions-job: 30-minute session window → ClickHouse sessions
- retention-job: D1/D7/D30 retention (`-Dretention.ndays` configurable) → ClickHouse retention_daily (includes the subject_id subject dimension, player>user>device, so that cohort filtering goes through pre-aggregation; for deployments on the old structure see the migration `schema/sql/clickhouse/migrations/2026-09-retention-daily-subject-id.sql`) / retention_rolling
- funnels-job: two-step funnel (level_start→level_complete, 24h timeout configurable) → ClickHouse funnels_2step
- risk-job: real-time risk detection → Kafka `oddsmaker.risk_events` + ClickHouse risk_events
- identity-merge-job: consumes `$identify` events and merges device_id/player_id into identity_id → ClickHouse identities
- dimension-sync-job: consumes dimension events (event_type=dimension) → ClickHouse item_dim / level_dim

## identity-merge-job (identity merge)

Consumes from `oddsmaker.events_raw` the `$identify` events (`event_type=identity`), maintains the ClickHouse `identities` table, and merges the multiple `device_id` / `player_id` values of the same user into a single `identity_id`, so that retention/LTV is not double-counted.

How it works:
- Groups by `game_id + environment + user_id`, maintaining `ValueState<IdentityState>` (identity_id, device_ids set, player_ids set, first_seen/last_seen).
- New user_id → generate a new identity_id; already present → reuse and append device_id/player_id.
- State TTL 90 days (released after the user is inactive for a long time).
- Each identify event writes one row to ClickHouse `identities` (ReplacingMergeTree by last_seen, merged automatically).

Startup example:

```bash
flink run -c io.oddsmaker.jobs.identity.IdentityMergeJob jobs/flink/identity-merge-job/build/libs/identity-merge-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default
```

Verification:

```sql
SELECT identity_id, user_id, device_ids, player_id, first_seen, last_seen
FROM identities
WHERE user_id = 'u123';
```

**v1 limitation**: cross-user merging via `previous_user_id` is not implemented today (it requires cross-key state reads; a later version will implement it with broadcast state). After a guest converts to an account, the old device keeps its identity and the new identity is associated with the same device; the analytics side can do a second merge via the `device_ids` intersection.

## dimension-sync-job (dimension sync)

Consumes from `oddsmaker.events_raw` the dimension events (`event_type=dimension`) and syncs item/level definitions from the game side into the ClickHouse dimension tables. For the design see [Dimension Data Sync](../reference/dimension-sync).

How it works:
- Parses dimension attributes from the event's `props_json` (`dim_type`, `resource_id`/`level_id`, `name`, `rarity`, etc.).
- Routes by `dim_type`: `item` → `item_dim`, `level` → `level_dim`.
- ClickHouse dimension tables use `ReplacingMergeTree(version_ts)`; duplicate writes keep the latest version.

Prerequisite: run `schema/sql/clickhouse/dimensions.sql` to create the `item_dim` and `level_dim` tables.

Startup example:

```bash
flink run -c io.oddsmaker.jobs.dimension.DimensionSyncJob jobs/flink/dimension-sync-job/build/libs/dimension-sync-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default
```

Verification:

```sql
SELECT resource_id, name, type, rarity FROM item_dim FINAL
WHERE game_id = 'game_demo' AND environment = 'prod';
```

In production, data is usually pushed in by the sync Agent (see the Sync Agent section in [Dimension Data Sync](../reference/dimension-sync)): the Agent reads DB/CSV inside the game vendor's intranet and pushes with `event_name=dimension_define` and `event_type=dimension` to Gateway `/v1/batch`; the sync offset and heartbeat are reported to Control via `POST /api/dimensions/sync-status`, and `GET /api/dimensions/sync-status?gameId=` exposes per-source latency and error counts.

## risk-job (real-time risk control)

Consumes `oddsmaker.events_raw`, runs seven categories of rule detection on each event, and after a hit writes `risk_events` to Kafka (for Control Service real-time alerting/review) and ClickHouse (for the risk dashboard and auditing).

Current built-in rules (parameters injected via `-D` system properties):

| Rule ID | Type | Trigger condition | Default parameters |
|---|---|---|---|
| `risk-threshold-amount` | THRESHOLD | `resource_amount` exceeds the threshold | `risk.threshold.amount=100000` |
| `risk-frequency-burst` | FREQUENCY | event count for the same subject in a sliding window exceeds the threshold | `risk.frequency.window-minutes=10`<br>`risk.frequency.max-events=1000` |
| `risk-velocity-amount` | VELOCITY | total resource change for the same subject in the window exceeds the threshold | `risk.velocity.max-amount=1000000` |
| `risk-ratio-source-sink` | RATIO | source/sink ratio for the same subject in the window exceeds the threshold | `risk.ratio.max-source-sink=10` |
| `risk-duplicate-receipt` | DUPLICATE_RECEIPT | duplicate submissions for the same receipt key in the window reach the count | `risk.receipt.max-occurrences=2` |
| `risk-ad-reward-abuse` | AD_REWARD | rewarded-ad reward bursts within the window | `risk.adreward.max-per-window=60` |
| `risk-pattern-sequence` | PATTERN | the ordered event sequence for the same subject completes within the window | `risk.pattern.sequence=login,purchase,refund`<br>`risk.pattern.window-minutes=5` |

VELOCITY detects an abnormal total resource amount within a window (e.g. producing more than 1 million gold in 10 minutes); RATIO detects an abnormal production/consumption ratio (e.g. source/sink > 10 means producing without consuming, suspected resource farming). PATTERN (multi-event sequences such as login→purchase→refund) is implemented with a keyed state machine: the sequence is delivered from the control side via `ruleConditions.sequence` (≥2 steps) + `timeWindowMinutes`; the window starts at the first step, expirations are invalidated lazily, a repeated first step restarts automatically, and after a hit the state resets so it can match again; no flink-cep dependency was introduced.

**Dynamic thresholds**: all thresholds are read from `-D` system properties by default, but if a Control Service address is configured, risk-job periodically (60s by default) fetches active `RiskRuleEntity` records and overrides the default thresholds by `ruleType` + `triggerThreshold`. When operations changes a rule threshold in Control Service, it takes effect within 60s without restarting the job. When Control Service is unavailable it automatically falls back to the `-D` defaults.

Subject priority: `user_id` (PLAYER) > `device_id` (DEVICE).

Output fields align with the ClickHouse `risk_events` table: `game_id, environment, ts, risk_event_id, source_event_id, rule_id, risk_type, severity, subject_type, subject_id, score, action, reason, evidence`.

Startup example:

```bash
flink run -c io.oddsmaker.jobs.risk.RiskJob jobs/flink/risk-job/build/libs/risk-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default \
  -Drisk.threshold.amount=100000 \
  -Drisk.frequency.window-minutes=10 \
  -Drisk.frequency.max-events=1000 \
  -Dcontrol.url=http://localhost:8085 \
  -Dcontrol.gameId=game_demo \
  -Dcontrol.token=admin \
  -Drule.refresh-ms=60000
```

Verification:

```sql
-- ClickHouse
SELECT risk_type, severity, count() FROM risk_events
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY risk_type, severity;

-- Kafka（实时告警消费）
kafka-console-consumer --bootstrap-server localhost:9092 --topic oddsmaker.risk_events
```

Extension plan:
- Rule parameters will later be wired into Control Service's `RiskRuleEntity`, fetching active rules by `game_id + environment` (periodic HTTP refresh). **✅ Implemented: RuleFetcher periodically fetches `/api/risk-dashboard/rules/{gameId}` and overrides thresholds by ruleType.**
- Rule type extensions: VELOCITY (total resource change within a window), RATIO (source/sink ratio), PATTERN (multi-event sequences). **✅ All implemented; PATTERN uses a KeyedProcessFunction state machine (no flink-cep).**
- Post-hit actions will be wired into Control Service's ReviewQueue and Webhook notifications. **✅ Implemented: RiskEventConsumer injects ReviewQueueService and WebhookService, sending a `risk_action` Webhook after a hit.**

Startup method
- Dependencies: Kafka, Apicurio Registry, ClickHouse already running (infra/docker-compose.yml)
- Build and run (requires the Flink command line installed locally)
```bash
scripts/run_flink.sh
```
Environment variables (optional overrides)
- KAFKA_BOOTSTRAP (default `localhost:9092`)
- REGISTRY_URL (default `http://localhost:8081/apis/registry/v2`)
- KAFKA_TOPIC (default oddsmaker.events_raw)
- CLICKHOUSE_URL (default `jdbc:clickhouse://localhost:8123/default`)
- KAFKA_DLQ (default oddsmaker.deadletter)
- GEOIP_MMDB (path to the GeoLite mmdb, can be empty)

Verification
- ClickHouse: `SELECT count() FROM events;`, `SELECT count() FROM sessions;`, `SELECT count() FROM risk_events;`
- DLQ: consume `oddsmaker.deadletter` to inspect invalid/duplicate events
- Risk: consume `oddsmaker.risk_events` to inspect real-time hits

Notes
- The JDBC Sink already does batching and retries; if end-to-end transactional semantics are needed it can later be replaced with a two-phase commit sink
- The GeoIP file is optional; running is unaffected when it is not provided
