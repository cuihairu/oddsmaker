# 06 - Oddsmaker 2.0 Restructure Plan (Code Agent Executable Edition)

> **Role**: this document converges 04-redesign (the new architecture) and 05-roadmap (the implementation roadmap) into a single **contract that can be handed directly to a Code Agent for batch-by-batch execution**:
> The domain model, module boundaries, and data contracts are fixed once and for all; afterwards each batch is executed in turn per the batches in §8, each batch carrying acceptance criteria and gates.
> Source materials: this document + `04-redesign.md` + `05-roadmap.md` + `todo.md` in the repo root (80/80 all checked off = the state at that time).
> Compiled on: 2026-10-04. Wording and numbers concerning old assessment files (`oddsmaker_completion_assessment.md` etc.) **are to be treated as archived snapshots and do not describe the current state**.

---

## 0. Execution Conventions (Requirements for the Code Agent)

1. **Gates**: before any commit that contains code, the full `services/` test suite + the `web` build must be all green; tagging/releasing/force push are forbidden; fix and push small problems autonomously.
2. **Schema source of truth**: to add a field or a table, only add a new migration with a higher version prefix under `services/control-service/src/main/resources/db/migration/` (e.g. `V0.9.16__...sql`) and align the entity; **editing already-released migrations is forbidden**.
3. **Blind spot of the test profile**: the H2 test profile (create-drop + Flyway off) cannot see migration constraints — any change involving NOT NULL/DEFAULT/UNIQUE/type drift must be verified against a real schema: `docker run -d --name oddsmaker-repro-pg -e POSTGRES_USER=oddsmaker -e POSTGRES_PASSWORD=oddsmaker -e POSTGRES_DB=oddsmaker -p 15433:5432 postgres:16-alpine`, then `DB_HOST=127.0.0.1 DB_PORT=15433 ./gradlew :services:control-service:bootRun --args='--logging.level.org.hibernate.SQL=DEBUG --logging.level.org.hibernate.orm.jdbc.bind=TRACE'`, use curl to probe the actual SQL bind and locate the field that hits the constraint, and delete the container when done (do not touch the demo deployment itself).
4. **After each batch completes**: write back to `todo.md` in the repo root (check off completed items / add new entries), and mark `[x]` at the beginning of the corresponding batch row in this document.
5. **§2–§7 of this document are the contract**: where they disagree with the current state, this document wins; if during execution this document conflicts with reality, first record `[OPEN]` and do not change the contract.

---

## 1. Product Positioning (P0, Zero Code First)

**One-sentence positioning (unified wording across the whole repo)**:

> Single-company, multi-game, multi-environment **Game Intelligence Platform** with built-in Analytics, Experimentation and Risk.
> A game-intelligence data platform for single-company deployment: real-time analytics + experimentation platform + built-in risk control.

Constraints:

- The target users are **self-built / on-premises deployments for a single game company**; no SaaS multi-tenancy (`04-redesign §1` design principles).
- Forbidden concept names: `Project`, `Workspace`, `Application`, `Tenant`, `Organization` (when used with multi-tenant semantics) — uniformly `Game`. `project_id` exists only in the Gateway compatibility layer (see §3.3).
- Old assessment numbers (80% completion, SDK coverage 90/85/80/60%, the "12-week plan") must not serve as the basis for any schedule or for the current state.

**Execution items (B1)**:

| Location | Current state | Change |
|---|---|---|
| GitHub repository description | "lightweight game analytics toolkit that turns gameplay events into actionable insights…" (conflicts with the README positioning) | Change it to the one-sentence positioning above |
| `oddsmaker_completion_assessment.md` (repo root) | No "historical snapshot" banner; still says "PIT…管理项目/API密钥/实验" | Add the same banner as line 3 of `oddsmaker_technical_recommendations.md` (archived at the end of 2026-10, pointing to todo.md); change the Project wording in the body to the Game wording |
| `docs/zh/redesign/index.md` | The document structure table only goes up to 05 | Add the 06 row |

---

## 2. Domain Model (Fixed Once and for All)

There are 13 first-class objects. For each: identity / ownership boundary / lifecycle / key invariants / current landing point / 2.0 action.

### 2.1 Core Identity (Converged, Only Needs Hardening)

