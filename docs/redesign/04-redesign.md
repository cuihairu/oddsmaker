# 04 - Redesign Proposal

> 🌐 Language: **English** · 中文 → [04-redesign.md](04-redesign.md)

## 1. Design Principles

1. **Single-company deployment**: the company is the deployment boundary, not a business tenant; the multi-tenant model is no longer built.
2. **Multi-game management**: `Game` is the core business object; one platform manages multiple games.
3. **Explicit environment isolation**: all events, configs, API Keys, and risk policies are bound to `game_id + environment`.
4. **Game verticalization**: the event system aligns with the game industry, covering sessions, payments, resources, levels, ads, errors, and experiments.
5. **Built-in risk control**: risk policies, real-time detection, alerting, disposition, and audit are main-pipeline capabilities.
6. **Schema governance**: Tracking Plan + JSON Schema + Avro + Registry, preventing event names and properties from running wild.
7. **Client security**: client SDKs only hold a public `api_key`; HMAC is only for the Server SDK.

## 2. Target Architecture

```text
SDK 层
  Web / Android / iOS / Unity / Unreal / Server
  - public api_key
  - typed game events
  - offline queue + batch + gzip
  - device/session management
  - client integrity signals
        |
        v
Gateway Service
  - API Key 校验：key -> game_id + environment
  - Server SDK HMAC 校验
  - JSON Schema / Tracking Plan 校验
  - PII deny/mask/coarse
  - 限流：key + game + env + IP + device
  - 风控前置：黑名单、重放、时间窗、体积、字段异常
        |
        v
Kafka
  - oddsmaker.events_raw
  - oddsmaker.events_validated
  - oddsmaker.risk_events
  - oddsmaker.deadletter
        |
        v
Flink Jobs
  - enrich：去重、GeoIP、UA、归因、Identity Merge
  - sessions：会话切分
  - retention：N-Day + Rolling
  - funnels：N 步漏斗
  - revenue：IAP/Ad/LTV 聚合
  - progression：关卡和任务分析
  - risk：作弊、支付、账号、广告、经济风控
  - realtime：5 分钟运营大屏
        |
        v
Storage
  PostgreSQL
    Game / Environment / ApiKey / User / Role / TrackingPlan / RiskRule / AuditLog
  ClickHouse
    events / identities / sessions / retention / funnels / revenue / risk_events / risk_scores
  Redis
    rate limit / realtime counters / risk short-window state / blacklists
  S3
    raw archive / replay / crash symbols / export
        |
        v
Applications
  Control Service
    游戏、环境、密钥、策略、风控、权限、审计
  Query Service
    固化指标 API、自定义 SQL 安全网关
  Dashboard
    运营实时大屏、风控告警、核心指标
  BI
    Superset / Metabase
```

## 3. Control Plane Model

```text
CompanyConfig
  - company_name
  - deployment_id
  - default_timezone
  - data_region

Game
  - id
  - name
  - genre
  - platforms
  - status

GameEnvironment
  - game_id
  - environment: dev | staging | prod
  - endpoint_policy
  - data_retention_days
  - sampling_policy

ApiKey
  - key_id
  - public_key
  - secret_key_hash only for Server SDK
  - game_id
  - environment
  - key_type: client | server | admin
  - enabled
  - rate_limits
  - pii_policy_id
  - risk_policy_id

User
  - id
  - email
  - display_name
  - global_role: admin | user

RoleBinding
  - user_id
  - scope: global | game | environment
  - game_id nullable
  - environment nullable
  - role: owner | operator | analyst | developer | risk_admin | viewer

TrackingPlan
  - game_id
  - environment
  - version
  - event definitions
  - property dictionary
  - cardinality limits

RiskRule
  - game_id
  - environment
  - rule_id
  - rule_type: threshold | sequence | velocity | blacklist | model
  - severity
  - action: mark | alert | block | review | webhook
```

## 4. Unified Event Schema

The target fields use `game_id + environment`; `tenant_id` is no longer used.

