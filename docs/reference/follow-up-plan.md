# Follow-up Plan

> 🌐 Language: **English** · 中文 → [follow-up-plan.md](follow-up-plan.md)

This document records Oddsmaker's currently unfinished feature items, ordered by priority, including design, implementation approach, and acceptance criteria. Each item is registered here before work starts, to avoid scattered discussion.

> **2026-09 status sync**: This list is written from an early action-item perspective; status is now annotated by each item's outcome (see the table below and the status line in each section). Stage-level progress uses the repo root `todo.md` as the active tracking table—P7 competitor-gap convergence is fully complete (release notes in [release-notes/v0.2.0.md](../release-notes/v0.2.0.md)), and the subsequent dimension sync Agent (§4) and the Python training pipeline (§6) have also been delivered. All items in this list have been delivered; the repo currently has on the books: P8 MMP attribution (paused) and two open requirements in `todo.md` (Debezium CDC dimension sync, ML anomaly detection into the evaluation batch).

Completed features are documented per-stage:
- [Resource event design](../analysis/jobs) (fact data)
- [Dimension data sync](./dimension-sync) (dimension data)
- [SDK design spec](./sdk-design) (client integration)

## Priority Overview

| # | Item | Stage | Type | Unlock value | Status (2026-09) |
|---|---|---|---|---|---|
| 1 | identity-merge Flink job | P2.2 | Backend job | SDK identify actually takes effect | ✅ Done |
| 2 | risk-job rule dynamism | P3.2 | Job enhancement | Add rules without restart | ✅ Done (RuleFetcher periodic pull + `-D` fallback) |
| 3 | risk-job rule type expansion | P3.2 | Job enhancement | Cover more risk-control scenarios | ✅ Done: all seven types THRESHOLD/FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD/PATTERN live (PATTERN implemented as a keyed state machine, avoiding the flink-cep dependency) |
| 4 | Dimension sync Agent | Cross-cutting | New module | Dimension sync landed | ✅ Delivered: `agents/dimension-sync-agent/` (five source types mysql/postgres/csv/excel/kafka) + sync-status API (V0.9.14) + dimension-sync-job + console dimension sync management page (web `/dimensions`, heartbeat/two-measure latency observability) |
| 5 | Symbolication service | P4.3 | New service | Readable crashes | ✅ Done via an alternative: in-repo symbolication engine (`symbol_mappings.mapping_rules` regex rules, V0.8.5) + CrashFingerprinter; the standalone microservice approach is no longer pursued |
| 6 | Prediction model training pipeline | P4.4 | Pipeline | ML models actually usable | ✅ Delivered: trainable pipeline in `ml/` (churn/pltv/risk/propensity, artifacts are JSON with a built-in heuristic baseline comparison) + Control-side registration/write-back pipeline + training schedule (auto-retrain then re-register); GBDT / real-time scoring are on-demand extensions |

---

## 1. identity-merge Flink job

> **Status (2026-09): Done**—`jobs/flink/identity-merge-job/`; behavior in [jobs.md](../analysis/jobs.md). The text below is the design from that time, kept for reference.

### Goal

Consume the `$identify` events reported by the SDK (event_type=identity), maintain the ClickHouse `identities` table, and merge multiple `device_id` / `player_id` values of the same user into a single `identity_id`, ensuring retention/LTV are not double-counted.

### Background

- The SDK already provides `identify(userId)` / `setPlayer(playerId)` (see [sdk-design.md](./sdk-design)), reporting `$identify` events.
- The ClickHouse `identities` table is already built (schema.sql line 211): `identity_id, user_id, player_id, character_ids, device_ids, first_seen, last_seen, risk_score`.
- What's missing is a job to land these events into the `identities` table.

### Design

```
events_raw (Kafka)
    │ 过滤 event_type='identity'
    ▼
IdentityMergeJob (Flink)
    │ keyBy(user_id)
    │ 状态：identity_id 映射 + device_id/player_id 集合
    │ 归并逻辑：
    │   - user_id 已存在 → 复用 identity_id，追加 device_id/player_id
    │   - user_id 新增 → 生成新 identity_id
    │   - previous_user_id 出现 → 合并两个 identity 到新 user_id
    ▼
ClickHouse identities (ReplacingMergeTree by last_seen)
```