| Object | Identity | Ownership | Key invariants | Current state | 2.0 action |
|---|---|---|---|---|---|
| **Game** | `game_id` (currently persisted as `VARCHAR(32)`; lowercase `[a-z0-9_]+` is a design expectation, not enforced) | Globally unique, **immutable** after creation | Exactly one Game exists per game_id on the platform (including the SUNSET state) | `GameEntity` (jpa package) | No structural action; `[a-z0-9_]+` validation is a design expectation (not enforced; V0.2.0 has only a length constraint) |
| **Environment** | `environment`: the five standard values `dev/qa/staging/prod/loadtest` | `(game_id, environment)` is globally unique | Environment names do not repeat within the same game; an environment must attach one `storageProfileId` | `GameEnvironmentEntity` (contains the direct physical fields `dataNamespace`/`kafkaTopicPrefix`/`databaseName`) | **Converge the physical fields**: direct-connection fields such as `kafkaTopicPrefix`/`clickhouse...` move to StorageProfile (see 2.2); Environment keeps only `storageProfileId` plus the logical `dataNamespace` |
| **StorageProfile** | `storage_profile_id` | Global | The business/event layers **do not hold** the profile's internal physical details; `isolationStrategy ∈ {SHARED, DEDICATED}` | `StorageProfileEntity` (IsolationStrategy.SHARED already implemented) | Becomes the **single entry point** for physical routing of environments; add a single resolver that resolves the kafka topic / CH database name / archive bucket from a profile |

### 2.2 Gateway and Keys

| Object | Identity | Key invariants | Current state | 2.0 action |
|---|---|---|---|---|
| **ApiKey (APIKey)** | `api_key` (public id) + `secret` | `keyType ∈ {CLIENT, SERVER}`; **CLIENT has no secret and no HMAC; SERVER holds the secret and is the only holder of HMAC** | `ApiKeyEntity` (`ApiKeyType.CLIENT/SERVER`, propsAllowlist, piiEmail/piiPhone, rpm limit) already in place | The Server SDK (§4.3) consumes SERVER-type keys; add issuance validation for SERVER-type keys: creation is forbidden when the game has not enabled the server event capability |

### 2.3 Data Contract Domain (Events and Schema)

| Object | Identity | Key invariants | Current state | 2.0 action |
|---|---|---|---|---|
| **Event** | The `event_id`+`event_name`+`ts_client` triple; the contract primary key is `game_id+environment+event_id` (see index.md) | An event is **not a control-plane resource** (it is a stream object), but the contract fixes it; see §4 | `schema/json/oddsmaker-event-schema.json` (v1, about 50 properties, no version/source/trust fields) | Incrementally raise it to v2 per §4: `event_version`, `source`, `trust_level`, `event_origin` |
| **EventSchema** | `(game_id, event_name, major_version)` | Only one ACTIVE version is allowed per event_name; the version compatibility policy is specified by the schema | `TrackingPlanEntity` + `EventDefinitionEntity`/`EventPropertyDefinitionEntity` already have an embryonic form (strictness/rejectUnknownEvents/enableAutoValidation) | ✅ (B7 landed: tracking_plans gains the five columns compatibility(NONE/BACKWARD/FORWARD/FULL)/pii_policy/retention_days/sampling_rate/owner_id (V0.9.20); a publish compatibility gate — when compatibility≠NONE, diff the event set against the most recent ACTIVE baseline of the same game+env (a BACKWARD violation = non-empty removed, a FORWARD violation = non-empty added, changed is an informational item only); incompatible publishes are rejected and stay DRAFT; `/api/games/{gameId}/schemas` resource API (publish/compatibility/deactivate sub-resources, on the same substrate as tracking-plans) + web `/schemas` page) |

### 2.4 Risk Domain (Six-Layer Abstraction, See §5)