```json
{
  "type": "record",
  "name": "GameEvent",
  "namespace": "io.oddsmaker.v1",
  "fields": [
    {"name": "event_id", "type": "string"},
    {"name": "game_id", "type": "string"},
    {"name": "environment", "type": "string"},
    {"name": "event_type", "type": "string"},
    {"name": "event_name", "type": "string"},

    {"name": "user_id", "type": ["null", "string"], "default": null},
    {"name": "device_id", "type": "string"},
    {"name": "player_id", "type": ["null", "string"], "default": null},
    {"name": "character_id", "type": ["null", "string"], "default": null},
    {"name": "session_id", "type": ["null", "string"], "default": null},

    {"name": "ts_client", "type": "long"},
    {"name": "ts_server", "type": ["null", "long"], "default": null},

    {"name": "platform", "type": ["null", "string"], "default": null},
    {"name": "app_version", "type": ["null", "string"], "default": null},
    {"name": "sdk_version", "type": ["null", "string"], "default": null},
    {"name": "country", "type": ["null", "string"], "default": null},
    {"name": "client_ip", "type": ["null", "string"], "default": null},
    {"name": "user_agent", "type": ["null", "string"], "default": null},

    {"name": "server_id", "type": ["null", "string"], "default": null},
    {"name": "guild_id", "type": ["null", "string"], "default": null},
    {"name": "match_id", "type": ["null", "string"], "default": null},
    {"name": "level_id", "type": ["null", "string"], "default": null},
    {"name": "game_mode", "type": ["null", "string"], "default": null},
    {"name": "difficulty", "type": ["null", "string"], "default": null},
    {"name": "progression_path", "type": {"type": "array", "items": "string"}, "default": []},

    {"name": "order_id", "type": ["null", "string"], "default": null},
    {"name": "product_id", "type": ["null", "string"], "default": null},
    {"name": "revenue_amount", "type": ["null", "double"], "default": null},
    {"name": "revenue_currency", "type": ["null", "string"], "default": null},
    {"name": "receipt_hash", "type": ["null", "string"], "default": null},

    {"name": "virtual_currency", "type": ["null", "string"], "default": null},
    {"name": "virtual_amount", "type": ["null", "long"], "default": null},
    {"name": "flow_type", "type": ["null", "string"], "default": null},
    {"name": "item_id", "type": ["null", "string"], "default": null},

    {"name": "ad_network", "type": ["null", "string"], "default": null},
    {"name": "ad_placement", "type": ["null", "string"], "default": null},
    {"name": "ad_format", "type": ["null", "string"], "default": null},
    {"name": "ad_impression_id", "type": ["null", "string"], "default": null},

    {"name": "experiments", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "attribution", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "risk_context", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "props", "type": {"type": "map", "values": "string"}, "default": {}}
  ]
}
```

### 4.1 Platform Event Convention

The following events are reported under the agreed event names, use the same envelope, and do not get a separate schema:

- `experiment_exposure`: experiment exposure. The SDK reports after a bucketing hit, with props carrying `exp` (experiment id) and `variant` (variant name). Snapshot rows in `experiment_metric_snapshots` are backfilled with `metric_name='exposure_users'` (the basis for SRM sample size), queryable through the exposure column of `/api/experiments/{id}/results`; see `analysis/experiments` for SDK integration.

## 5. ClickHouse Core Tables

**Database topology**: shared tables (SHARED). (**Current-state note**: it is currently shared tables (SHARED); DEDICATED dedicated-database is only reserved as an enum value and is not implemented — `StorageProfileEntity.IsolationStrategy` is only an enum, with no code path that creates a database per game.)

```sql
-- events
CREATE TABLE events
(
    game_id LowCardinality(String),
    environment LowCardinality(String),
    event_date Date DEFAULT toDate(ts_server),
    ts_server DateTime64(3) DEFAULT now64(3),
    ts_client DateTime64(3),

    event_id String,
    event_type LowCardinality(String),
    event_name LowCardinality(String),

    user_id String DEFAULT '',
    device_id String,
    player_id String DEFAULT '',
    character_id String DEFAULT '',
    session_id String DEFAULT '',

    platform LowCardinality(String) DEFAULT '',
    app_version LowCardinality(String) DEFAULT '',
    sdk_version LowCardinality(String) DEFAULT '',
    country FixedString(2) DEFAULT '',
    client_ip_hash String DEFAULT '',
    user_agent String DEFAULT '',

    server_id String DEFAULT '',
    guild_id String DEFAULT '',
    match_id String DEFAULT '',
    level_id String DEFAULT '',
    game_mode LowCardinality(String) DEFAULT '',
    difficulty LowCardinality(String) DEFAULT '',

    order_id String DEFAULT '',
    product_id String DEFAULT '',
    revenue_amount Decimal(18,4) DEFAULT 0,
    revenue_currency FixedString(3) DEFAULT '',
    receipt_hash String DEFAULT '',

    resource_id LowCardinality(String) DEFAULT '',
    resource_amount Int64 DEFAULT 0,
    flow_type LowCardinality(String) DEFAULT '',
    item_id String DEFAULT '',

    ad_network LowCardinality(String) DEFAULT '',
    ad_placement String DEFAULT '',
    ad_format LowCardinality(String) DEFAULT '',
    ad_impression_id String DEFAULT '',

    experiments Map(String, String),
    attribution Map(String, String),
    risk_context Map(String, String),
    props Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, event_type, event_date, player_id, user_id, device_id, ts_server, event_id)
TTL event_date + INTERVAL 365 DAY;

-- risk_events
CREATE TABLE risk_events
(
    ts DateTime64(3),
    risk_event_id String,
    source_event_id String DEFAULT '',
    rule_id String,
    risk_type LowCardinality(String),
    severity LowCardinality(String),
    subject_type LowCardinality(String),
    subject_id String,
    score Float32,
    action LowCardinality(String),
    reason String,
    evidence Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (toYYYYMM(ts))
ORDER BY (risk_type, severity, ts, subject_id);

-- identities
CREATE TABLE identities
(
    identity_id String,
    user_id String DEFAULT '',
    player_id String DEFAULT '',
    character_ids Array(String),
    device_ids Array(String),
    first_seen DateTime64(3),
    last_seen DateTime64(3),
    risk_score Float32 DEFAULT 0
)
ENGINE = ReplacingMergeTree(last_seen)
PARTITION BY (toYYYYMM(last_seen))
ORDER BY (identity_id);
```

