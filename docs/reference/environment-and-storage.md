# Environment and Storage Profile Design

Oddsmaker's goal is not to build a multi-tenant SaaS that "looks sophisticated", but to provide **a single game company** with a genuinely operable multi-game analytics and risk control platform.

This means the model must satisfy three things at once:

- Multiple games can be managed within the same company
- The same game can go through release stages such as `dev / staging / prod`
- Data of different games can be isolated as needed (shared partitions → production isolation → dedicated backends)

> **Implementation status (2026-10)**: what is currently in place is shared ClickHouse tables, isolated by `(game_id, environment)` partitioning; "a separate database per game" is the target form, via the storage profile's `isolationStrategy`, and the physical database split is not yet wired up. The remaining sections of this document are written in terms of the target form; keep this note in mind when reading.

The design orientation is:

`Game -> Environment -> StorageProfile` (isolation strength is determined by the profile)

**Storage layout of the current implementation**:

```
PostgreSQL（元数据）
├─ games / environments / api_keys
├─ users / audit_logs / storage_profiles
└─ ...

ClickHouse（共享表）
├─ events / sessions / retention
├─ risk_events / risk_scores / risk_actions
└─ （全部按 (game_id, environment, 日期) 分区）
```

**Advantages of the target form (database per game)**:

- Physical isolation, with independent capacity planning and scaling
- Easier game migration and archival
- Queries can omit `WHERE game_id = ...` (current shared-table queries must carry the partition condition)

## Why Not Remove Environment

`environment`'s responsibility is to express the **release stage**, not the company, customer, or physical storage boundary.

Reasons to keep `environment`:

- The same game usually has different API Keys, sampling rates, PII policies, and risk thresholds under `dev/staging/prod`
- Risk rules are usually validated in non-production environments first, then promoted to production
- Test data and production data must be isolated, but the control plane should not have to replicate three sets of "fake games" for this
- SDKs, Gateway, Tracking Plan, experiments, and auditing naturally all need environment semantics

If `environment` were removed, things would usually end up degrading into:

- `game-a-dev`
- `game-a-staging`
- `game-a-prod`

The surface benefit of this design is simplicity, but the actual costs are:

- Duplicated control plane objects
- Duplicated rules and policies
- Duplicated API Key management
- Fragmented reporting and audit chains

## Evolution of the Storage Profile's Responsibilities

`storage_profile` is the only bridge between games/environments and the data plane:

- Describes the cluster configuration of Kafka / ClickHouse / Redis / archive buckets
- Expresses isolation strength via `isolationStrategy` (`SHARED` / `PROD_ISOLATED` / `DEDICATED`)—`SHARED` is the current default and the only wired-up form
- Handles cluster capacity planning and scaling

**storage_profile focuses on**:

- Kafka cluster configuration and topic namespace
- ClickHouse cluster configuration and database distribution
- Redis cluster configuration
- S3 Archive bucket configuration
- Cluster-level monitoring and alerting

## Core Model

### 1. Game

Business object.

Examples:

- `game_id = slot_royale`
- `game_id = poker_arena`

### 2. Environment

Logical release stage.

Recommended fixed enum:

- `dev`
- `qa`
- `staging`
- `prod`
- `loadtest`

Currently created by default:

- `dev`
- `staging`
- `prod`

### 3. StorageProfile

Data plane routing configuration.

It describes:

- Kafka cluster / topic namespace
- ClickHouse cluster / database routing
- Redis cluster
- Archive bucket
- Isolation strategy

## Recommended Strategies

Oddsmaker does not support unlimited custom isolation modes; instead it recommends a small number of controlled strategies.

### `SHARED`

All environments share the same set of backends.

Suitable for:

- Local development
- Demos
- Small-scale PoCs

Pros:

- Lowest cost
- Simplest deployment

Cons:

- Weak isolation between production and testing

### `PROD_ISOLATED`

`prod` uses a dedicated profile; `dev/qa/staging` share a non-production profile.

Suitable for:

- The default setup for normal production

Pros:

- Avoids test data polluting production
- Best balance of cost and isolation

Cons:

- One more set of backend resources than `SHARED`

### `DEDICATED`

A specific `game + environment` exclusively occupies a backend.

Suitable for:

- Top-tier games
- High-traffic production environments
- Strongly regulated or high-risk businesses

Pros:

- Strongest isolation
- Easy to scale independently

Cons:

- Highest cost and operational complexity

## Recommended Defaults

Oddsmaker recommends:

- Non-production: `shared-nonprod`
- Production: `shared-prod`
- Exceptionally large games: upgrade to `dedicated` as needed

In other words, the platform's recommended default is neither "share everything" nor "isolate everything", but:

**Production isolated, non-production shared.**

## Diagrams

### Logical Model

```mermaid
flowchart TB
  G[Game: slot_royale]
  E1[Environment: dev]
  E2[Environment: staging]
  E3[Environment: prod]
  S1[Storage Profile: shared-nonprod]
  S2[Storage Profile: shared-prod]

  G --> E1
  G --> E2
  G --> E3
  E1 --> S1
  E2 --> S1
  E3 --> S2
```

### Data Plane Mapping

