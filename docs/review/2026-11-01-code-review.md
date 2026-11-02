# Code Deep Review — oddsmaker monorepo (2026-11-01)

- **Review scope**: full repository (control-service, gateway-service, Flink jobs, SDKs, web console, ml/)
- **Review date**: 2026-11-01 (run completed 2026-11-02)
- **Method**: requesting-code-review skill flow — security static scan, hidden-bug hunting, quality-gate baseline
- **Classification**: P0 (blocker/fatal) · P1 (severe) · P2 (medium) · P3 (minor)
- **Disposition**: P0/P1 fixed on the spot (one problem one commit, tests green); P2/P3 recorded to `todo.md` (T-REV01–28)

---

## 1. Summary

| Bucket | Total | P0 | P1 | P2 | P3 | Fixed | Remaining |
|--------|------:|---:|---:|---:|---:|------:|----------:|
| Security static scan | 7 | 0 | 1 | 0 | 6 | 1 | 6 |
| Hidden-bug hunt (stream) | 11 | 0 | 2 | 4 | 5 | 2 | 9 |
| Hidden-bug hunt (control-plane) | 12 | 0 | 0 | 7 | 5 | 0 | 12 |
| Quality gates | 2 | 0 | 0 | 2 | 0 | 0 | 2 |
| **Total** | **32** | **0** | **3** | **13** | **16** | **3** | **29** |

**29 findings recorded** (32 total − 3 fixed). **3 P1 fixed**, 13 P2 + 16 P3 remaining (including 2 quality-gate gaps) → `todo.md` T-REV01–28.

**Fixed commits**:
| # | Commit | Finding |
|---|--------|---------|
| P1-1 | `9bb7423` | EventsExportService path traversal (exportDay / listDays) |
| P1-2 | `81c7820` | BatchController idempotency bit not released on publish failure |
| P1-3 | `a6c5704` | AvroPublisher async send failure silently lost (not routed to DLQ) |

---

## 2. P1 — Fixed on the spot

### P1-1 Path traversal in events export (FIXED — `9bb7423`)

**Location**: `services/control-service/src/main/java/io/oddsmaker/control/service/EventsExportService.java` — `exportDay(...)` (gameId path variable + environment body field concatenated into `Path.of(storageDir, gameId, "events", ...)` and `fileName = "events-" + environment + ".jsonl"`) and `listDays(gameId)`.

**Problem**: `gameId` (from path) and `environment` (from body) were only non-blank checked, then concatenated into a filesystem path with no segment validation. A request `GET /events/{gameId}/export?environment=..%2F..%2Fetc` could traverse outside the storage root; `environment="..%2F.."` in the filename likewise.