| Object | Identity | Current state | 2.0 action |
|---|---|---|---|
| **Signal** | A stream, not an entity | Raw events after Gateway validation are signals (the `risk_context` field is already in the event contract) | Explicitly define the signal list (gold_gain/login_ip/device/…); no new table is needed — **landed (2026-10-15)**: an explicit list on both sides in `RiskSignals` (9 signals: gold_gain/login_ip/device/win_flag/event_frequency/receipt/ad_reward/revenue/trust_level, snake_case); only explicit declarations and documentation are added, the extraction logic (`FeatureCalc`/`RiskJob`) and the serialization format stay unchanged; there is no shared dependency module on either side, and each side's tests pin the same documented list for alignment |
| **Feature** (**new**) | The time-windowed value of `(game_id, scope_key, feature_name)` | ✅ (B5 landed: the `risk_features` table + a sliding window over the first 6 features in the risk-job feature job) | Shared `feature_store` dual write on the ANALYTICS side, see §5.3 (B10) |
| **RiskRule** | `rule_id`, `environmentId` nullable (global rule) | `RiskRuleEntity` already exists (THRESHOLD/SEQUENCE, riskScore, actionType, enableAutoBlock) | Converge `ruleConditions` into a structured JSON schema (referencing EventSchema fields), keeping the current field names — **landed (2026-10-15)**: a typed view in `dto/RuleConditions` (typed on read + a single validation entry point on write, explicit errors in strict mode / `parsed=false` in tolerant mode to stay compatible with existing data); structural validation on the FEATURE write path tightened (per-item checks on scope/feature/op/value), the existing 2-8 constraint of PATTERN and the "any string" stance of THRESHOLD unchanged; the `rule_conditions` column is not rewritten and the external exposure surface is unchanged; "referencing EventSchema fields" **has been decided and landed as a delegated decision (2026-10-16, FW1016D, see docs/决策记录.md)**: the legal value domain of feature names = the computed-side feature registry of the risk-job (`RiskFeatures` list on both sides, 6 features), unknown names are explicitly rejected on the write path; no EventSchema cross-validation is done — a feature name is a "signal × window" derived quantity, not an event contract field |
| **RiskScore** | `(game_id, subject, as_of)` value object | ✅ (B6 landed: risk-job evaluation produces CH `risk_scores` — the per-subject accumulated maximum contribution score of each rule plus rule-hit detail JSON, a ReplacingMergeTree subject snapshot) | **Split out of rule**: one evaluation produces one accumulated score record (B6) |
| **Decision** (**new**) | The adjudication `(case_id → status)` | ✅ (B6 landed: `RiskCaseEntity.status` is a layered forward-only state machine, Decision-first — the adjudication lands first, illegal transitions refuse to execute the action) | B6: adjudication state machine `OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED` |
| **Action** | The disposition action | `RiskRuleEntity.ActionType` (IGNORE/ALERT/LOG_ONLY/CHALLENGE/THROTTLE/BLOCK/REVIEW/WEBHOOK already implemented) + BlockList/ban | Bound to the Decision (the Decision determines the action, not the reverse) |

### 2.5 Experiment Domain

| Object | Identity | Current state | 2.0 action |
|---|---|---|---|
| **Experiment** | `(game_id, environment_id, name)` | ✅ (**B8 landed, V0.9.21**): `ExperimentEntity` is now field-based (Audience/Guardrail/Decision + status enum-ized), and `ExperimentSplitter` (FNV-1a bucketing) + `ExperimentMetricsAggregator` (ClickHouse daily attribution, idempotent backfill of `experiment_metric_snapshots`: exposure_users/events_count/revenue) + `ExperimentStatsService` are retained | B8 fills in the genuinely missing pieces: Audience (Segment reference), Guardrail, Decision (status enum-ized), making Variant explicit, promoting Allocation out of configJson; **Splitter and Aggregator are untouched** |

### 2.6 Users and Permissions

| Object | Identity | Current state | 2.0 action |
|---|---|---|---|
| **User/Permission** | User + role | **B2 (V0.9.16) landed**: `user_role_assignments` is the single source of truth and `UserService` is the only write path (`createUser` UserService.java:94-97, `updateRoles` UserService.java:261; users.roles is demoted to a display projection); the manual psql section in `deploy/demo/README.md §5` has been deleted | **B2 converged to a single source of truth**: `user_role_assignments`; `users.roles` demoted to a display/compatibility column (read-heavy, write-light); add a one-off backfill migration + delete the manual psql section in `deploy/demo/README.md §5` |

Audit: `AuditLogEntity` (V0.2.4) is already the only audit-trail channel; 2.0 adds event-category constants (RISK_POLICY_CHANGED / API_KEY_ROTATED / DATA_EXPORTED / PERMISSION_CHANGED / EXPERIMENT_PUBLISHED) with no new table — **landed (2026-10-15)**: the `AuditEventType` enum (five categories + OTHER; `AuditLogEntity.getEventType()` is derived on read, with adjudication = resource × action × details.operation); metadata from resource-first `logCreate`/`logUpdate` lands in details (gameId/environment promoted to columns); from now on a rotation row (the 68ce564 convention) can directly derive API_KEY_ROTATED.

