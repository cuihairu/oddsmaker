# Dimension Data Sync Design

> 🌐 Language: **English** · 中文 → [dimension-sync.md](dimension-sync.md)

> **Implementation status (2026-10)**: What has landed is the Sync Agent (`agents/dimension-sync-agent/`, five source types: mysql/postgres/csv/excel/kafka), the **HTTP Pull pipeline** (Control-side scheduler + Bearer credential AES-GCM encrypted custody + `next_cursor` resumable transfer, configuration in the `dimension-pulls` API), the **Pull config YAML surface** (`dimension-pulls/export` + `/import`, credentials never written to disk), **Control custody of the Agent property surface** (`agent-configs` API + the console "Agent Managed Config" surface + the `/api/dimensions/agent-configs/resolved` delivery endpoint, secret keys under AES-GCM ciphertext custody; on the Agent side the `control.config-url` switch triggers a startup fetch + local file fallback, and `control.config-refresh-seconds` polls for hot updates, precedence local < Control < System property), the `/v1/batch` dimension event channel, and `dimension-sync-job` (consumes `oddsmaker.events_raw` and writes `item_dim`/`level_dim`). Only Debezium CDC remains unimplemented (heaviest external dependency, pending game-side Kafka infrastructure)—this document marks every unimplemented part inline.

## Conclusion

Oddsmaker does not force the game side to use any particular integration method. This document defines four sync approaches + one out-of-the-box sync Agent, covering game scenarios from POC to maturity and from indie studios to large companies.

Core trade-offs:

- **Dimension data** (item definitions, level configs, event configs, etc.) and **fact data** (player consumption, behavior events) are separated.
- Fact data goes SDK → Gateway → ClickHouse main tables (see the resource event design document).
- Dimension data is synced from the game-side business database to ClickHouse dimension tables (this document).
- Queries **never read back from the game business database**; all joins are done inside ClickHouse.

## Why Sync Instead of Direct Connection

| Approach | Problem |
|---|---|
| Analytic queries read the game DB in real time | Puts analytic load on the business DB, affecting production; cross-network latency; coupled permissions |
| Only have the SDK report dimensions | The game side may not emit events for dimension changes (item rename, rarity adjustment); historical facts "travel" into new attributes |
| Sync everything into one database | OLTP and OLAP have different responsibilities; mixing them destroys the performance of both |

The right approach: **the game business database is the source of dimensions, the ClickHouse dimension table is the analytics-side replica, and a sync pipeline sits in between**.

## Sync Approaches Overview

