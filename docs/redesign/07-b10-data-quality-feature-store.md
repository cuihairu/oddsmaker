# B10 Final Design: Data Quality Metrics and Shared Feature Dual Write

This document is the final design of the B10 batch; the contract comes from §5.3 of the plan document and from the B10 row (`06-od2-restructure-plan`): the five Data Quality metrics + the direction of the Game Data Health page, the shared Feature dual-write schema, and no Data Lineage system. This batch has already landed together with B10 (8eba96c) (the V0.9.22 migration, DataQualityController, DataQualityReporter, DeadLetterConsumer, etc.); the line numbers below are design-draft numbering, and after landing the source code is authoritative. All signal points were verified by reading the code; the files and line numbers are given in each section, and the verification is based on the 2026-10-07 working copy.

## 1 Scope

- In scope: the definition of the five metrics' semantics, the schema and write path of the tables they are stored in, the feature_store dual-write table schema, and the data-fetching direction of the health page.
- Out of scope: the Data Lineage system (§6 only records the direction, which is the full amount the plan document asks for); the enrich late side output (the late semantics land at the gateway edge, see §3.5); historical backfill of the metrics (counted from the launch window); real Analytics-side consumption of feature_store (a later batch).

## 2 Current-State Signal Points

| Signal | Location | Current state |
|---|---|---|
| The 9 gateway rejection reasons: `invalid_schema` / `api_key_scope_mismatch` / `unknown_event` / `invalid_timestamp` / `pii_blocked` / `payload_too_large` / `trust_escalation` / `blocked` / `kafka_error` | `BatchController.java:124-255` | Determined per event, returned to the client in the batch response, not persisted |
| Gateway idempotent de-duplication (`replayGuard.consumeEventId`) | `BatchController.java:188-193` | Same-instance SDK retries are silently absorbed, counted in `resp.duplicates`, and do not enter Kafka |
| Sampling exclusion | `BatchController.java:215` | `OUTCOME_SAMPLED_OUT`, only enters the Inspector buffer |
| Server-side timestamp stamping | `BatchController.java:147-148, 420-422` | When `ts_server` is absent the gateway fills in `Instant.now()`; when the SDK provides it, that value is trusted |
| enrich second-pass de-duplication | `EventsEnrichJob.java:396, 417` | `DedupFunction` judges duplicate → deadletter (reason=`duplicate`) |
| enrich watermark | `EventsEnrichJob.java:66-67` | `BoundedOutOfOrderness(5min)`, no late side output |
| deadletter topic | `DlqPublisher` (`oddsmaker.deadletter`) | The consumer has landed (control `DeadLetterConsumer`): duplicate→`duplicates_enrich`, the 9 gateway reasons are skipped, unknown→`dlq_other` |
| Live Inspector | `EventInspectorBuffer` | In-memory ring buffer, 200 entries per scope, TTL 10 minutes, emptied on restart |

The root cause of the missing metrics is not that there are no signals, but that all the signals travel through one-off HTTP responses or process memory with nothing persisted anywhere — this gap has been closed by B10: gateway counter snapshots + a DLQ consumer writing `data_quality_metrics`. The Inspector remains a debugging surface, not a metric source, and this design does not reuse it.

> **Line-number note**: the line numbers in this section and in the §2 table are all based on the 2026-10-07 working copy, that is, the layout before B10 landed; after landing, the `BatchController` line numbers have drifted, so the symbol name is authoritative.

## 3 The Semantics of the Five Metrics

The statistical dimension is uniformly `(game_id, environment, 5-minute window)`. By default the numerator and denominator come from the gateway edge counters; the only exception is the enrich-layer numerator for duplicate (§3.4).

### 3.1 event_valid_rate

`accepted / received`.

- `received`: the number of batch requests that pass authentication and batch-level validation and enter per-event determination.
- `accepted`: the number published into `events_validated`; retries absorbed by gateway idempotent de-duplication count as accepted (consistent with the current code, where `BatchController.java:190-191` counts a duplicate into `resp.accepted` as well), while `duplicates_gateway` is also recorded separately for use by §3.4.