## 6. Risk Control Module

### 6.1 Risk Inputs

- Request metadata: IP, UA, API Key, signature, time window, body size.
- Device signals: device_id, fingerprint, root/jailbreak, emulator, debugger.
- Account signals: user_id, player_id, login method, multi-device relationships.
- Behavior signals: event frequency, level completion time, resource flow, match results, session rhythm.
- Payment signals: order_id, receipt_hash, product, amount, refund, sandbox environment.
- Ad signals: impression_id, placement, network, reward claim frequency.

### 6.2 Risk Detection Layers

| Layer | Location | Purpose |
|---|---|---|
| Hard block | Gateway | blacklist, signature errors, replay, over-limit, illegal environment |
| Short-window rules | Redis + Gateway/Flink | per-minute event spikes, IP/device clustering, frequency anomalies |
| Stateful rules | Flink | multi-step behavior sequences, resource anomalies, payment anomalies, ad anomalies |
| Offline back-scan | ClickHouse | batch account farming, long-term economy anomalies, channel quality analysis |
| Model scoring | Risk Service | churn, cheating, payment fraud, account risk |

### 6.3 Risk Actions

- `mark`: only tags, does not affect the pipeline.
- `alert`: alerts to the Dashboard/Webhook.
- `block`: rejected by the Gateway or the game server.
- `review`: enters the manual review queue.
- `throttle`: reduces sampling or rate-limits.
- `shadow_ban`: output to the game server to enforce a shadow ban. (**Not implemented**: the `ActionType` enum has no such value; this is a design intention only.)

### 6.4 Risk Policy Example

Risk rules are created per `game_id` (`POST /api/risk-rules`, `gameId` is required); the examples omit the remaining fields:

```yaml
rule_id: payment_receipt_reuse
game_id: game_demo
environment: prod
risk_type: payment
severity: high
window: 24h
condition:
  receipt_hash_distinct_users_gt: 1
action: block
```

```yaml
rule_id: resource_inflation_spike
game_id: game_demo
environment: prod
risk_type: economy
severity: medium
window: 10m
condition:
  resource_id: gold
  source_amount_gt_p99_multiplier: 5
action: alert
```

## 7. Module Evolution Cross-Reference

| Module | Current | After the redesign |
|---|---|---|
| Control plane | Project and Organization coexist | Game + Environment + ApiKey + Policy |
| Event fields | project_id / tenant_id / app_id mixed together | game_id + environment |
| Gateway | HMAC, rate limiting, some PII capabilities | API Key bound to game/environment + risk control in front |
| Flink | enrichment, sessions, retention, two-step funnels | add N-step funnels, LTV, levels, risk control |
| ClickHouse | tenant_id/app_id partitioning | game_id/environment partitioning |
| SDK | inconsistent parameters | typed game events + public api_key |
| Permissions | over-heavy multi-tenant RBAC | in-company authorization by game/environment |
| Risk control | conceptual | risk_events + risk_rules + actions across the whole pipeline |

## 8. Migration Strategy

1. Add a v1 schema whose fields are `game_id + environment`.
2. Gateway supports a compatibility-period mapping: the old `project_id` or `tenant_id/app_id` is mapped to the new fields.
3. Create a normalized events table in ClickHouse and dual-write for a period.
4. Rewrite the Flink jobs with the new keys and roll out gradually.
5. Release a new major version of the SDK; mark the old parameters deprecated.
6. Delete the `Organization` control-plane API and migration scripts, keeping historical backups.