| Approach | Who reads the source | Who pushes to oddsmaker | What the game side must do | Latency |
|---|---|---|---|---|
| **Webhook Push** | Game-side code | Game side calls the API | Call the Gateway to report a `dimension_define` event when an item changes | Real-time |
| **Sync Agent** | Agent (inside the game side's network) | Agent pushes to Gateway | Deploy a binary + fill in the connection string | Minute-level |
| **HTTP Pull** | Oddsmaker | Oddsmaker pulls | Expose an `/items?since=...` incremental query API | Minute-level |
| **Debezium CDC** | Debezium reads binlog | Debezium writes to Kafka | Deploy Kafka Connect + enable binlog | Second-level |

Whichever approach is used, what lands in ClickHouse is the same unified `item_dim` table, and the downstream query logic for analytics, risk control, and BI is completely identical.

## Recommended Approach by Game Stage

Game teams at different stages differ in technical capability, willingness to integrate, and compliance constraints. Pick the row that matches your stage.

### Onboarding / POC

**Goal**: Validate whether Oddsmaker is usable as fast as possible, without modifying game code.

**Recommended**: **Webhook Push** (small manual reporting) or **Sync Agent + CSV source** (designer table export).

- Few items (tens to hundreds); export a CSV once by hand or by script and hand it to the Agent to push up.
- No binlog, no API, no Kafka deployment.
- Once the POC works, pick a long-term approach.

### Indie Games / Early Stage

**Characteristics**: small team (1-5 people), simple tech stack, unwilling to make many changes for integration, config tables may be Excel.

**Recommended**: **Sync Agent** (MySQL/PostgreSQL source) or **CSV/Excel source**.

- Deploy the Agent, configure the local DB connection string, or periodically drop CSVs into a directory.
- The Agent is a ready-made binary provided by Oddsmaker; the game side writes no sync code.
- Minute-level latency is enough (indie games change items infrequently).

### Growth Stage / Mid-size Games

**Characteristics**: has backend development capability, has an API gateway, starting to care about real-time behavior and data quality.

**Recommended**: **HTTP Pull** (game side builds a query API) or **Webhook Push** (add one line of call in the item-change code).

- Does not require Oddsmaker to deploy an Agent into the game's internal network.
- The game side has full control over the API (auth, rate limiting, field trimming).
- Works with the Tracking Plan for field validation and whitelisting.

### Maturity / Large-studio Games

**Characteristics**: full tech stack, has DBAs and a data team, already runs Kafka, requires second-level latency, strict compliance.

**Recommended**: **Debezium CDC**.

- Reuses the game side's existing Kafka cluster; introduces no new components into the game's internal network.
- Second-level latency supports real-time risk control (mass production of new items, abnormal rarity changes).
- The DBA only needs to enable binlog + grant a read-only account; no application-layer changes.

### Legacy / Older Games

**Characteristics**: old tech stack (may still manage config with Excel/CSV), no API, old database version, no binlog.

**Recommended**: **Sync Agent + file source** (CSV/Excel/Parquet).

- Designers periodically export item tables to a shared directory or object storage.
- The Agent watches the directory for changes and pushes to the Gateway automatically.
- These games usually change items infrequently; day-level latency is acceptable.

### Decision Tree

```
游戏方愿意写代码吗？
├─ 是 → 愿意开 binlog + 有 Kafka 吗？
│       ├─ 是 → Debezium CDC（成熟期）
│       └─ 否 → Webhook Push（成长期）
└─ 否 → 愿意暴露查询 API 吗？
        ├─ 是 → HTTP Pull（成长期）
        └─ 否 → 愿意部署 Agent 吗？
                ├─ 是 → Sync Agent（独立游戏/存量）
                │        └─ 数据源是 DB？→ MySQL/PG source
                │        └─ 数据源是文件？→ CSV/Excel source
                └─ 否 → Webhook Push 手动上报（仅 POC 可接受）
```

## Approach Details

### Webhook Push

The game side proactively calls the Oddsmaker Gateway to report dimension events when items change (added, modified, delisted).

```
POST /v1/batch
X-Api-Key: pk_game_xxx
Content-Type: application/x-ndjson

{"event_id":"01JE...","event_type":"dimension","event_name":"dimension_define","game_id":"game_x","environment":"prod","dim_type":"item","resource_id":"sword_001","name":"铁剑","rarity":"common","op":"upsert","version_ts":1760000000000,"ts_client":1760000000000}
{"event_id":"01JE...","event_type":"dimension","event_name":"dimension_define","game_id":"game_x","environment":"prod","dim_type":"item","resource_id":"sword_002","name":"钢剑","rarity":"rare","op":"upsert","version_ts":1760000000001,"ts_client":1760000000001}
```

There is no separate `/v1/dimension` endpoint—dimension events go through `/v1/batch` alongside ordinary events, distinguished by `event_type=dimension`, reusing the existing auth, rate limiting, and PII governance.

- `op`: `upsert` (add/update) or `delete` (delist; the downstream currently ignores this semantics and still writes a new row, see "Version Handling" below).
- `version_ts`: source change time, used for SCD2 and idempotency.
- The Gateway reuses the existing auth, rate limiting, and PII governance capabilities; no new components.
- Suited to scenarios that are **willing to make changes, change infrequently, and need real-time**.

### Sync Agent (`oddsmaker-agent`)

The open-source sync Agent provided by Oddsmaker, deployed by the game side inside its own network. The Agent reads local sources and pushes to the Gateway over HTTPS. **Implemented**: repository `agents/dimension-sync-agent/`—a standalone Gradle module with zero in-repo dependencies (only Jackson + kafka-clients + JDK HttpClient + runtimeOnly JDBC drivers), supporting five source types `mysql` / `postgres` / `csv` / `excel` / `kafka` (excel uses the standard library's zip+xml for minimal xlsx parsing, no POI; kafka manages its own offset checkpoint, and real-broker end-to-end has been tested), and the whole directory can be split out into a standalone repository via git subtree.

```
游戏方内网:
  oddsmaker-agent
    ├─ source: mysql | postgres | csv | excel | kafka
    ├─ transform: 控制列映射（dim_type/resource_id/op/version_ts，其余列进 attributes）
    ├─ checkpoint: 本地 checkpoint.json（原子落盘；kafka 位点同为 checkpoint 自管）
    └─ sink: HTTPS POST /v1/batch（NDJSON，event_name=dimension_define）
```

Configuration example (`agent.properties`; a System property of the same name can override the file value key by key, which makes it convenient to keep secrets off disk with `-Dgateway.api-key=...`). Configuring `control.config-url` enables Control custody (see "Agent Property Surface Custody" below); delivered keys override local values key by key, and configuring `control.config-refresh-seconds` additionally enables polling for hot updates:

```properties
# 推送目标（Gateway /v1/batch）
gateway.endpoint=https://ingest.oddsmaker.local
gateway.api-key=pk_game_xxx

# 维度归属
game.id=game_demo
game.environment=prod
dimension.type=item

# 数据源之一：mysql / postgres（增量查询——单 ? 占位，必须以 ORDER BY <水位列> 结尾）
source.type=mysql
source.jdbc.url=jdbc:mysql://replica.internal:3306/game?useSSL=true
source.jdbc.user=oddsmaker_ro
source.jdbc.password=***
source.jdbc.query=SELECT item_code AS resource_id, item_name AS name, category, rarity, updated_at, UNIX_TIMESTAMP(updated_at)*1000 AS version_ts FROM items WHERE updated_at > ? ORDER BY updated_at LIMIT 1000
source.jdbc.cursor-column=updated_at

# 数据源之二：csv 目录（按文件名序扫 *.csv，处理完整文件才记断点；增量 = 投递新文件）
# source.type=csv
# source.csv.dir=/data/dimension-exports

# 数据源之三：excel 目录（扫 *.xlsx 取首个工作表，首行为表头；断点语义同 CSV）
# source.type=excel
# source.excel.dir=/data/dimension-exports

# 数据源之四：kafka topic（消息体 = JSON 对象，控制列语义同上；位点 checkpoint 自管）
# source.type=kafka
# source.kafka.bootstrap-servers=broker1:9092,broker2:9092
# source.kafka.topic=game-dimensions
# source.kafka.group-id=oddsmaker-dimension-sync
# source.kafka.poll-timeout-ms=3000
# source.kafka.cursor-initial=0=42;1=57
# 鉴权（可选）：SASL_* 协议时 sasl-mechanism/username/password 必填（机制仅 SCRAM-SHA-256/512）
# source.kafka.security-protocol=SASL_PLAINTEXT
# source.kafka.sasl-mechanism=SCRAM-SHA-256
# source.kafka.username=oddsmaker_ro
# source.kafka.password=***
# TLS 证书模式（security-protocol=SSL / SASL_SSL）：自签或私有 CA 需配客户端信任库；
# 不配走 JVM 默认信任库（公有 CA）。明文协议下配置信任库启动即拒（配置漂移防护）
# source.kafka.ssl-truststore-path=/etc/oddsmaker/kafka-client.truststore.p12
# source.kafka.ssl-truststore-password=***
# source.kafka.ssl-truststore-type=PKCS12
# mTLS（broker ssl.client.auth=required）另配客户端证书库三键，单向 TLS 不配即可；
# 私钥口令复用证书库口令（PKCS12 单一口令语义），校验口径同信任库
# source.kafka.ssl-keystore-path=/etc/oddsmaker/kafka-client.keystore.p12
# source.kafka.ssl-keystore-password=***
# source.kafka.ssl-keystore-type=PKCS12

# Agent 行为
agent.batch-size=500
agent.poll-seconds=300
agent.checkpoint-path=/var/lib/oddsmaker-agent/checkpoint.json

# 同步状态上报（Control /api/dimensions/sync-status；url 缺省则跳过）
status.url=https://control.oddsmaker.local/api/dimensions/sync-status
status.token=<admin-token>
status.source-key=agent-mysql-main
```

Startup: run `./gradlew :agents:dimension-sync-agent:installDist`, then run `build/install/dimension-sync-agent/bin/dimension-sync-agent --config=/etc/oddsmaker/agent.properties`.

Key constraints (all are implemented behavior):

- **Read-only account**: the DB account held by the Agent is granted only SELECT permission.
- **Read from a replica**: the connection string points to a replica to avoid affecting production.
- **Incremental query**: must be based on `updated_at` or an auto-increment ID; full table scans are forbidden. The watermark is stored in the checkpoint with a type tag (`n:` integer / `t:` time / `s:` string), and on recovery it is bound to the PreparedStatement by the original type (PG's timestamp column rejects bigint literals).
- **Rate limiting + batching**: default one round every 5 minutes, 500 rows per batch, configurable as needed.
- **Resumable transfer**: the checkpoint is a local JSON file, written atomically (tmp + move); **it advances only after all pushes succeed**, and on failure the next round replays the same window with the old watermark—downstream item_dim/level_dim are `ReplacingMergeTree(version_ts)`, so replay is idempotent.
- **Credentials never leave the game network**: in the default mode, DB credentials stay only on the Agent locally; only the offset and counts are reported to Control, and no source data can be obtained. **Once Control custody is enabled this principle no longer holds**—when source-side credentials (DB/Kafka/keystore passwords and the gateway key) are delivered via `resolved`, they enter Control's AES-GCM custody (the same key surface as Pull credentials); the trade-off is centralized operations in exchange for moving the custody boundary back; the custody surface does not custody `agent.checkpoint-path` (a local path).
- **Observable heartbeat**: each round reports `POST /api/dimensions/sync-status` (camelCase JSON: `gameId`/`environment`/`sourceKey`/`sourceType`/`cursor`/`lastEventTs`/`pushedCount`/`errorCount`/`lastError`, authenticated with `x-admin-token`); on the Control side, `GET /api/dimensions/sync-status?gameId=` returns two latency measures—`sinceLastPushSeconds` (Agent liveness) and `sinceLastEventSeconds` (source freshness).

This is also where the **FileImport approach** ends up—file import is essentially one of the Agent's source types and does not need to be its own project.

### HTTP Pull (Implemented)

Oddsmaker actively pulls from a query API exposed by the game side. The Control side schedules periodic pulls by (game, environment, sourceKey). **Implemented**: `DimensionPullService` in `services/control-service/` (scheduler + AES-GCM encrypted credential custody + resumable transfer), which translates into `RawDimensionChange(source_type=pull)` and goes through the existing `/v1/batch` event entry point.

```
GET https://api.game-x.com/v1/items?updated_after=1760000000000&limit=1000
Authorization: Bearer ***
→ 200 OK
  {"items": [...], "next_cursor": "..."}
```

Game-side API contract:

- Supports the `updated_after` incremental parameter (not sent on the first pull; subsequently pass the `next_cursor` last delivered by the server, or the millisecond value of the largest `updated_at` on the page).
- The response is a JSON object; the entry array key is probed in the order `items` / `resources` / `levels` / `data`, with an optional `next_cursor` string (an empty string is treated as the end of the window).
- In-row control columns align with the Agent semantics: one of `resource_id`/`item_code`/`level_id`/`id`/`code` is the resource identifier, `op` (defaults to upsert), one of `updated_at`/`version_ts`/`updated_ts` is the version time (an epoch millis number or ISO-8601 text), and the remaining columns go into `attributes`.

Control-side behavior (all implemented):

- **Encrypted credential custody**: the `credential` is encrypted with AES-GCM and stored in the database when the record is created (key `ODDSMAKER_CREDENTIAL_KEY`; the default falls back to a built-in dev key with a WARN, and must be changed in production); no API response ever echoes the ciphertext.
- **Scheduling**: a 15s tick; due-ness is judged by the configured `intervalSeconds` (default 300s); a single page has bounded latency, and `next_cursor` continues paging on the next tick.
- **Resumable transfer**: cursor = the server's `next_cursor`, falling back by default to the largest `version_ts` on the page; it advances only after all pushes succeed, and on failure the next round replays the same window with the old breakpoint—downstream `ReplacingMergeTree(version_ts)` makes replay idempotent.
- **Same-table observability**: pull progress is written directly to `dimension_sync_status` (`source_type=pull`), and latency is shown via the existing `GET /api/dimensions/sync-status` under the same measure as the Agent reporting source.

Configuration API (`dimension:read` to read / `dimension:manage` to write):

```http
GET    /api/games/{gameId}/dimension-pulls           列出该游戏全部 Pull 配置
POST   /api/games/{gameId}/dimension-pulls           建档（environment/sourceKey/endpoint/credential 必填）
PUT    /api/games/{gameId}/dimension-pulls/{id}      更新（credential 缺省=保留既有凭证）
DELETE /api/games/{gameId}/dimension-pulls/{id}      删除
POST   /api/games/{gameId}/dimension-pulls/{id}/run  手动触发一轮拉取
```

Console entry: the "HTTP Pull Source Config" lower half of the `/dimensions` page—create/edit (leaving the credential blank = keep the existing one)/delete/pull now (both success and failure produce result text), plus YAML bulk import/export (the export never writes credentials to disk; you can edit it and paste the whole thing back to import for a bulk update). The API can also be used directly; both are gated by the same permissions.

Example create request body:

```json
{
  "environment": "prod",
  "sourceKey": "items",
  "dimType": "item",
  "endpoint": "https://api.game-x.com/v1/items",
  "credential": "bearer-token-plaintext",
  "intervalSeconds": 300,
  "pageLimit": 1000,
  "enabled": true
}
```

- Suited to scenarios where the **game side has API capability, is unwilling to deploy an Agent, and is unwilling to enable binlog** (compared with the Agent, the game side deploys nothing and exposes only a single read-only query API).

### Debezium CDC (Not Implemented)

The game side deploys Kafka Connect + the Debezium Source Connector to write binlog changes of the `items` table to a Kafka topic, and Oddsmaker subscribes to that topic and does the transformation. This approach currently has no corresponding implementation and is planned; when it lands, the consumer side can reuse the `dimension-sync-job` event entry point.

```
游戏 MySQL → Debezium → Kafka topic: game_x.db.items
                                    ↓
                  Oddsmaker dimension-consumer (Flink job 或独立服务)
                                    ↓
                           ClickHouse item_dim
```

- Second-level latency, suited to real-time risk control.
- The Oddsmaker side only subscribes to the topic and does not touch the game database.
- Requires the game side to: enable binlog, grant a read-only account, deploy Kafka Connect (or reuse existing Kafka).
- Configure a Debezium heartbeat table to keep it alive and avoid latency buildup from low-frequency updates.
- Note DDL changes: when the game side adds a column to the `items` table, Debezium's schema history detects it, and the transformation layer must be able to handle new fields.

#### Event format and target-table mapping (in-repo prep, 2026-10-19; to be decided after external unlock)

Of the three unlock items, "CDC event format and target table mapping" is the half that can be prepared inside this repo: the Debezium envelope is fixed by the connector; what must be chosen is the transformation path from that envelope to `RawDimensionChange`. Current constraint: the `dimension-sync-agent` kafka source (`source.type=kafka`) expects a **flat JSON** message — control columns `dim_type` / `resource_id` / `op` / `version_ts` (case-insensitive; aliases and defaults per `JdbcSource.mapRow`: op defaults to upsert, version_ts defaults to consumption time, `item_code`/`level_id`/`id` are accepted as resource_id aliases), everything else goes into `attributes`. The Debezium envelope is nested (`payload.before` / `payload.after` / `payload.op` / `ts_ms`, with a tombstone empty message after each delete). A **raw Debezium topic therefore cannot be fed to that kafka source directly**: the message root is an object but carries no `resource_id` control column, so every record is skipped as a bad message (missing resource_id → dropped, offset still advances) — option C looks zero-cost but in practice silently drops everything.

| Option | Where the transform lives | In-repo change | Trade-offs |
|---|---|---|---|
| A consumer reads the envelope natively | Oddsmaker gains a CDC-aware source (in `dimension-sync-job` or a new Agent source type) | one new source type + tests | zero transform config on the game side; new columns land in `attributes` automatically, schema evolution stays verifiable in-repo; tombstone and `op` collapsing must be handled by us |
| B flatten via Kafka-side SMT | game side / shared cluster applies a Debezium SMT (`ExtractNewRecordState` handles insert/update/delete via the header op, plus field renames) | zero | fastest to land; but no built-in SMT renames fields (needs a custom transform or a lowercase column-name convention), and the transform rules drift in the game-side cluster, unverifiable in-repo |
| C Agent kafka source reads the raw topic | none | zero | **not viable** (see above: no control columns → everything skipped); do not wire it this way |

Recommendation (not decided; choose after unlock based on game-side cooperation): if the game side will not carry transform config, take A (verifiable in-repo, pinnable with tests); if they prefer to keep config on the Kafka side, take B and register the exact SMT config in this section to guard against game-side drift.

Field mapping from the envelope to `RawDimensionChange` (option A; option B converges to the same mapping after flattening):

| Debezium envelope | `RawDimensionChange` | Notes |
|---|---|---|
| `payload.op` = `c` / `i` / `u` | `op=upsert` | three values collapse into upsert, matching the `ReplacingMergeTree(version_ts)` overwrite semantics of `item_dim` |
| `payload.op` = `d` (+ tombstone) | `op=delete` | downstream currently ignores delete (see the "Version handling" section); CDC adds no new semantics |
| columns of `payload.after` (`before` for d) | control columns + `attributes` | primary key column → `resource_id`; business timestamp column → `version_ts` (epoch millis or ISO-8601; if absent the existing default = consumption time applies, no new default is invented for CDC); remaining columns enter `attributes` by name |
| `ts_ms` / `source.ts_ms` | not mapped | capture time ≠ business version; binlog latency must not impersonate version order |

The landing boundary is unchanged: the consumer reuses the `dimension-sync-job` dimension event entry, the target tables remain `item_dim`/`level_dim`, and CDC only replaces the upstream channel — the intermediate format and output schema in the "Unified abstraction" section are untouched.

## Unified Abstraction

Whichever approach is used, the intermediate format that lands and the output schema are completely identical.

### Intermediate Format: `RawDimensionChange`

```json
{
  "source_type": "webhook | agent | pull | debezium",
  "dim_type": "item",
  "op": "upsert | delete",
  "resource_id": "sword_001",
  "attributes": { "name": "铁剑", "rarity": "common", "type": "weapon" },
  "version_ts": 1760000000000,
  "game_id": "game_x",
  "environment": "prod"
}
```

Each Provider's job is to translate source data into this format; **the downstream logic is written only once**.

> **Implementation status**: `source_type` is currently written only by the Pull pipeline (`DimensionPullService` translates into `RawDimensionChange(source_type=pull)`); the Agent / Webhook channels do not carry this field, and the four-value enum is the target abstraction.

### Output: `item_dim` (ClickHouse)

```sql
CREATE TABLE item_dim (
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
```

> **Target shape (not landed)**: the SCD2 shape with `valid_from` / `valid_to` columns + `ReplacingMergeTree(is_current)` + `ORDER BY` including `valid_from` has not landed—`dimensions.sql` has no `valid_to` column, and the job always writes `is_current = 1`.

### Version Handling (Shared Logic)

When an item's attributes change (such as a rename or a rarity adjustment), the transformation layer writes a new row: `version_ts` takes the source change time, and `is_current` is always 1; `ReplacingMergeTree(version_ts)` guarantees that the latest version wins for the same `resource_id`, and the old version is naturally replaced. `op=delete` is currently ignored by the downstream—delist semantics are not distinguished and it is still written as a new row (there is no delete branch).

> **Target shape (not landed)**: SCD2 semantics (the old version sets `valid_to` to the change time + `is_current = 0`; the new version has `valid_from` / `valid_to = null`) has not landed—`dimensions.sql` has no `valid_to` column, and the job always writes `is_current = 1`. The "time travel" problem of historical facts joining the corresponding versioned dimension by event time therefore remains unsolved.

The version handling logic lives in the transformation layer and is **shared by all Providers**; do not scatter it across individual Provider implementations.

## Configuration-Driven (Control Surface Implemented, Agent Property Migration Deferred)

Each game's dimension sync is configured in the Control Service; onboarding a new game is just filling in tables, no code changes.

- **Implemented (2026-10, HTTP Pull)**: the game-side configuration of pull sources moves to the Control surface—the `dimension_pull_config` table stores endpoint/credential ciphertext/interval/paging/breakpoint by (game, environment, sourceKey), and filling in the table via the `dimension-pulls` REST API completes onboarding; the scheduler needs no code changes.
- **Implemented (2026-10, Control YAML config surface)**: `dimension-pulls` adds two endpoints for declarative configuration—`GET /export` exports all configuration as YAML (credentials never written to disk), `POST /import` locates each record by (environment, sourceKey) and creates/updates it, `credential` defaults to keeping the existing credential, and a method-level transaction guarantees that any single failure rolls back everything. To onboard a new game you can first draft a YAML file and import it in one go; afterwards an export gives you a versionable inventory.
- **Implemented (2026-10, Control custody of the Agent property surface)**: Agent deployment properties move to the Control surface—the `dimension_agent_config` table stores non-secret props text + the whole set of secret keys as AES-GCM ciphertext by (game, environment, agentKey), filled in via the `agent-configs` REST API; `/api/dimensions/agent-configs/resolved` is the Agent's config retrieval endpoint (injects game.id/game.environment/status.source-key, decrypts and merges into a single Properties text).
- **Implemented (2026-10, Agent-side config retrieval)**: configuring `control.config-url` in the Agent's local `agent.properties` enables custody—fetch `resolved` once at startup, and delivered keys override local values key by key; if the fetch fails, it falls back entirely to the local file (Control being unreachable does not block startup); precedence local < Control-delivered < System property. Custody switches/local-path keys (`control.config-url`, `agent.checkpoint-path`) always stay local.
- **Implemented (2026-10, managed config hot update)**: adding `control.config-refresh-seconds` (>0) locally enables polling hot update—re-fetch `resolved` every N seconds; if the delivered text is identical to the currently applied one, nothing happens; if it changed, rebuild source/sink/status with the new config (the old pieces are released only after all new pieces are constructed successfully, and the checkpoint is untouched so the breakpoint continues across the source switch), and the change takes effect immediately without waiting for the current polling interval to elapse; if the fetch fails or the new config fails validation, the current config is retained (Control being unreachable does not block polling). Boundary: when source.type switches across classes (e.g. mysql→kafka), cursor semantics are interpreted according to the new source (watermarks and offsets are not interchangeable), so the breakpoint must be cleared or the starting point confirmed.
- **Implemented (Agent / Flink jobs)**: the Agent's local `agent.properties` (five source types) and the Flink jobs' System properties (`kafka.topic`, `clickhouse.*`, etc.) remain the runtime configuration surface when custody is not enabled.
- **Not implemented**: the `provider:` polymorphism and the `auth.token_ref` secret reference table in the target shape below—currently Pull/Agent credentials are inline plaintext inputs + AES-GCM encrypted custody, with no indirect reference by secret name.

### Pull Config YAML (Implemented Shape)

```yaml
# oddsmaker 维度同步 Pull 配置导出（凭证不回显；导入时 credential 缺省=保留既有）
dimensions:
  items:
    environment: prod                          # 必填，须属于该游戏
    dimType: item                              # 可缺省，缺省 item
    endpoint: https://api.game-c.com/v1/items  # 必填，须 http/https
    credential: bearer-token                   # 仅首建必填；更新缺省=保留既有
    intervalSeconds: 300                       # 可缺省，缺省 300（10..86400 秒）
    pageLimit: 1000                            # 可缺省，缺省 1000（1..5000）
    enabled: true                              # 可缺省，缺省 true
```

The `dimensions` mapping key is the `sourceKey`, aligned with the table's unique constraint (game, environment, sourceKey)—changing `environment` means a new record for another environment. Field whitelist validation: unknown fields produce a precise error (to prevent typos like `endpont` from failing silently). The export contains no credential ciphertext; export→import can write back non-credential fields as-is, but importing into a new record for an (env, sourceKey) that has never been configured requires supplying `credential` (credentials never leave Control's plaintext custody boundary).

| Endpoint | Description | Permission |
| --- | --- | --- |
| `GET /api/games/{gameId}/dimension-pulls/export` | Exports all Pull config for the game as YAML (`application/x-yaml`, credentials never written to disk) | `dimension:read` |
| `POST /api/games/{gameId}/dimension-pulls/import` | The request body is YAML text; locates each record by (environment, sourceKey) and creates/updates it, returning `{created, updated, total}`; any single failure rolls back everything | `dimension:manage` |

### Agent Property Surface Custody (Implemented; Startup Fetch + Optional Polling Hot Update)

Each Agent deployment is one record, unique constraint (game, environment, agentKey). Key names follow the dot-key naming of `agent.properties` and are split into two halves by visibility:

- **props (non-secret, visible and editable as plaintext)**: `gateway.endpoint`, `source.*` (URL/query/directory/protocol, etc.), `agent.batch-size`, `agent.poll-seconds`, `status.url`, `dimension.type`; key whitelist validation (unknown keys / illegal value shapes produce precise errors), `source.type` is one of five.
- **secrets (secret keys, under AES-GCM ciphertext custody, the management surface echoes only key names)**: `gateway.api-key`, `status.token`, `source.jdbc.password`, `source.kafka.password`, `source.kafka.ssl-truststore-password`, `source.kafka.ssl-keystore-password`; creation must include `gateway.api-key`, update defaults to keeping existing secrets (explicitly supplying the map rotates them wholesale).
- **Keys not on the custody surface**: `game.id` / `game.environment` / `status.source-key` are injected automatically when delivering for the row (gameId, environment, agentKey); `agent.checkpoint-path` is a game-side local path and always stays local.

| Endpoint | Description | Permission |
| --- | --- | --- |
| `GET /api/games/{gameId}/agent-configs` | Lists all Agent config records for the game (props visible, secrets echo only the key-name list) | `dimension:read` |
| `POST /api/games/{gameId}/agent-configs` | Create: `{environment, agentKey, props, secrets, enabled}`; environment must belong to the game, and agentKey is unique per game per environment | `dimension:manage` |
| `PUT /api/games/{gameId}/agent-configs/{id}` | Update: props fully replaced; secrets default to keeping existing secrets | `dimension:manage` |
| `DELETE /api/games/{gameId}/agent-configs/{id}` | Delete | `dimension:manage` |
| `GET /api/dimensions/agent-configs/resolved?gameId=&environment=&agentKey=` | Agent config retrieval: the response body is complete text that can be `Properties.load`-ed (three injected keys + props + decrypted secrets); disabled records are refused delivery (any non-200 endpoint falls back to the local file) | `dimension:read` |

Cross-field combination validation (e.g. SASL_* protocols must carry a mechanism and credentials) is still backstopped by the Agent's startup fail-fast; the Control side only handles the key whitelist and value shapes. Configuring `control.config-refresh-seconds` (>0, requires `control.config-url`) locally on the Agent side enables hot update: re-fetch `resolved` every N seconds, and if the text changed, switch source/channel (the checkpoint is untouched); on failure, the current config is retained.

Console entry: the "Agent Managed Config" area of the `/dimensions` page—create/edit/delete, with props and secrets in two separate text boxes (secrets are `key=value` per line, and the server echoes only the key-name list); leaving secrets blank on edit = keep existing secrets, filling them in rotates them wholesale; for a disabled record, the Agent falls back to the local file on fetch. The API can also be used directly; both are gated by the same permissions.

### Target Shape (Not Implemented: provider Polymorphism + Secret Reference)

The shape described below, "one YAML describing all dimension sources for the game", is currently not implemented; only Pull has landed:

```yaml
# 游戏 A：大厂、走 CDC
dimensions:
  item_dim:
    provider: debezium
    topic: game_a.db.items
    consumer_group: oddsmaker-dim-sync
    mapping:
      resource_id: "${after.item_code}"
      name: "${after.item_name}"
      rarity: "${after.rarity}"

# 游戏 B：独立工作室、走 Agent
dimensions:
  item_dim:
    provider: agent
    # Agent 在游戏方部署，Oddsmaker 只收事件
    auth_token_ref: game_b_dim_token

# 游戏 C：成长期、走 Pull
dimensions:
  item_dim:
    provider: pull
    url: https://api.game-c.com/v1/items
    schedule: "*/10 * * * *"
    auth:
      type: bearer
      token_ref: game_c_api_token
    mapping:
      resource_id: "${code}"
      name: "${display_name}"
```

## Monitoring and Operations

The Control Service exposes each game's dimension sync status:

- Last successful sync time
- Sync latency (end-to-end time from a source change to landing in ClickHouse)
- Error count and the most recent error message
- Checkpoint position (local checkpoint in Agent mode; `next_cursor` breakpoint in HTTP Pull mode, same table, same measure)
- Change in total dimension record count (alert on abnormal swings, which may indicate a problem at the source)

When integration has problems, both the game side and Oddsmaker operations can quickly locate which step is at fault.

## Implementation Priority

There is no need to do everything at once. A suggested value/cost ordering:

1. **Webhook Push**: already usable (`/v1/batch` + `event_type=dimension`, zero new components).
2. **Sync Agent (MySQL/PostgreSQL source)**: implemented.
3. **HTTP Pull**: implemented (2026-10, Control scheduler + encrypted credential custody + resumable transfer, end-to-end tested against a mock game API).
4. **Sync Agent (CSV/Excel source)**: implemented.
5. **Debezium CDC**: not implemented.

## Relationship to the Resource Event Design

This document is a supplement to the resource event design (fact data):

| | Resource events (facts) | Dimension sync (dimensions) |
|---|---|---|
| Data source | SDK player behavior reporting | Game business DB / config |
| Written table | `events` | `item_dim` / `level_dim` / ... |
| Frequency | High (several per player per second) | Low (a few changes per day) |
| Query role | Facts to be aggregated | Dimensions to be joined |
| Sync method | SDK → Gateway → Kafka → ClickHouse | One of the four approaches in this document |

The two converge at the ClickHouse layer, and analytic queries join on keys such as `resource_id`.
