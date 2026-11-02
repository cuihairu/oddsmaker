# 代码深审报告 — oddsmaker 单体仓库（2026-11-01）

- **审计范围**: 全仓库（control-service、gateway-service、Flink 作业、SDK、web 控制台、ml/）
- **审计日期**: 2026-11-01（执行完成 2026-11-02）
- **审计方法**: requesting-code-review 技能流程 — 安全静态扫描、隐藏 bug 猎捕、质量门基线
- **严重度分级**: P0（阻塞/致命）· P1（严重）· P2（中等）· P3（轻微）
- **处置**: P0/P1 当场修（一问题一 commit，测试绿）；P2/P3 记入 `todo.md`（T-REV01–28）

---

## 1. 汇总

| 分桶 | 总数 | P0 | P1 | P2 | P3 | 已修 | 剩余 |
|--------|------:|---:|---:|---:|---:|------:|----------:|
| 安全静态扫描 | 7 | 0 | 1 | 0 | 6 | 1 | 6 |
| 隐藏 bug（流式） | 11 | 0 | 2 | 4 | 5 | 2 | 9 |
| 隐藏 bug（控制面） | 12 | 0 | 0 | 7 | 5 | 0 | 12 |
| 质量门 | 2 | 0 | 0 | 2 | 0 | 0 | 2 |
| **合计** | **32** | **0** | **3** | **13** | **16** | **3** | **29** |

**记录 29 项发现**（32 项 − 3 项已修）。**3 项 P1 已修**，13 项 P2 + 16 项 P3 剩余（含 2 项质量门缺口）→ `todo.md` T-REV01–28。

**修复 commit 清单**:
| # | Commit | 发现 |
|---|--------|---------|
| P1-1 | `9bb7423` | EventsExportService 路径穿越（exportDay / listDays） |
| P1-2 | `81c7820` | BatchController 发布失败未释放幂等位 |
| P1-3 | `a6c5704` | AvroPublisher 异步发送失败静默丢失（未路由 DLQ） |

---

## 2. P1 — 已当场修复

### P1-1 事件导出的路径穿越（已修 — `9bb7423`）

**位置**: `services/control-service/src/main/java/io/oddsmaker/control/service/EventsExportService.java` — `exportDay(...)`（gameId 路径变量 + environment body 字段拼接进 `Path.of(storageDir, gameId, "events", ...)` 与 `fileName = "events-" + environment + ".jsonl"`）及 `listDays(gameId)`。

**问题**: `gameId`（路径）与 `environment`（body）仅做非空校验，随后无片段校验直接拼入文件系统路径。请求 `GET /events/{gameId}/export?environment=..%2F..%2Fetc` 可穿越存储根目录；`environment="..%2F.."` 同样可进文件名。