### Implementation Notes

- `keyBy(user_id)` guarantees the merge state of the same user stays on the same parallelism.
- State: `ValueState<IdentityState>`, containing `identity_id`, `Set<device_id>`, `Set<player_id>`, `first_seen`.
- Output: each state change writes one row to `identities`, and `ReplacingMergeTree(last_seen)` merges them automatically.
- TTL: state is retained for 90 days (released after the user has been inactive for a long time).
- Idempotency: deduplicated on `identity_id` + `last_seen`.

### Files Involved

- Add `jobs/flink/identity-merge-job/` (build.gradle.kts + IdentityMergeJob.java)
- Register in `settings.gradle.kts`
- Update `docs/zh/analysis/jobs.md`

### Acceptance

- After reporting a `$identify` event, the corresponding row appears in the `identities` table, and `device_ids` contains the current device_id.
- When the same device identifies two user_ids in succession, the two identities are merged (triggered by `previous_user_id`).
- sessions/retention queries can aggregate by `identity_id` (not double-counting devices and users).

---

## 2. risk-job rule dynamism

> **Status (2026-09): Done**—`RuleFetcher` (a Runnable singleton + periodic pull of Control's active rules at a refreshMs interval, with `-D` static rules as a fallback); instead of Broadcast State it uses a shared AtomicReference rule set, which is equivalent in effect (add rules without restart).

### Goal

risk-job currently configures rules with `-D` system properties (`risk.threshold.amount`, etc.), and adding a rule requires a restart. Change it to pull active `RiskRuleEntity` from the Control Service by `game_id + environment`, refreshing periodically.

### Design

```
Control Service (RiskRuleRepo)
    │ GET /api/risk/rules?gameId=X&env=prod&status=ACTIVE
    │ （定时拉取，默认 60s）
    ▼
RuleSupplier (Flink 算子状态 / 广播流)
    │ 广播到所有 risk 检测算子
    ▼
risk-job 检测逻辑（按规则配置执行）
```

### Implementation Notes

- Use the Flink **Broadcast State Pattern**: the rule stream is broadcast to the event stream, and event processing reads the current rules.
- `RuleFetcher`: periodic HTTP pull, parsing JSON into `List<RiskRule>`.
- Rule mapping: `RiskRuleEntity.ruleType` → the corresponding detection function (THRESHOLD/FREQUENCY/...).
- Rule invalidation: on pull, rules that are no longer ACTIVE are deleted and cleared from the broadcast.
- Compatibility: keep `-D` system properties as a fallback (use static rules when the Control Service is unavailable).

### Files Involved

- `jobs/flink/risk-job/.../RuleFetcher.java` (new)
- `jobs/flink/risk-job/.../RiskJob.java` (modified: wire in the broadcast stream)
- Control Service exposes a read-only rule query API (reuse it if one already exists)

> **Implementation correction**: `RuleBroadcaster` was not built—the rule set is held in a shared `AtomicReference` and the Broadcast State Pattern was not used (see the status line in §2).

### Acceptance

- Add a RiskRule in the Control Service; it takes effect in risk-job within 60s, with no restart.
- Change the rule to PAUSED; it stops triggering within 60s.
- When the Control Service is unavailable, risk-job falls back to `-D` static rules.

---

## 3. risk-job rule type expansion

> **Status (2026-09): ✅ Fully done**—all seven rule types THRESHOLD / FREQUENCY / VELOCITY / RATIO / DUPLICATE_RECEIPT / AD_REWARD / PATTERN are live in `RiskJob` (with unit tests). The PATTERN sequence rule ultimately uses a **KeyedProcessFunction + ValueState state machine** (with `PatternFunction.advance` as a pure-function core): ordered sequences of ≥2 steps, the window counted from the first step, lazy invalidation when the window is exceeded, automatic restart when the first step repeats, and reset after a hit so it can match again; rules are delivered via the control side's `ruleConditions.sequence` + `timeWindowMinutes`, and both create/update perform 2-8 step sequence validation. The flink-cep dependency was not introduced (no new dependency, no extra state-backend burden, and the logic can be unit-tested as pure functions).

### Goal

Currently only THRESHOLD + FREQUENCY are supported. Add the three rule types VELOCITY / RATIO / PATTERN, covering all detection scenarios listed in roadmap P3.2.

### Design

| Rule type | Detection logic | Flink implementation |
|---|---|---|
| THRESHOLD | `resource_amount > N` | Per-event filter (implemented) |
| FREQUENCY | Event count within a window > N | sliding window count (implemented) |
| **VELOCITY** | Total resource change within a window > N | sliding window sum(resource_amount) |
| **RATIO** | source/sink ratio within a window exceeds a threshold | dual-stream join or same-window aggregation |
| **PATTERN** | Multi-event sequence (e.g. login→purchase→refund within a short time) | CEP (design draft; actually implemented as a keyed state machine, now live) |

### Implementation Notes

- VELOCITY: in the existing FREQUENCY window, replace `count()` with `sum(resource_amount)`.
- RATIO: within the same window, split into two aggregations for `flow_type='source'` and `'sink'`, and compare the ratio.
- PATTERN: ~~use the `org.apache.flink.cep` library~~ → actual implementation: `inputs.filter(inPatternSequence).keyBy(subjectWindowKey).process(new PatternFunction(rules))`, where `PatternFunction` uses `ValueState` (with a 1-day TTL as a backstop for reclamation) to maintain "next-step index + first-step timestamp", advancing incrementally per event, and outputting a RiskHit on a hit and resetting.
- ~~Implement a `RuleDetector` interface per rule type, with `RuleBroadcaster` dispatching by rule type~~ → actual implementation: all seven rule types live in `RiskJob.java` + `RuleFetcher.java` (a shared `AtomicReference` rule table, no Broadcast State used, and the repo has no `*Detector` files).

### Files Involved

- ~~The detector package such as `jobs/flink/risk-job/.../detector/ThresholdDetector.java`~~ → **Not built**: all seven rule types are actually implemented in `RiskJob.java` + `RuleFetcher.java`; there are no `*Detector` files under the repo's `jobs/flink/risk-job`
- ~~`jobs/flink/risk-job/.../detector/PatternDetector.java` (new, depends on flink-cep)~~ → actually implemented in `RiskJob.PatternFunction` (same file as the other rules; no separate detector package was created)

### Acceptance

- VELOCITY: within 10 minutes, the source total for a resource exceeds 1,000,000 → triggers.
- RATIO: within 10 minutes, source/sink > 10 → triggers (abnormal production ratio).
- PATTERN: the same subject shows `level_complete` → `revenue` → `refund` within 5 minutes → triggers.

---

## 4. Dimension sync Agent

> **Status (2026-09): Delivered**—the repo side (`jobs/flink/dimension-sync-job/` writing dimension tables with SCD2 + `schema/sql/clickhouse/dimensions.sql`) was completed earlier; this batch delivers the Control-side `dimension_sync_status` table (V0.9.14) + the `/api/dimensions/sync-status` heartbeat reporting/query API (dimension:read / dimension:manage permissions), plus the Agent itself, `agents/dimension-sync-agent/`—a **standalone Gradle module with zero in-repo dependencies** (only Jackson + kafka-clients + JDK HttpClient + runtimeOnly JDBC drivers). All five source types are in place: mysql/postgres incremental queries, csv/excel directory scanning (excel uses the standard library's zip+xml for minimal xlsx parsing, no POI, file-granularity breakpoints same as csv), kafka subscription (manages its own offset checkpoint `k:0=42;1=57`, not relying on the broker group offset; **real-broker end-to-end has been tested all green**—apache/kafka:3.7.0 single-node KRaft: earliest start / breakpoint seek resumption (checkpoint takes precedence over group offset) / catching up on partitions expanded while running / poison-pill skip advances the offset / drain cap of 100 rounds resumes with no loss, and the cases auto-SKIP when the broker is unreachable; the tests fixed an offset bookkeeping defect: a partition with no new data in the current round once dropped an entry from the cursor and triggered a full replay in the next round; the SASL_PLAINTEXT+SCRAM auth loop (including sync failure with wrong credentials and config fail-fast) and 3-node cluster multi-address bootstrap / single-broker-failure fallback have been filled in with tests (8645547: `KafkaSourceSaslBrokerE2ETest` / `KafkaSourceMultiBrokerE2ETest`, auto-SKIP when the broker is unreachable), throughput on the order of 200k rows drained at 16k msg/s with zero loss). The console management page (web `/dimensions`) shows heartbeat health and two-measure latency read-only under `dimension:read`, degrading honestly when there is no data. When a standalone repository is needed, just do a git subtree split of the `agents/dimension-sync-agent/` directory, with no code changes.

### Goal

Land the `oddsmaker-agent` designed in [dimension-sync.md](./dimension-sync), providing DB / file / Push source adapters so the game side can integrate dimension sync with zero code.

### Design

See the full design in [dimension-sync.md](./dimension-sync). The Agent is a standalone repository `oddsmaker-agent`; this repo is only responsible for:
- Dimension event reception on the Gateway side (**reusing `/v1/batch`, `event_type='dimension'`, no separate endpoint**)
- The Flink side consuming `dimension_define` events and writing `item_dim` / `level_dim`
- Sync status monitoring on the Control Service side

### Implementation Notes (This Repo's Part)

- Gateway: `POST /v1/batch` already supports arbitrary events; a dimension event is just a special event with `event_type='dimension'`.
- Add a Flink job `dimension-sync-job`: consumes dimension events in events_raw and writes dimension tables with SCD2 logic.
- Control Service: add a `dimension_sync_status` table recording each game's last sync time/offset.
- ClickHouse: add `item_dim` / `level_dim` tables (SCD2, see dimension-sync.md).

The Agent itself (`agents/dimension-sync-agent/`, zero in-repo dependencies and splittable into a standalone repo) provides:
- `mysql` / `postgres` source (incremental query: a single ? placeholder + ORDER BY on the watermark column)
- `csv` source (scans the directory in filename order, file-granularity breakpoints)
- A sink that pushes to the Gateway `/v1/batch` (NDJSON, `event_name=dimension_define`)
- A heartbeat to Control `/api/dimensions/sync-status` each round

### Files Involved (This Repo)

- Add `jobs/flink/dimension-sync-job/` (consumes dimension events, writes dimension tables)
- Add `schema/sql/clickhouse/dimensions.sql` (item_dim / level_dim SCD2 tables)
- Add a `DimensionSyncStatus` entity and API to the Control Service

### Acceptance

- Push one item change via the Agent; the corresponding row appears in ClickHouse `item_dim` within 60s.
- After an item rename, a new version row appears in item_dim (is_current=1, ReplacingMergeTree(version_ts) lets the latest version win; SCD2 valid_to/is_current=0 not implemented).
- The Control Service's `/api/dimensions/sync-status` returns each game's sync latency.

---

## 5. Symbolication service

> **Status (2026-09): Done via an alternative; the original approach is closed**—no standalone Python/Go microservice was built; instead a lightweight in-repo approach landed in P6: the Gateway CrashFingerprinter (SHA-256 fingerprint normalized over the first 8 stack frames) + the Control symbolication engine (`symbol_mappings.mapping_rules` regex rules, V0.8.5 migration) + `/api/crash-metrics/{gameId}/symbolicate`. This meets the "readable crashes" goal and avoids introducing an extra service stack.

### Goal

The stack_trace in crash reports is raw addresses (offsets) and needs to be symbolicated into readable function names/file names/line numbers. Provide management of dSYM (iOS) / Proguard mapping (Android) / source map (Web) + a symbolication API.

### Design

```
SDK 上报 crash (props.stack_trace = 原始地址)
    │
    ▼
Symbolicator Service (独立微服务)
    │ 查询 mapping 文件（按 app_version + platform）
    │ 调用对应符号化器：
    │   - iOS: dSYM (dwarfdump / atos)
    │   - Android: proguard mapping (retrace)
    │   - Web: source map (source-map)
    ▼
回填 symbolicated_stack 到 events.props 或单独表
```

### Implementation Notes

- A standalone microservice (Python/Go) to avoid polluting the Java stack.
- mapping files stored by `game_id + platform + app_version` (object storage or local).
- Symbolication is asynchronous: after a crash is stored, a message is sent to the `oddsmaker.crash_symbolicate` topic, and the symbolication service consumes it and writes back.
- Provide an API for the game side to upload mapping files (`POST /api/symbols/upload`).

### Files Involved

- New repository `oddsmaker-symbolicator` (Python/Go)
- Add a `SymbolMapping` entity to the Control Service (mapping file metadata)
- Add `POST /api/symbols/upload` and `GET /api/symbols` APIs to the Control Service
- Flink `crash-enrich-job` (optional): consumes crash events and sends them to the symbolication topic

### Acceptance

- After the game side uploads an iOS dSYM, newly reported crashes show a symbolicated stack within 60s.
- The same crash reported multiple times is symbolicated only once (deduplicated by crash_hash).
- When the mapping file is missing, mark it `unsymbolicated` and do not block.

---

## 6. Prediction model training pipeline

> **Status (2026-09): Delivered**—the P6 explainable heuristics (pLTV D7→D30 multiplier method / ChurnScorer / RiskScorer) landed earlier; this batch adds the trainable pipeline `ml/` (the `oddsmaker-ml` Python package, numpy/pandas/scikit-learn): churn (label = no event within 14 days after the snapshot, LR), pltv (D7→D30 multiplier through-origin WLS, holdout cohort MAPE compared against an equal-weight ratio-mean baseline), risk (label = risk_actions block/review, class_weight=balanced LR). All are linearly explainable—artifacts are versioned JSON (feature_names + coefficients + intercept), so the Java side can score with a dot product + sigmoid, no Python runtime needed; each model artifact carries a built-in heuristic baseline comparison (auc_gain_vs_heuristic / holdout MAPE gains are auditable). Synthetic data runs offline in one command via `python3 -m oddsmaker_ml train --source synthetic`; the real-data entry point is ClickHouse HTTP (JSONEachRow, standard library urllib only). All four models are delivered (including propensity: label = a payment event within 14 days after the snapshot, same feature definition as churn, LR linearly explainable + `PropensityScorer` heuristic baseline, mirrored fallback with the same formula on the Java side, golden-locked on both ends). Write-back pipeline (P4.4 wrap-up): Control-side `MlArtifactRegistry` (`POST /api/ml-artifacts` registers artifacts, with validation semantics aligned to Python's `validate_artifact`, same-version retraining overwrites, and an audit entry written; `GET /api/ml-artifacts/game/{gameId}` lists versions / `active` gets the currently effective one) + `PredictionMetricsService` batch scoring (churn/risk take ClickHouse features by feature_names for a linear dot product + sigmoid; pltv is D7 revenue × artifact multiplier for immature users) writes back to predictions (TTL 30 days as before). When the artifact is present and validation passes, the model score takes precedence (`path=model`); when missing/corrupt/feature-definition-mismatched, it falls back to the heuristic (`path=heuristic`), and the two paths are distinguished by the response body's path and the stored `model_id/model_version`; when ClickHouse is not configured it degrades honestly to `available=false`. Training schedule (delivered): `MLModelService.scheduledMlRetrain()` (cron `oddsmaker.ml.retrain.*` configurable, default daily at 04:20, switch off by default) delegates to `MlRetrainScheduler`—it discovers games with registered `ml_model_artifacts` → `MlTrainingRunner` runs the `python3 -m oddsmaker_ml train --model all` subprocess (the data source prefers real ClickHouse data: CH is available and the HTTP url is derivable—an explicit `clickhouse-url` takes precedence, otherwise derived from the JDBC url; when unavailable it falls back to synthetic data to run through, per the `allow-synthetic` switch, and setting it false means SKIPPED with no synthetic artifact written) → artifacts are validated and registered one by one (same-version overwrite is idempotent) → the four batch scorings are triggered to write back to predictions. Honest degradation: training failure / illegal artifact / scoring exception only write an audit entry (FAILURE / PARTIAL / SKIPPED, actor=ml-retrain) and logs, without blocking existing pipelines, and a single game's failure does not block the others. GBDT accuracy upgrades / real-time scoring remain on-demand extension points.

### Goal

`MLModelService` already has a model management skeleton (registration/deployment/evaluation) but no actual training schedule. Add training pipelines for churn prediction / pLTV / risk.

### Design

```
Control Service: MLModelService
    │ 配置模型（类型、特征、目标）
    ▼
Training Job（定时调度，每日/每周）
    │ 1. 从 ClickHouse 抽特征（用户行为 + 资源 + 会话）
    │ 2. 训练（XGBoost / LightGBM / 简单 LR）
    │ 3. 评估（AUC / LogLoss / 校准度）
    │ 4. 注册到 MLModelService（版本化）
    ▼
Serving（Control Service 或独立服务）
    │ 按需打分：批量（每日跑一次，写回 ClickHouse predictions 表）
    ▼
predictions 表（user_id, model_id, score, predicted_at）
```

### Implementation Notes

- The training pipeline uses Python (scikit-learn / XGBoost), as a standalone job (like a Flink job but in Python).
- Feature engineering: extract features via SQL over the events table (7/30-day behavior aggregations).
- Model storage: simple files + version numbers (MLflow optional).
- Scoring: batch first (run once daily, write back to the ClickHouse `predictions` table); real-time scoring to be added later.
- Model types:
  - **churn** (churn prediction): target = no event for 14 days; features = `days_inactive_30d`, `session_count_30d`, `event_count_30d`, `revenue_total_30d`
  - **pltv** (pLTV): target = 30-day cumulative revenue; features = `v_ltv_by_cohort_day` + `v_user_first_seen`, D7→D30 multiplier WLS regression
  - **risk** (risk scoring): target = risk_actions block/review labels; features = 30-day hit counts by severity + distinct_rules_30d

### Files Involved

- The standalone Python package `oddsmaker-ml` inside the `ml/` directory
- Control Service: complete the training schedule and model serving API of `MLModelService`
- ClickHouse: add a `predictions` table
- Flink job (optional): real-time scoring, consuming events + looking up models + outputting predictions

### Acceptance

- Configure a churn model, train daily, AUC > 0.7, and AUC ≥ the heuristic baseline (auc_gain_vs_heuristic ≥ 0).
- After the model is registered with the Control Service, a user's churn probability can be queried via the API.
- The predictions table updates daily, covering all active users.
- The model artifact JSON (schema_version=1) contains `feature_names + coefficients + intercept`, and the Java side can score with a dot product + sigmoid, no Python runtime needed.
- holdout_metrics_trained["mape"] ≤ baseline["mape"] × 1.01 (the pltv model is no worse than the equal-weight ratio-mean baseline).

---

## Suggested Cadence (2026-09 update)

The original list 1/2/3/4/5/6 has all been delivered. The repo currently has on the books:

1. **P8 MMP attribution** (paused): conditional project setup (prerequisite: export permission for any MMP's raw data, either Data Locker / CSV uploads); it will not start until that is met, see the paused items in todo.md and `docs/mmp-attribution-evaluation.md`.
2. **Two open requirements in `todo.md`**: Debezium CDC dimension sync (scheduled after a real game side enables binlog and deploys Kafka Connect), ML anomaly detection into the evaluation batch (work starts once the trigger condition holds).