---

## 3. Module Boundaries

### 3.1 Module Inventory (Target State)

| Module | Responsibility | Current state |
|---|---|---|
| `services/gateway-service` | The single ingestion entry point: validation/Auth/rate limiting/PII/risk gate/event dispatch | ✅ |
| `services/control-service` | The 7 control-plane resource groups (see 3.2) | ✅ |
| `jobs/flink/*` (7 jobs) | State computation: enrich / identity-merge / sessions / retention / funnels / risk / dimension-sync | ✅ |
| `sdks/{web,android,ios,unity}` | Client-side collection (documented boundary: no secret, no HMAC) | ✅ |
| **`sdks/server` (new)** | **A first-class citizen**: server-side trusted events + HMAC (B3) | ✅ (B3 landed: a zero-dependency Java module, Memory→Disk Queue→Batch→Gzip→HMAC) |
| `web/` | Console SPA | ✅ |
| `deploy/` + `docker/` | Composition matrix (§7) | ✅ |

### 3.2 Control-Plane Resource Groups (grouping inside control-service)

```
games        → Game / Environment / StorageProfile
keys         → APIKey（含 Server Key 发放）
schemas      → EventSchema / TrackingPlan（过渡期并轨）
experiments  → Experiment / Variant / Exposure / Metric / Guardrail（B8 后）
risk         → RiskRule / Feature(B5) / RiskCase / ReviewQueue / BlockList
data         → Dashboard / Segment / Report / Cohort / Export / Funnel / Retention / Identity / PlayerData
system       → User / Role / AuditLog / Webhook / RateLimit / Security / FlinkJob / ML / Integration
```

The current controllers are scattered (66 controllers in the `api/` package); **2.0 only documents the grouping, it does not move files** — package-level renaming is a "deferrable" item outside B10, to avoid a huge diff in a single review.

### 3.3 Dependency Discipline (Crossing Counts as a Defect)

1. **The business layer does not touch physical routing**: the topic/CH database name/archive bucket may only be obtained through the StorageProfile resolver (§2.1).
2. **Events enter only through the Gateway**: SDKs, Flink, and any module must not write to the Kafka/ClickHouse event tables directly (except analytical queries on the control side, which go through the read-only ClickHouseClient interface).
3. **The `project_id` compatibility layer is kept**: todo.md has already been checked off with `[x]` (old fields are mapped to game_id, purely legacy fields are rejected as invalid_schema). This plan **does not delete the compatibility layer** (deleting it would break v0 SDK compatibility); it only forbids `project_id` outside the compatibility layer.
4. **SDK contract**: the client only sends events and only holds `api_key`; the SEREVR SDK may send trusted events + HMAC; neither may embed storage/routing knowledge.

---

## 4. Data Contracts

### 4.1 Event v1 (Current State, Immutable Base)

`schema/json/oddsmaker-event-schema.json`: `$id` anchoring, about 50 top-level properties, a `props` free-extension area, `required` covering `event_id/event_name/ts_client/game_id/environment` and so on. The Gateway's `JsonSchemaValidator` + `PropsPolicy` enforce validation; events carrying deprecated fields are rejected as `invalid_schema`.

### 4.2 Event v2 Increment (B4)

On top of v1, **only add fields, never modify or remove**:

| New field | Type | Semantics | Validation |
|---|---|---|---|
| `event_version` | integer | The major version of the event contract (EventSchema) | ≥1; defaults to 1 |
| `source` | enum | `client \| server \| system \| derived` | Required (filled automatically by the server SDK and by the gateway internals) |
| `trust_level` | enum | `LOW \| HIGH \| COMPUTED` (client→LOW; server/system→HIGH; derived→COMPUTED) | Derived from source; the client cannot self-elevate |
| `event_origin` | string | SDK name+version (e.g. `server-java/0.1.0`), used for audit back-tracing | Optional, the gateway fills in the default |

### 4.3 Server Trusted Event Convention (the contract B3 writes in sync)