**修复**: 新增 `requireSafePathSegment(value, errorCode, reason)` 拒绝 `/`、`\`、`\0`、`..`；接入 `exportDay`（gameId + environment）与 `listDays`（gameId），以 `BusinessException` 拒绝。

**测试**: `EventsExportServiceTest.exportDayAndListDaysRejectPathTraversal()`。

### P1-2 发布失败未释放幂等位（已修 — `81c7820`）

**位置**: `services/gateway-service/src/main/java/io/oddsmaker/gateway/security/ReplayGuard.java` + `api/BatchController.java`。

**问题**: `BatchController` 在 `publisher.publish(event)` **之前**消费事件幂等位（`replayGuard.consumeEventId(...)`）。发布失败（被 catch，走 `kafka_error` 拒绝路径）时该位仍保持已消费 — SDK 对同一 `eventId` 的重试被当重复拒绝，事件从未到达 Kafka 却永久丢失。

**修复**: 新增 `ReplayGuard.releaseEventId(gameId, environment, eventId)` 从 `eventIds.seen` 移除键；在 `catch (Exception ex)` 包住 `publisher.publish(event)` 的路径、`reject(...)` 之前调用。文档注明成功路径不得 release（防并发双发）。

**测试**: `ReplayGuardRotationTest.releaseEventIdAllowsReconsume()`、`BatchControllerCoverageTest.kafkaErrorReleasesIdempotencyBit()`（mock 先抛后成功；断言第二次提交被接受、duplicates == 0）。

### P1-3 异步发送失败静默丢失（已修 — `a6c5704`）

**位置**: `services/gateway-service/src/main/java/io/oddsmaker/gateway/kafka/AvroPublisher.java` — `publish(Event e)`。

**问题**: `publish` 原为 `return producer.send(new ProducerRecord<>(...))`，返回的 `Future` 从不被观察。`acks=all` 下 broker 故障时 `KafkaProducer.send` 在 I/O 线程返回异常完成的 Future — 调用方永远看不到，事件被网关计为 `ACCEPTED` 却从未进 Kafka。静默丢失，无日志无指标。

**修复**: 改用双参 `producer.send(record, callback)`（Kafka ≥ 3.7）。回调将任何 `exception != null` 路由至 `dlq.publish(eventId, "kafka_send_failed", json)`，内层 try/catch + `log.error` 保证 DLQ 失败不抛入发送路径。

**测试**: `PublishersTest.publishRoutesAsyncFailureToDlq()`（回调以 `TimeoutException` 触发 → DLQ 主题收到该事件）；`PublishersBrokerE2ETest` 构造点更新。

---

## 3. P2 — 剩余（→ todo）

| # | ID | 发现 | 位置 |
|---|----|---------|------|
| P2-1 | T-REV01 | `ReplayGuard.rotateIfNeeded` 竞态：`current == signatures` 在锁外判真，检查与加锁之间并发轮换导致 `else` 分支再次轮换，整代 TTL 幂等记录被丢弃 | `security/ReplayGuard.java:122-126` |
| P2-2 | T-REV02 | FunnelsJob 可选步回退未验证中间步 optional — 非 optional 中间步被静默跳过，N+1 步对 N−1 步做时间戳匹配 | `jobs/flink/funnels-job/.../ConfigurableFunnelsJob.java:450-462` |
| P2-3 | T-REV03 | risk-job FEATURE 广播状态无 TTL — 广播 `MapState` 不能带 TTL；闲置一周的特征值仍参与风控评估 | `jobs/flink/risk-job/.../FeatureRuleFunction.java:30-31,119` |
| P2-4 | T-REV04 | events-enrich dedup 的 DLQ 侧输出未接线 — 仅 `validated.getSideOutput(DLQ)` 接 DLQ sink，dedup 流的 DLQ 输出被孤立 | `jobs/flink/events-enrich-job/.../EventsEnrichJob.java:158 vs 411-437` |
| P2-5 | T-REV05 | `ExportService` 事务回滚毒化：类级 `@Transactional` + catch 内 `markAsFailed+save` 再抛 `RuntimeException` — API 代理路径整体回滚、FAILED 标记丢失、作业卡 PENDING；CH 查询 + 文件写在事务内持连接 | `services/control-service/.../ExportService.java:161-166` |
| P2-6 | T-REV06 | `CohortService` 同型事务毒化：外部调用下 `CALCULATING`/`FAILED` 双标记回滚；昂贵的 `computeCohort` CH 查询在事务内 | `services/control-service/.../CohortService.java:143-146` |
| P2-7 | T-REV07 | claim 并发唯一约束 → 500：`catch DataIntegrityViolation` 回查现有记录，但唯一违例已毒化事务 — 设计的中幂等 200 不可达；同型在 `RedeemCodeService:207`、`PlayerDataQueryService:154` | `services/control-service/.../MailService.java:168-171` |
| P2-8 | T-REV08 | `IdentityConsumer` 在 `upsertIdentity` 成功后吞异常并提交 — offset 前进、链接静默丢失、不重试 | `services/control-service/.../IdentityConsumer.java:82-84` |
| P2-9 | T-REV09 | `SegmentService` DELETE+INSERT 无原子性 — `ALTER DELETE` 后 `INSERT` 失败则 `segment_members` 置空、所有 `segmentFilterFragment` 返回空；需人工重跑 | `services/control-service/.../SegmentService.java:164-182` |
| P2-10 | T-REV10 | `BlockListClient` 缓存键缺 `gameId` — 本地缓存键 `targetType:targetValue` 无 gameId，跨游戏误封最长 15s | `services/gateway-service/.../config/BlockListClient.java:38-58` |
| P2-11 | T-REV11 | `DataQualityCounters` 非原子计数 — `arr[ordinal]++` 非 `LongAdder`；并发 reactor 线程丢计数 | `services/gateway-service/.../metrics/DataQualityCounters.java:55` |
| P2-12 | T-REV12 | 无 JVM 静态分析门 — gradle 无 checkstyle/spotless/ktlint/detekt/pmd；CI 只跑 test — lint 门缺失 | `build.gradle.kts` + `.github/workflows/ci.yaml` |
| P2-13 | T-REV13 | web eslint 门已坏 — 无 eslint 配置（eslint ≥ 9 强制 flat config）+ `lint` 脚本用 eslint 10 已移除的 `--ignore-path`；CI 只接线 test/build | `web/package.json` + `web/vitest.config.js` |

---

## 4. P3 — 剩余（→ todo）

| # | ID | 发现 | 位置 |
|---|----|---------|------|
| P3-1 | T-REV14 | 死配置 `oddsmaker.security.jwt.*` — 全仓零消费者；真实密钥走 `oddsmaker.auth.jwt-secret` 且受 `ProductionSecretsValidator` 保护；死块含硬编码默认值待清 | `services/control-service/.../application.yaml:97-99` |
| P3-2 | T-REV15 | gateway 配置测试密钥对（`apiKey`→`secret`）随主配置，注释标生产勿用 | `services/gateway-service/.../application.yaml:34` |
| P3-3 | T-REV16 | k8s postgres 占位口令 — `CHANGE_ME_IN_PRODUCTION`；未改即应用则 DB 认证用占位 | `deploy/k8s/postgres-secrets.yaml:14` |
| P3-4 | T-REV17 | k8s redis 占位口令 | `deploy/k8s/redis-secrets.yaml:13` |
| P3-5 | T-REV18 | `MlRetrainScheduler` 内部 `http://` — 配置派生（非用户输入），非 TLS 拉取 | `services/control-service/.../MlRetrainScheduler.java:225` |
| P3-6 | T-REV19 | webhook/集成 URL 只查前缀 — admin 提供的端点仅验 `http(s)://` 前缀，允许 `http://` 到内网主机 | `services/control-service/.../WebhookService.java:345`、`IntegrationService:189`、`DimensionPullService:615` |
| P3-7 | T-REV20 | `EventQueue.diskEvents` 少计 — 溢盘后未加回 `rest.size()`，diskEvents 下计致磁盘超 `maxDiskEvents` | `sdks/server/.../EventQueue.java:153-166` |
| P3-8 | T-REV21 | `RiskJob.toJson` 只转义 `"` — 未转义 `\`；反斜杠/证据值致 `risk_events` 主题畸形 JSON | `jobs/flink/risk-job/.../RiskJob.java:1055` |
| P3-9 | T-REV22 | RetentionJob 迟发 `day < first` 被忽略 — 针对更早首日的迟发事件被直接丢弃，无队列校正 | `jobs/flink/retention-job/.../RetentionJob.java:195-238` |
| P3-10 | T-REV23 | 窗口/键控键裸 `|` 拼接无长度前缀 — 含 `|` 的 `game_id` 撞键并状态；同型在 `RetentionJob:123`、`FunnelsJob:121`、`IdentityMergeJob:140` | `jobs/flink/risk-job/.../RiskJob.java:988` |
| P3-11 | T-REV24 | `QuotaEntity` 可空列拆箱 NPE — `currentUsage` 为可空 BIGINT；`isOverLimit`/`getUsagePercent`/`incrementUsage` 未判空拆箱；同型在 `RateLimitService:166-167` | `services/control-service/.../jpa/QuotaEntity.java:98,113,133` |
| P3-12 | T-REV25 | ClickHouse `Statement` 未关 — `execute(conn -> conn.createStatement().execute(...))` 只关连接不关 Statement；同型在 `PlayerErasureService:624` | `services/control-service/.../RetentionEnforcementService.java:237` |
| P3-13 | T-REV26 | `@CreationTimestamp` 不回填内存实体 — `createGame` 返回 `GameDTO` 时 `createdAt`/`updatedAt` 仍 null；同型在 `GameService:247` | `services/control-service/.../GameService.java:97` |
| P3-14 | T-REV27 | `ReportService.timeRange` NumberFormatException — `TIME_RANGE_PATTERN` 允许无界数字；20+ 位 `timeRange` 抛未捕获 `NumberFormatException` → 500 | `services/control-service/.../ReportService.java:508` |
| P3-15 | T-REV28 | `RiskEventConsumer` 吞异常致 BLOCK 未强制 — `handleBlock` 瞬时失败被吞、offset 提交、BLOCK 永不执行，尽管 `risk_case` 已显 BLOCK 决策 | `services/control-service/.../RiskEventConsumer.java:162-165` |

---

## 5. 质量门

| 门 | 状态 | 详情 |
|------|--------|--------|
| JVM 静态分析 | **缺口**（P2-12） | 整个 gradle 构建无 checkstyle/spotless/ktlint/detekt/pmd 任务；CI（`ci.yaml`）只跑 test/build 作业 |
| Web eslint | **缺口**（P2-13） | `web/package.json` 有 `lint` 脚本但无 flat 配置（eslint ≥ 9 必需），且脚本传 `--ignore-path`（eslint 10 已移除）；CI 只接线 test/build |
| Web vitest | 绿 | 202/202 通过；coverage provider v8、jsdom |
| Gradle 测试 | 绿 | 2975 例（control-service + gateway + jobs + Android 70）— 与 CI 基线无回归 |

---

## 6. 范围与方法说明

- **安全静态扫描**覆盖全模块：硬编码密钥、shell 注入、SQL 拼接、`eval`、反序列化、路径穿越。路径穿越（P1-1）是唯一可利用项；JWT 死配置（P3-1）在验证 `oddsmaker.security.jwt.*` 零消费者后由初始 P2 降级。
- **隐藏 bug 猎捕**分流式（Flink + Kafka + gateway 响应式）与控制面（Spring Boot + JPA + ClickHouse + Redis）两轮。gateway 的 P1（P1-2/P1-3）耦合、一并修复：让 AvroPublisher 暴露异步失败而不释放幂等位，会把每次重试变成永久重复丢弃。
- **无 P0** — 无崩溃、无阻断主流程的不可达路径、无正常运行下的数据损坏缺陷。
- 双语：英文主报告于此；中文对照见 `docs/zh/review/2026-11-01-code-review.md`。