**Fix**: added `requireSafePathSegment(value, errorCode, reason)` rejecting `/`, `\`, `\0`, and `..`; wired into `exportDay` (gameId + environment) and `listDays` (gameId). Rejects with `BusinessException`.

**Test**: `EventsExportServiceTest.exportDayAndListDaysRejectPathTraversal()`.

### P1-2 Idempotency bit not released on publish failure (FIXED — `81c7820`)

**Location**: `services/gateway-service/src/main/java/io/oddsmaker/gateway/security/ReplayGuard.java` + `api/BatchController.java`.

**Problem**: `BatchController` consumed the event idempotency bit (`replayGuard.consumeEventId(...)`) **before** `publisher.publish(event)`. On a publish failure (caught, `kafka_error` reject path), the bit stayed consumed — the SDK's retry of the same `eventId` was rejected as a duplicate, permanently losing the event even though it never reached Kafka.

**Fix**: added `ReplayGuard.releaseEventId(gameId, environment, eventId)` which removes the key from `eventIds.seen`; called in the `catch (Exception ex)` path around `publisher.publish(event)` before `reject(...)`. Documented that the success path must NOT release (prevents concurrent double-send).

**Tests**: `ReplayGuardRotationTest.releaseEventIdAllowsReconsume()`, `BatchControllerCoverageTest.kafkaErrorReleasesIdempotencyBit()` (mock publish throws then succeeds; asserts second submission accepted, duplicates == 0).

### P1-3 Async send failure silently lost (FIXED — `a6c5704`)

**Location**: `services/gateway-service/src/main/java/io/oddsmaker/gateway/kafka/AvroPublisher.java` — `publish(Event e)`.

**Problem**: `publish` did `return producer.send(new ProducerRecord<>(...))` and the returned `Future` was never awaited. With `acks=all` and a broker outage, `KafkaProducer.send` returns a Future that completes exceptionally on the I/O thread — the caller never observes it, the event is counted `ACCEPTED` by the gateway yet never reaches Kafka. Silent loss with no log or metric.

**Fix**: switched to the 2-arg `producer.send(record, callback)` (Kafka ≥ 3.7). The callback routes any `exception != null` to `dlq.publish(eventId, "kafka_send_failed", json)`, with an inner try/catch + `log.error` so DLQ failure cannot throw into the send path.

**Tests**: `PublishersTest.publishRoutesAsyncFailureToDlq()` (callback invoked with `TimeoutException` → DLQ topic receives the event); `PublishersBrokerE2ETest` constructor sites updated.

---

## 3. P2 — Remaining (→ todo)

| # | ID | Finding | Location |
|---|----|---------|----------|
| P2-1 | T-REV01 | `ReplayGuard.rotateIfNeeded` race: `current == signatures` checked outside the lock, a concurrent rotation between check and lock causes the `else` branch to rotate again, dropping the whole generation's TTL idempotency records | `security/ReplayGuard.java:122-126` |
| P2-2 | T-REV02 | FunnelsJob optional-step fallback doesn't verify intermediate steps are optional — a non-optional middle step is silently skipped, then an N+1-step sequence is timestamp-matched against an N−1-step one | `jobs/flink/funnels-job/.../ConfigurableFunnelsJob.java:450-462` |
| P2-3 | T-REV03 | risk-job FEATURE broadcast state has no TTL — `MapState` broadcast state can't carry TTL; a feature value left idle for a week still participates in risk evaluation | `jobs/flink/risk-job/.../FeatureRuleFunction.java:30-31,119` |
| P2-4 | T-REV04 | events-enrich dedup's DLQ side output is unwired — only `validated.getSideOutput(DLQ)` is connected to the DLQ sink; the dedup stream's DLQ output is orphaned | `jobs/flink/events-enrich-job/.../EventsEnrichJob.java:158 vs 411-437` |
| P2-5 | T-REV05 | `ExportService` transaction rollback-only poison: class-level `@Transactional` + catch that calls `markAsFailed+save` then rethrows `RuntimeException` — the API-proxy path rolls back the whole transaction, the FAILED marker is lost, the job stays PENDING; CH query + file write happen inside the transaction holding the connection | `services/control-service/.../ExportService.java:161-166` |
| P2-6 | T-REV06 | `CohortService` same transaction poisoning: under an external call both `CALCULATING`/`FAILED` markers roll back; the expensive `computeCohort` CH query runs inside the transaction | `services/control-service/.../CohortService.java:143-146` |
| P2-7 | T-REV07 | claim concurrent unique-constraint → 500: `catch DataIntegrityViolation` re-queries the existing record, but the unique violation has already poisoned the transaction — the designed idempotent 200 is unreachable; same pattern in `RedeemCodeService:207`, `PlayerDataQueryService:154` | `services/control-service/.../MailService.java:168-171` |
| P2-8 | T-REV08 | `IdentityConsumer` swallows the exception after a successful `upsertIdentity` and commits — offset advances, the link is silently lost, no retry | `services/control-service/.../IdentityConsumer.java:82-84` |
| P2-9 | T-REV09 | `SegmentService` DELETE+INSERT not atomic — an `ALTER DELETE` followed by a failed `INSERT` leaves `segment_members` empty and every `segmentFilterFragment` returns empty; requires manual rerun | `services/control-service/.../SegmentService.java:164-182` |
| P2-10 | T-REV10 | `BlockListClient` cache key lacks `gameId` — the local cache key `targetType:targetValue` has no gameId, so a cross-game false block persists up to 15s | `services/gateway-service/.../config/BlockListClient.java:38-58` |
| P2-11 | T-REV11 | `DataQualityCounters` non-atomic counter — `arr[ordinal]++` not a `LongAdder`; concurrent reactor threads lose counts | `services/gateway-service/.../metrics/DataQualityCounters.java:55` |
| P2-12 | T-REV12 | No JVM static-analysis gate — gradle has no checkstyle/spotless/ktlint/detekt/pmd; CI only runs test — lint gate missing | `build.gradle.kts` + `.github/workflows/ci.yaml` |
| P2-13 | T-REV13 | web eslint gate broken — no eslint config (eslint ≥ 9 requires flat config) + `lint` script uses `--ignore-path` removed in eslint 10; CI only wires test/build | `web/package.json` + `web/vitest.config.js` |

---

## 4. P3 — Remaining (→ todo)

| # | ID | Finding | Location |
|---|----|---------|----------|
| P3-1 | T-REV14 | Dead config `oddsmaker.security.jwt.*` — zero consumers repo-wide; real secret is `oddsmaker.auth.jwt-secret` guarded by `ProductionSecretsValidator`; dead block contains a hardcoded default to remove | `services/control-service/.../application.yaml:97-99` |
| P3-2 | T-REV15 | Gateway config test key pairs (`apiKey`→`secret`) shipped with main config, comment marks prod-not-for-use | `services/gateway-service/.../application.yaml:34` |
| P3-3 | T-REV16 | k8s postgres placeholder password — `CHANGE_ME_IN_PRODUCTION`; if unmodified the DB auths with the placeholder | `deploy/k8s/postgres-secrets.yaml:14` |
| P3-4 | T-REV17 | k8s redis placeholder password | `deploy/k8s/redis-secrets.yaml:13` |
| P3-5 | T-REV18 | `MlRetrainScheduler` internal `http://` — config-derived (not user input), non-TLS fetch | `services/control-service/.../MlRetrainScheduler.java:225` |
| P3-6 | T-REV19 | webhook/integration URL only prefix-checked — admin-supplied endpoints validated only against an `http(s)://` prefix, allowing `http://` to internal hosts | `services/control-service/.../WebhookService.java:345`, `IntegrationService:189`, `DimensionPullService:615` |
| P3-7 | T-REV20 | `EventQueue.diskEvents` undercount — after a spill-to-disk overflow the returned size doesn't add back `rest.size()`, undercounting `diskEvents` so the disk can exceed `maxDiskEvents` | `sdks/server/.../EventQueue.java:153-166` |
| P3-8 | T-REV21 | `RiskJob.toJson` escapes only `"` — doesn't escape `\`; backslashes / evidence values produce malformed JSON on the `risk_events` topic | `jobs/flink/risk-job/.../RiskJob.java:1055` |
| P3-9 | T-REV22 | RetentionJob late event with `day < first` is ignored — a late event targeting an earlier first-day is dropped with no queue correction | `jobs/flink/retention-job/.../RetentionJob.java:195-238` |
| P3-10 | T-REV23 | Window/keying keys naively `|`-concatenated with no length prefix — a `game_id` containing `|` collides keys and state; same in `RetentionJob:123`, `FunnelsJob:121`, `IdentityMergeJob:140` | `jobs/flink/risk-job/.../RiskJob.java:988` |
| P3-11 | T-REV24 | `QuotaEntity` unboxing NPE — `currentUsage` is nullable BIGINT; `isOverLimit`/`getUsagePercent`/`incrementUsage` unbox without a null check; same in `RateLimitService:166-167` | `services/control-service/.../jpa/QuotaEntity.java:98,113,133` |
| P3-12 | T-REV25 | ClickHouse `Statement` not closed — `execute(conn -> conn.createStatement().execute(...))` closes only the connection, not the Statement; same in `PlayerErasureService:624` | `services/control-service/.../RetentionEnforcementService.java:237` |
| P3-13 | T-REV26 | `@CreationTimestamp` doesn't backfill the in-memory entity — `createGame` returns a `GameDTO` with `createdAt`/`updatedAt` still null; same in `GameService:247` | `services/control-service/.../GameService.java:97` |
| P3-14 | T-REV27 | `ReportService.timeRange` NumberFormatException — `TIME_RANGE_PATTERN` allows unbounded digits; a 20+ digit `timeRange` throws an uncaught `NumberFormatException` → 500 | `services/control-service/.../ReportService.java:508` |
| P3-15 | T-REV28 | `RiskEventConsumer` swallows the exception → BLOCK never enforced — a transient `handleBlock` failure is swallowed, offset committed, BLOCK never executes despite the `risk_case` showing a BLOCK decision | `services/control-service/.../RiskEventConsumer.java:162-165` |