- Server event naming convention: `server.<domain>.<action>` (e.g. `server.purchase.confirmed`), semantically distinct from the client-side `client.`; **top-up/economy settlement only recognizes server events** (only `trust_level=HIGH` events can drive the BLOCK-level action of a risk decision).
- The client event and the server event for the same business fact are **not merged and de-duplicated**; they are retained separately by `source`, and risk/settlement consumers explicitly declare which one they depend on.

### 4.4 Three Schema Modes (Reusing the TrackingPlan Embryo)

The three values of `ValidationStrictness`: `OFF` (validation off) / `WARN` (warn only, no rejection) / `STRICT` (reject non-conforming events, the default). Mode priority: `environment-level TrackingPlan > ApiKey.propsAllowlist > gateway default`. The gateway and the schema files (`schema/json/*.schema.json`) are kept in sync as they are shipped.

### 4.5 Control API Contract

A unified `ApiResponse` envelope + global exception mapping (400 IllegalArgument / 401 unauthenticated / 403 denied by AccessGuard / 404 not found / 409 uniqueness conflict / 500 fallback) — already in place as-is, and every v2 increment reuses it; the endpoints of the new resource group are named `/api/schemas`, `/api/features`, `/api/risk/decisions` in the same style as the existing `/api/games|environments|experiments|risk-rules`. (**Landing note**: what actually landed uses the game-scoped prefix `/api/games/{gameId}/...`, e.g. SchemasController `/api/games/{gameId}/schemas`, feature-store `/api/games/{gameId}/{environment}/feature-store/...`; the flat naming in this sentence was only an early conception of this section.)

---

## 5. Six-Layer Risk Abstraction (P0 Focus, B5/B6 Landed)

```
Signal → Feature → Rule → Risk Score → Decision → Action
```

Current-state mapping (verified):

- **Signal** ✅: post-validation Gateway events + `risk_context`; the risk-job `RuleConfig` is indexed by ruleType, and among multiple entries of the same type the highest riskScore wins (`jobs/flink/risk-job/.../RuleConfig.java:13`).
- **Feature** ✅ (B5 landed): the risk-job feature job upserts `risk_features` over a sliding window for the first 6 features; `FEATURE` rules read `ruleConditions.features` from the feature snapshot (the shared `feature_store` dual write is left to B10, §5.3).
- **Rule** ✅: `RiskRuleEntity` (THRESHOLD/SEQUENCE, riskLevel, actionType, enableAutoBlock/blockDuration).
- **Risk Score** ✅ (B6 landed): on the risk-job evaluation side, `RiskScoreFunction` accumulates per subject the maximum contribution score of each rule (the Rule's static riskScore is demoted to that rule's maximum contribution score; repeated hits of the same rule take the higher value rather than stacking); each evaluation writes a CH `risk_scores` row (subject accumulated score + rule-hit detail JSON); nothing is written back on the disposition-action side (control is the single writer of `risk_actions`).
- **Decision** ✅ (B6 landed): `RiskCaseEntity.status` is a layered forward-only state machine (`OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED`, strictly ascending layers + OPEN cannot jump straight to RESOLVED); `RiskEventConsumer` is Decision-first — the adjudication lands the status first, and action execution only happens after the Decision; illegal transitions refuse to execute and are archived as `decision_rejected`; BLOCK-level actions require an input `trust_level=HIGH` (a dual gate with the same semantics on both the risk-job and control sides).
- **Action** ✅: ActionType + BlockList/ban + Webhook alerting is fully connected.

### 5.1 B5: Feature Layer (risk first)

- New table `risk_features` (`game_id, environment, scope_key(identity), feature_name, window_start, window_end, value, as_of`) + `RiskFeatureEntity` + repository.
- Add a feature-job branch in `jobs/flink/risk-job`: first-batch features such as gold_gain_1h/24h, device_count, account_count_per_ip, win_rate (**the feature list is authoritative per this document, the first 6**).
- The Rule's `ruleConditions` no longer reads directly from events; it reads from features instead (event → feature → rule, three-stage decoupling).

### 5.2 B6: Score and Decision

- Evaluation is triggered along with events and produces `risk_scores` rows (subject, accumulated score, rule-hit detail JSON); the Rule's static riskScore becomes "the maximum contribution score of that rule".
- The adjudication state machine `RiskCaseEntity.status`: `OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED` (the legal-transition table goes into the tests).
- Action execution only happens after the Decision; BLOCK-level actions require a `trust_level=HIGH` input (depends on 4.3; B6 acceptance covers this item).