### 3.2 drop_rate

`rejected_total / received`, where `rejected_total` is the sum of the 9 rejection reasons in the §2 table, expanded by reason at read time. `kafka_error` is a platform failure rather than a client-integration problem; the health page must flag it separately and must not fold it into integration quality.

### 3.3 unknown_event_rate

`rejected_unknown_event / received`. A sub-item of drop_rate listed separately: during integration, event names that mismatch the Tracking Plan are the most common rejection source and need an independent threshold alert; mixing them into drop_rate would dilute it.

### 3.4 duplicate_rate

`(duplicates_gateway + duplicates_enrich) / received`.

- `duplicates_gateway`: same-instance retries absorbed by replayGuard (`BatchController.java:191`).
- `duplicates_enrich`: cross-instance / cross-replay duplicates intercepted by `DedupFunction` and arriving via deadletter (`EventsEnrichJob.java:417`); the signal enters the table through the DLQ consumer (§4.3).
- The denominator uniformly uses the gateway edge `received`, and both layers' numerators are folded onto the same denominator. The enrich-layer numerator lags the gateway window by one processing cycle; the health page aligns them by window when displaying, and does not pursue cross-layer real-time.

### 3.5 late_event_rate

`late / received`, `late := ts_server − ts_client > 5 minutes`. The threshold is aligned with the enrich watermark `BoundedOutOfOrderness(5min)` (`EventsEnrichJob.java:66-67`); both `ts_server` and `ts_client` are available at the gateway entry (§2).

- `invalid_timestamp` (rejected because the timestamp is illegal or exceeds a reasonable offset) is not counted as late — that is a validity issue, already covered in §3.2.
- No late side output is added in enrich: the gateway edge already has two timestamps, and a second definition of late would only produce two rates that do not reconcile. Late events below the watermark are currently written to CH as usual, and B10 does not change this path.

### 3.6 Identity

`received = accepted + rejected_total + sampled_out` (accepted includes `duplicates_gateway`). It is checked when control writes the table, and a broken identity is logged. When the counting path misses a branch, the identity surfaces the problem ahead of any threshold alert.

## 4 Storage Plan

### 4.1 Metrics Table (PG control database, the V0.9.22 migration has landed)

```sql
CREATE TABLE data_quality_metrics (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_sec INT NOT NULL DEFAULT 300,
    received BIGINT NOT NULL DEFAULT 0,
    accepted BIGINT NOT NULL DEFAULT 0,
    sampled_out BIGINT NOT NULL DEFAULT 0,
    rejected_schema BIGINT NOT NULL DEFAULT 0,
    rejected_unknown_event BIGINT NOT NULL DEFAULT 0,
    rejected_invalid_timestamp BIGINT NOT NULL DEFAULT 0,
    rejected_pii_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_payload_too_large BIGINT NOT NULL DEFAULT 0,
    rejected_trust_escalation BIGINT NOT NULL DEFAULT 0,
    rejected_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_scope_mismatch BIGINT NOT NULL DEFAULT 0,
    rejected_kafka_error BIGINT NOT NULL DEFAULT 0,
    duplicates_gateway BIGINT NOT NULL DEFAULT 0,
    duplicates_enrich BIGINT NOT NULL DEFAULT 0,
    late BIGINT NOT NULL DEFAULT 0,
    dlq_other BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_data_quality_window UNIQUE (game_id, environment, window_start, window_sec)
);
CREATE INDEX idx_data_quality_recent
    ON data_quality_metrics (game_id, environment, window_start DESC);
```

Two trade-offs: the nine rejection reasons each occupy a column instead of building a reason dimension table — the set of reasons is closed by a code enum (defined in one place, `BatchController`), and a join against a dimension table buys no openness; rates are not stored as columns but computed at read time — storing the numerator and denominator in the same row and persisting the rate would inevitably produce a second source of truth inconsistent with the counts.