---

## 5. Quality gates

| Gate | Status | Detail |
|------|--------|--------|
| JVM static analysis | **Gap** (P2-12) | No checkstyle/spotless/ktlint/detekt/pmd task anywhere in the gradle build; CI (`ci.yaml`) runs only test/build jobs |
| Web eslint | **Gap** (P2-13) | `web/package.json` has a `lint` script but no flat config (eslint ≥ 9 requires one) and the script passes `--ignore-path` (removed in eslint 10); CI wires only test/build |
| Web vitest | Green | 202/202 pass; coverage provider v8, jsdom |
| Gradle tests | Green | 2975 cases (control-service + gateway + jobs + Android 70) — no regression vs CI baseline |

---

## 6. Notes on scope & method

- **Security static scan** covered hardcoded secrets, shell injection, SQL concatenation, `eval`, deserialization, and path traversal across all modules. The path-traversal finding (P1-1) was the only exploitable one; the JWT dead config (P3-1) was downgraded from an initial P2 after verifying `oddsmaker.security.jwt.*` has zero consumers.
- **Hidden-bug hunt** split into stream (Flink + Kafka + gateway reactive) and control-plane (Spring Boot + JPA + ClickHouse + Redis) passes. The gateway P1s (P1-2/P1-3) were coupled and fixed together: making AvroPublisher surface async failures without releasing the idempotency bit would have turned every retry into a permanent duplicate-drop.
- **No P0** was found — no crash, no unreachable path that blocks a primary flow, no data-corruption-under-normal-operation defect.
- Bilingual: English primary here; Chinese mirror at `docs/zh/review/2026-11-01-code-review.md`.