### 5.3 Shared Feature Layer (P1, B10 Optional)

Analytics DAU/retention/ARPU and risk features share the same source (player behavior features), and B5's `risk_features` table does not simulate sharing — sharing happens via **dual write at the output of the risk-job feature job**: one copy into `risk_features`, one copy into the shared `feature_store` summary table (B10 defines the schema; it does not occupy the B5/B6 critical path).

### 5.4 Acceptance (shared by B5/B6)

- The full test suite is green; add `RiskFeature*Test` (feature computation, window sliding, rules referencing features) and `RiskDecisionStateMachineTest` (at least 3 each of legal/illegal state-machine transitions).
- Bash-level end-to-end (real-PG reproduction method): feed in 2 above-threshold "gold gain" events → a 1h window row appears in `risk_features` → the rule triggers → a row lands in `risk_scores` → Decision=REVIEW → Action=ALERT.

---

## 6. Formalizing the Experiment Platform (B8)

Current state (verified; the assessment underestimated this area): `ExperimentSplitter` (FNV-1a/weighted/salt bucketing, the `experiments` config constrained by the `experiment-config.schema.json` JSON Schema), `ExperimentMetricsAggregator` (ClickHouse daily-window attribution, idempotent backfill of `experiment_metric_snapshots`, already including exposure_users de-duplication and experiments map attribution as the basis for SRM sample size), `ExperimentStatsService` and `/api/experiments/{id}/results`; the only shortcoming is the **control-plane resource form** — `ExperimentEntity` is still a bare 7-field table (status is a free-form string), with Audience/Guardrail/Decision all missing. (**Status update**: B8 has landed, V0.9.21 — Audience/Guardrail/Decision are now field-based and status is enum-ized, so this shortcoming is closed.)

Target field-ization (**the Splitter and Aggregator algorithms are untouched**):

| Object | Current state | 2.0 form |
|---|---|---|
| Audience | Missing | Targeting conditions (reusing the Segment concept, referenced by `audience_segment_id`) |
| Allocation / Variant | Hidden in configJson | Promote an explicit `variants[]` (name/weight/salt key) into columns; `variant_id` is generated when the experiment is created |
| Exposure | ✅ the `experiment_exposure` event already enters ClickHouse attribution | Already listed in the platform event convention of `04-redesign §4.1`; the exposure column is queryable through `/api/experiments/{id}/results` |
| Metric | ✅ `experiment_metric_snapshots` has been backfilled | Guardrail boolean flags (inverse metrics crash/refund/complaint; breaching them raises an alert) |
| Decision | Missing (status is a free-form string) | `status ∈ {DRAFT, LIVE, PAUSED, ENDED}` enum-ized + a publish action |

Acceptance: ExperimentController gains Guardrail/publish actions and status enum-ization; `experiment-config.schema.json` is upgraded in sync and keeps old configJson forward-compatible; all existing Splitter-related tests stay green without modification.

---

## 7. Runtime Mode Matrix (B9)

The three-tier orchestration already exists today: `deploy/demo` (no Kafka, control designed degrades to available=false), the root `docker-compose.quickstart.yml` (Kafka+Apicurio+topic-init), `infra/docker-compose.yml` (the full set). **B9 does not change the orchestration; it produces a declarative matrix**:

| Mode | Components | Benefit/cost |
|---|---|---|
| Lite | gateway → control (the in-memory queue writes straight through, ClickHouse optional) | Runs on a 1C dev machine; real-time analytics is degraded |
| Standard | + Kafka + ClickHouse + redis + apicurio | The single-machine default tier (≈ the current quickstart) |
| Production | + all Flink jobs + Superset/Grafana/Prometheus + object storage archiving | Not suitable for a 1.6GB machine (the current deploy/demo has already been downgraded to a lighter tier for this reason) |

The matrix lands in `MODES.md` under `deploy/`, and the demo orchestration notes "this tier = Lite".

> **✅ (B9 landed)** `deploy/MODES.md` is in place; the four orchestration headers are marked: demo=Lite, quickstart=Standard,
> root compose=the Production business stack, infra=the Production observability/middleware surface. One reconciliation correction against the table above:
> infra is not a single "full set" tier — it is the subset of middleware + observability with no postgres/redis/business services; all Production components
> = the two-piece set of the root compose (business stack + Flink ×7 + Superset) ∥ infra (Kafka/CH/Apicurio/observability surface);
> object storage archiving is not in any compose (the `deploy/backup` script + `deploy/k8s/backup-cronjob`).
> The Standard tier has no Flink jobs (`events_raw` has no downstream consumer, CH has only schema and no data) — this too has been faithfully entered into the matrix.