```mermaid
flowchart LR
  subgraph Control Plane
    G1[Game]
    E[Environment]
    SP[Storage Profile]
    K[API Key / Policy / Risk Rule]
  end

  subgraph Data Plane
    KF[(Kafka)]
    CH[(ClickHouse)]
    R[(Redis)]
    A[(Archive)]
  end

  G1 --> E
  E --> SP
  E --> K
  SP --> KF
  SP --> CH
  SP --> R
  SP --> A
```

## Design Benefits

### For the Analytics Pipeline

- The event protocol keeps `game_id + environment`
- No need to expose the storage topology to SDKs
- ClickHouse / Kafka / Redis can evolve per profile

### For the Risk Control Pipeline

- Risk rules remain bound to `game + environment`
- Production environments can be stricter, non-production can be looser
- Production Redis risk state can be independent, avoiding test pollution

### For the Control Plane

- No need to replicate three sets of "fake projects"
- More natural policy inheritance: game-level defaults, environment-level overrides
- Dedicated profiles can be added later without breaking the upper-level resource model

## Rejected Alternatives

### Option 1: Remove environment

Reasons for rejection:

- It would stuff release-stage semantics into game/project names
- API Keys, rules, audits, and experiments would be duplicated heavily

### Option 2: A separate database by default for every `(game, env)`

Reasons for rejection:

- Operational cost is too high
- Too much duplication of analytics objects, DDL, reports, and monitoring
- Too heavy for a project in its early days

## Final Conclusion

Oddsmaker's recommended model is:

- `game_id` handles business isolation
- `environment` handles stage isolation
- `storage_profile` is the configuration slot for isolation strength (currently only `SHARED` is wired up)

This model is more realistic than "keeping only game", and more operable than "splitting a separate database for every environment".

## Event Delivery and Deduplication Semantics (audit DATA-001)

The end-to-end delivery semantics of Kafka `events_raw` → events-enrich-job → ClickHouse `events`
are **at-least-once** — the platform does not promise exactly-once; this section explicitly bounds the
residual duplication window.

### Logical event identity and the two deduplication passes

Logical event identity = `game_id + environment + event_id`, length-prefix encoded (length-delimited,
so a separator can never collide with a key). The same key is used in two places:

- Gateway `ReplayGuard` (local generation cache, entry idempotency) — the first pass, absorbing ingestion-end
  resends; the window is instance-local (shared Redis is a future hardening step);
- Enrich job `DedupFunction` (Flink keyed state, 7-day TTL) — the second pass, absorbing Kafka
  re-delivery and restart replay.

### Deduplication scope

Job-side deduplication only holds within the operator's **keyed state** scope. Keyed state is persisted
with checkpoints and survives a restart only if the checkpoint is recoverable; the repo has not
configured an external state backend or HA manager, so checkpoints land in the JobManager process.

### Residual duplication windows (not eliminated)

1. **Restart where the checkpoint is unrecoverable** (compose local-executor shape: in-process state
   backend, no HA) → dedup state is completely empty, and a re-delivered same-`event_id` event is
   inserted again;
2. **Checkpoint interval window** (residual under `CheckpointingMode.AT_LEAST_ONCE`) → the JDBC sink
   flushes without waiting for the checkpoint to complete; if the job crashes after the flush but
   before the checkpoint completes, recovery rewinds the offsets and re-reads this batch, while dedup
   state only returns to the last checkpoint (without this batch) → the same `event_id` is inserted
   again;
3. **Non-transactional sink** → JDBC batch write + retries (`maxRetries=3`) can replay already-applied
   rows under at-least-once semantics.

### Neither the storage layer nor the query layer converges duplicates

`events` is a plain `ENGINE = MergeTree` (ORDER BY includes `event_id`, but no `dedup_token`, not a
ReplacingMergeTree), and the analytical queries do not compensate with uniq/argMax on event_id —
**duplicate rows persist permanently**, and AggregatingMergeTree-style aggregation tables count them
twice.

### Current decision

This batch does not introduce downstream idempotent writes: ReplacingMergeTree + `dedup_token` would
require reworking all aggregation tables and read queries at once (the `dedup` modifier, aggregation
semantics changes), and is left as an independent later batch, not folded into this one. The current
boundary = the two upstream dedup passes + explicit residual-window declaration + observation, rather
than "duplicates eliminated".

### Observation semantics

Events judged duplicate inside the job are emitted to the `DedupFunction`'s `duplicate` side output, and that
side output **is** wired to the DLQ sink — both the `ValidateFunction`'s and the `DedupFunction`'s side
outputs go to the same sink (`EventsEnrichJob.java:159-160`, named `dlq-sink` and `dlq-sink-dedup`).
Duplicate events therefore reach the DLQ, and the `duplicates_enrich` counter (fed by `reason=duplicate`
records, aggregated by `DeadLetterConsumer`) is a direct reading of upstream dedup interception volume.
T-REV04 is closed.

### Verification surface

- `EventsEnrichJobTest.dedupScopeIsJobStateOnly`: behavioral pin — a fresh state instance re-delivering
  the same event_id goes to the sink a second time, turning "dedup only holds within state scope" into
  an executable assertion;
- `ClickhouseEventsDeliverySemanticsTest`: schema pin (the events table has no dedup token, and
  switching to a partition-dedup engine fails the test) + doc-reconciliation pin (the four key
  statements — at-least-once / keyed state / MergeTree / residual — failing the test if missing), so
  the residual risk cannot be quietly written off as eliminated in the docs.