### 4.2 Gateway-Side Write Path

The gateway process maintains counters keyed by `(game_id, environment, window)`, and every 60 seconds an `@Scheduled` snapshot is POSTed to control `/internal/data-quality` (internal-token auth, using the same channel pattern as `InternalBlockListController`; the precedent for a gateway-side call is `BlockListClient`). Each snapshot carries cumulative values since the start of the window; control overwrites **the gateway-owned columns** of that key according to `uq_data_quality_window`, so out-of-order and duplicate deliveries are harmless and no incremental merge is performed.

**Column ownership** (implementation finalization): the two columns `duplicates_enrich` / `dlq_other` belong to the DLQ consumer (incrementally merged per §4.3); gateway snapshots do not carry them and preserve the existing values on overwrite — otherwise the 60-second snapshot would wipe out the enrich-side increments. The remaining columns are gateway-owned.

A gateway restart loses the in-memory counts of the current window: with 60-second snapshots, at most part of one window's counts is lost. Metrics are an observability surface, not a billing surface — accepted.

### 4.3 enrich-Layer duplicate Write Path

control-service adds a lightweight Kafka consumer subscribing to `oddsmaker.deadletter` (`DeadLetterConsumer`): records with reason=`duplicate` accumulate `duplicates_enrich` by window according to `game_id`/`environment`/event time; **the 9 gateway rejection reasons are skipped** — the same message has already been counted into the corresponding `rejected_*` column inside `reject()` (DLQ publishing and counting are a dual write at the same point), and counting it again in the consumer would double-count; only the remaining unknown reasons (future third-party DLQ producers) accumulate into `dlq_other` as a catch-all. This simultaneously closes the gap that deadletter has had no consumer since launch. The row rate is on the order of events per second, so single-partition ordered consumption suffices and no consumer-group concurrency is introduced. A missing scope (raw has no game_id/environment) lands in the `unknown` bucket for the current window — the observability surface does not drop counts.

### 4.4 Retention Policy

Keep 5-minute granularity for 30 days, with a scheduled cleanup configured in the implementation batch. No rollup: the acceptance criterion is "the metrics table is queryable and the health page has data to show", and the row count of 30 days × 5 minutes × (game × environment) is directly queryable in PG; if a cross-quarter look-back requirement appears, daily rollups can be added then — adding them now is guessing at requirements.

### 4.5 Direction of the Game Data Health Page

A new web page, with control providing two read-only endpoints: the window series (the five rates + the identity remainder) and the Top rejection reasons. The data surface is only the single table of §4.1, with no second data source; the page layout is left to the implementation batch.

## 5 Finalized Schema for the feature_store Summary Table

The original contract (`06-od2-restructure-plan` §5.3): "sharing happens via dual write at the output of the risk-job feature job: one copy into `risk_features`, one copy into the shared `feature_store` summary table". The following is the schema promised by B10:

```sql
-- B10 共享 Feature 摘要表（计划书 §5.3）：risk-job 特征作业与 risk_features 同源双写。
-- features 为该 (scope, 窗口) 全部特征值的扁平映射；
-- Analytics 侧取数不再对 risk_features 做 pivot。scope_key 编码沿用 risk_features 约定
-- （PLAYER:<user_id> / DEVICE:<device_id> / IP:<client_ip>）。

CREATE TABLE feature_store (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    features TEXT NOT NULL,
    as_of TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_feature_store_window UNIQUE (game_id, environment, scope_key, window_start, window_end)
);

CREATE INDEX idx_feature_store_scope
    ON feature_store (game_id, environment, scope_key, window_end DESC);
```

`features` is a flat JSON string (a TEXT column; the actual situation is described below) of the form `{"events_5m": 42.0, "fail_ratio_5m": 0.17}` — a pivot collapse corresponding one-to-one with the rows in `risk_features` for that scope and the same window. Keys within a row use bare feature names: the `(scope, window_end)` unique key already guarantees that windows do not clobber each other, so a conflict on the same key within a row means the overwrite semantics of a late recomputation (see below). The row count is on the same order as risk_features (one row per scope per window), not exceeding the original table.