---

## 8. Scheduled Batches (Code Agent Execution Order)

Batch dependencies: B1 → B2 → B3 → B4 → B5 → B6; B7 ∥ B5; B8 ∥ B5; B9 any time; B10 last.

| Batch | Content (§ anchor) | Estimated blast radius | Acceptance criteria (exit condition) |
|---|---|---|---|
| **B1** | Product positioning alignment: GitHub description, completion_assessment archive banner, add 06 to index.md (§1) | 3 pure documentation changes | No code; the banner/description/index are all in place; no tests triggered |
| **B2** | Converge permissions to a single source of truth: one-off backfill migration + `updateRoles`/`createUser` rewritten to write assignments + delete the manual psql from README §5 (§2.6) | control-service, migration ×1, deploy/demo README | All green; create account → assign role → permission-gated endpoint returns 200 (real-PG reproduction method); README §5 has no psql section |
| **B3** | Server SDK skeleton (`sdks/server`, Java): Initialize/SetUser/Track/Flush/Memory→Disk Queue→Batch→Gzip→HMAC (§3.1, §4.3) | New module | The SDK integration test passes (queue overflow spills to disk, HMAC requests pass gateway signature verification 200 / forged 401); a gateway SERVER key issuance verification test |
| **B4** | Event v2 increment: `event_version/source/trust_level/event_origin` + the three schema files kept in sync + gateway validation (§4.2) | gateway, schema/json×3, field pass-through in each Flink job | All green; a v1 event without source still returns 200 (backward compatible); when source=client the trusted level is LOW and cannot be self-elevated by rewriting |
| **B5** | Risk Feature layer (§5.1) | control, risk-job, migration ×1 | See the 5.4 acceptance |
| **B6** | Standalone RiskScore + Decision state machine + the HIGH trust gate for Block actions (§5.2) | control, risk-job | See the 5.4 acceptance |
| **B7** | Promote EventSchema to a first-class resource (TrackingPlan upgrade, compatibility/PII/retention fields, a schemas group in the UI tree) (§2.3) | control, web | All green; EventSchema API CRUD + version publish/compatibility-check tests |
| **B8** | Formalize the experiment platform (§6) | control, web, schema event list | All green; Audience/Guardrail/Decision field-based + status enum-ized; the existing Splitter/Aggregator tests pass with zero modifications; exposure is queryable |
| **B9** | MODES.md + orchestration tier annotations (§7) | deploy/ documentation | The three-tier component matrix is consistent with the current orchestration (verified by reading the code) |
| **B10** | Data Quality (event_valid_rate/drop_rate/duplicate/late metrics + a Game Data Health page) and defining the schema for the shared Feature dual write (§5.3); **no Data Lineage scheduling** | control, web, Flink | The metric table is persisted and queryable; the health page has data to show |

> The reason B10 is scheduled last (correctness first): Data Quality is an observability means that comes after B2/B4/B5 stabilize, not something to build first; Data Lineage only records direction and does not build its own system (the metric source chain is already given in `04-redesign §5`, which is enough).

---

## 9. Boundaries (the do-not-do list, written into the code review checklist)

1. **Do not** make it multi-tenant/SaaS-ized (the Organization/Workspace/Project/Tenant concepts do not enter new code).
2. **Do not delete** the Gateway `project_id` compatibility layer (§3.3).
3. **Do not allow** the free flow of events: outside dev mode, events with no registered schema are rejected by default (B7 landed: a new Schema defaults to `rejectUnknownEvents=true` (closed in V0.9.20); the event surface of the ACTIVE Schema is pushed to the gateway via the internal feed; undefined events are rejected at the event level as `unknown_event`; the dev environment is exempt).
4. **Do not let** the SDK/business layer hold physical routing (storage_profile is the only bridge).
5. **Do not** systematize Data Lineage, **do not** build cross-company data exchange, **do not** add new endpoints beyond the mobile native queue.
6. Numbers in the old assessment files such as "permissions 15% complete" and the "12-week plan" **must never again be cited by anyone as the current state** (after the B1 banner is added the wording is unified across the whole repo).