**Finalized implementation deviation (4cc8c29)**: `features` uses TEXT rather than JSONB — control only stores and retrieves the raw string and queries go through the `(game_id, environment, scope_key, window_end)` index; this batch has no need for json operators; when a json query need appears, `ALTER COLUMN features TYPE jsonb USING features::jsonb` upgrades it in place, and TEXT→JSONB is lossless.

Dual-write mechanism (implementation finalization 4cc8c29): the risk-job persistence point is `RiskJob.featureJdbcSink` (`SinkFunction<FeatureRow>` JDBC upsert); the dual write attaches a second sink `featureStoreJdbcSink` in parallel to the `featureRows` stream (connection parameters and batch parameters the same as `featureJdbcSink`). FeatureRow is in long format (one row per feature), and the `feature_store` side relies on jsonb merging at the SQL layer to consolidate rows: `ON CONFLICT ... DO UPDATE SET features = (features::jsonb || EXCLUDED.features::jsonb)::text` — long-format rows each carry a single-feature JSON fragment, and on conflict the same key takes the right-hand value (late-recomputation overwrite), with `as_of` refreshed accordingly; the merge semantics were verified empirically by inserting the same key three times into a throwaway postgres:16 container. Being in the same database, no distributed transaction is needed; a `feature_store` write failure is treated exactly the same as a `risk_features` write failure (the sink throws, the job replays), and there is no split semantics of "main table succeeds / summary table fails".

Consumers: the implementation batch adds a read-only endpoint in control (fetch the most recent window row by scope). Real Analytics consumption belongs to a later batch; delivering the table and the write in this batch already satisfies "it does not occupy the B5/B6 critical path". The retention policy stays consistent with risk_features (currently neither has a TTL, and neither is unilaterally given one).

## 6 Data Lineage: Direction Only

The plan document requires no Lineage system, and this section is the full delivery — the metric source chain is consistent with `04-redesign §5`:

| Data | Produced by | Storage | Consumed by |
|---|---|---|---|
| The five quality-metric counts | Gateway edge determination + enrich de-duplication/DLQ | PG `data_quality_metrics` | Game Data Health page |
| Risk features | risk-job feature job | PG `risk_features` | FEATURE rule evaluation |
| Shared feature summary | risk-job dual write from the same source | PG `feature_store` | control read-only endpoint (Analytics later) |
| Duplicate events | Gateway replayGuard / enrich DedupFunction | Counts + deadletter | The §4.3 consumer |

## 7 Acceptance Cross-Reference and Implementation Batch Checklist

> **Landing note**: every item in the right-hand column "code to be written by the implementation batch" has landed together with B10 (8eba96c) (the migration, the gateway counters and snapshot reporting, the `/internal/data-quality` endpoint, the DLQ consumer, the web health page, the feature_store dual write); this section preserves the design-draft correspondence, and after landing the source code is authoritative.

The correspondence of the three B10 acceptance items in todo:

| Acceptance | Design landing point | Code to be written by the implementation batch |
|---|---|---|
| The metric table is persisted and queryable | The §4.1 table + the §4.2/§4.3 write paths | Migration file; gateway counters and snapshot reporting; the control `/internal/data-quality` endpoint; the DLQ consumer |
| The health page has data to show | §4.5 | A new web page + two read-only query endpoints in control |
| The feature_store table-creation migration and the dual-write synthesizer test | §5 | Migration file; `featureJdbcSink` extended for dual write; synthesizer test; no Lineage code |

---

The signal-point line numbers were verified against the 2026-10-07 working copy (`BatchController.java`, `EventInspectorBuffer.java`, `EventsEnrichJob.java`, `RiskJob.java`, `DlqPublisher.java`); the style of the schema draft is aligned with `V0.9.18__risk_features.sql`. When this design draft was written, no code or migration was changed; the implementation has landed together with B10 (8eba96c), and the source code is authoritative.
