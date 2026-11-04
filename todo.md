# TODO

需求清单：一条一句；完成项留一行证据（commit + 测试数）。重构契约 = `docs/zh/redesign/06-od2-restructure-plan.md`。

## 账本使用协议（GOV-001，2026-10-19 代决，详见 docs/决策记录.md）

- 每条任务一个稳定 ID（T-*）、一个权威状态：状态只写在任务行上，不靠历史留痕推断。
- 复核无变化时不改行；被收敛前的逐巡留痕由 git 历史保存（`git log -p todo.md`）。
- 有实质变化（新证据/状态翻转/整改落地）才改行：更新状态、证据与解锁条件；卡点写清缺什么。
- 已完成条目移入「已完成需求」；「边界」是不可再提事项；两者均不占开放账。
- 审计台账同源：SITE-001 与 T-DEMO502、ML-001 与 T-ML 同源（勿双记，以本账为准）。

## 开放需求

- [ ] T-DEMO502 Demo 站公网 502（P1，owner=用户侧）：解锁=用户在 CF 面板把 `oddsmaker.cuihairu.site` 的 origin A 记录改指 `106.14.216.177`（保持橙云），并把源站 443 换回受信证书或把 CF SSL 临时改为 Full。2026-11-01 复核：经 CF=502；`curl --resolve` 直连源站 :443=200（`-k`）；TLS 信任态未核验（仓内取证腿仅 `openssl x509 -noout -subject -dates`）。仓内与源站无可修项。【已代决暂缓（需用户输入），按 docs/review/2026-10-30-decisions.md 执行】
- [ ] T-CDC Debezium CDC 维度同步（P3，owner=等外部输入）：解锁=游戏方开 binlog + 部署 Kafka Connect + 提供 CDC 事件格式与目标表映射，三者齐备即开工。2026-11-01 复核：`grep -rl binlog deploy/`=0、`grep -rn debezium deploy/`=0，三项外部输入仍未到；仓内预备面已备（dimension-sync.md CDC 节三选项对比 + 信封→`RawDimensionChange` 映射表，实证选项 C 不可行）。【已代决暂缓（需外部输入）】
- [ ] T-ML ML 异常检测进评估批次（P3，owner=等部署面）：解锁=四项触发条件任一达标；条件 a 需 infra 栈部署 + 带网关流量的部署面，自指标落库满 7 天（7d）/28 天（28d 分位基线）；条件 b/c（阈值调整次数/误杀率）缺记录面。2026-11-01 复核：infra-prometheus 未运行（Exited 11 天前）、demo compose 服务面仍=postgres/redis/control-service（无 gateway）、127.0.0.1:9090 由 Cockpit 占用——条件 a 时钟未起。【已代决暂缓（需部署面输入）】
- [ ] DOC-GIF 配置开关效果动态图（P2，文档，BLOCKED）：枚举全部「开启后有效果」的开关/配置项，自动流水线生成证据（GUI 类 off/on 截图 + 切换录屏，CLI 类终端录屏，ffmpeg 缝合 GIF）；docs「配置效果」页（双语）逐开关嵌入说明 + off 图 + on 图 + GIF；流水线可重跑，兼作配置效果回归测试（参考 kode todo C-24 同款模式，2026-11-01 用户令全舰队推广）。【已代决暂缓（需真实部署机 + GUI 浏览器会话 + 用户圈定开关范围）：demo compose 服务面仍=postgres/redis/control-service（无 gateway），127.0.0.1:9090 由 Cockpit 占用，无可用 GUI 会话截图/录屏；ffmpeg 未验证】

## 代码深审 2026-11-02 挂账（T-REV，来源 docs/review/2026-11-01-code-review.md；P0/P1 已当场修，此处仅 P2/P3）

- [x] T-REV01 ReplayGuard 轮换竞态（P2）：——已修复：`rotateIfNeeded` 显式区分目标代并在锁内按目标字段双检，过期签名代并发轮换后不落入 `eventIds` 分支；新增过期签名代竞态回归测试（ReplayGuard.java:75-83/:90/:121-143、ReplayGuardRotationTest.java:62-84；gateway-service 测试 246 例 0 失败）
- [x] T-REV02 FunnelsJob 可选步回退未验证中间步 optional（P2）：——已修复：optional 回退先计算最近必选下界，只允许锚定到未跨必选中间步的更早时间戳；新增必选中间步阻断与全 optional 中间步放行测试（ConfigurableFunnelsJob.java:450-466、ConfigurableFunnelsJobTest.java:483-506/:793-836；funnels-job 测试 48 例 0 失败）
- [x] T-REV03 risk-job FEATURE 广播状态无 TTL（P2）：——已修复：广播 MapState 不能带 Flink TTL，按特征行 as_of 做处理时间过期（缺省 24h，`risk.feature.broadcast-ttl-ms` 可调），陈旧值按无值不参与评估；新增过期判定与陈旧不命中测试（FeatureRuleFunction.java:30-44/:81-99、RiskFeatureTest.java:344-359；risk-job 测试 100 例 0 失败）
- [x] T-REV04 events-enrich dedup 的 DLQ 侧输出未接线（P2）：——已修复：dedup 算子单独接线到 DLQ sink（dlq-sink-dedup），duplicate 事件不再孤立；管道搭建测试断言 validate 与 dedup 两处侧输出均已接线（EventsEnrichJob.java:115-117/:159-160、EventsEnrichJobTest.java:44-49；events-enrich-job 测试 52 例 0 失败）
- [x] T-REV05 ExportService 事务回滚毒化（P2）：——已修复：移除类级 @Transactional，create/cancel/查询/清理保留局部事务，processExportJob 每个 save 独立提交且 CH 查询/文件写不持 DB 连接，失败 FAILED 标记在 rethrow 后持久化；新增 Spring+H2 事务回归（ExportService.java:24/:68/:199、ExportServiceTransactionTest.java:33-78；ExportService 相关 7 个测试类 99 例 0 失败）
- [x] T-REV06 CohortService 同型事务毒化（P2）：——已修复：移除类级 @Transactional，createCohort 保留局部事务，calculateCohort 每个 save 独立提交、重 CH 查询不持 DB 连接，FAILED 标记在 rethrow 后持久化；新增 Spring+H2 事务回归（CohortService.java:22/:56、CohortServiceTransactionTest.java:33-74；Cohort 相关 9 个测试类 173 例 0 失败）
- [x] T-REV07 claim 并发唯一冲突 500（P2）：——已修复：claim/ingestPayment 去方法级 @Transactional，唯一违例在各自 repo 短事务提交回滚、catch 回查在新事务读到胜者，幂等 200 可达；RedeemCodeService.redeem 经 catch 抛业务异常（409 可达、无回查），已符合；新增真实并发双线程回归（MailService.java:138、PlayerDataQueryService.java:123、IdempotentConflictTest.java:40-120；相关 7 个测试类 162 例 0 失败）
- [x] T-REV08 IdentityConsumer 吞异常致链接丢失（P2）：——已修复：移除 catch 吞异常，upsert/链接写失败向上传播供 Kafka 容器按错误处理器重试（offset 不前进），环境名解析的 best-effort miss 仍内部兜底；新增链接失败传播回归 + 更新旧「被吞掉」断言（IdentityConsumer.java:72-81、IdentityConsumerTest.java:190-197、FinalSweep2Test.java:469-478；相关 5 个测试类 119 例 0 失败）
- [x] T-REV09 SegmentService DELETE+INSERT 无原子性（P2）：——已修复：改先 INSERT 新代（computed_at=now64(3)）后 DELETE 旧代（computed_at < 本代标记），INSERT 失败旧成员完整保留、不再置空分群，DELETE 失败由 ReplacingMergeTree 同主体新替旧 + 下次成功物化自愈；新增 INSERT 失败保留旧成员回归（SegmentService.java:163-182、SegmentServiceTest.java:224-241；相关 5 个测试类 75 例 0 失败）
- [x] T-REV10 BlockListClient 缓存键缺 gameId（P2）：缓存/结果键改为 gameId:targetType:targetValue，控制器同键读取；跨游戏回归覆盖（services/gateway-service/src/main/java/io/oddsmaker/gateway/config/BlockListClient.java:153，services/gateway-service/src/test/java/io/oddsmaker/gateway/config/GatewayRemoteClientsTest.java:149）；gateway-service tests=247 failures=0
- [x] T-REV11 DataQualityCounters 非原子计数（P2）：窗口计数改为 LongAdder，快照取 sum；并发回归覆盖（services/gateway-service/src/main/java/io/oddsmaker/gateway/metrics/DataQualityCounters.java:57，services/gateway-service/src/test/java/io/oddsmaker/gateway/metrics/DataQualityCountersTest.java:83）；gateway-service tests=248 failures=0
- [x] T-REV12 无 JVM 静态分析门（P2）：全模块接入 PMD（errorprone EmptyCatchBlock/CompareObjectsWithEquals + bestpractices PreserveStackTrace），check 挂 PMD 门，CI/nightly/release 由 test 改 check；修复全仓 60+ 违规（build.gradle.kts:50，config/pmd/ruleset.xml:1，.github/workflows/ci.yaml:56）；全模块 check tests=2938 failures=0
- [x] T-REV13 web eslint 门已坏（P2）：新增 ESLint9 flat config（essential + 核心错误规则），修复 16 个 lint 错误；lint 脚本去除 --ext/--ignore-path，CI 接入 lint，并将测试线程池/超时调稳（web/eslint.config.js:1，web/package.json:10，web/vitest.config.js:21，.github/workflows/ci.yaml:137）；lint/build/tests 202 passed / 0 failed
- [x] T-REV14 死配置 oddsmaker.security.jwt.*（P3）：删除零消费者的 security.jwt 块（含硬编码默认密钥）；真实密钥走 oddsmaker.auth.jwt-secret（services/control-service/src/main/resources/application.yaml:96）；control-service check tests=2115 failures=0
- [x] T-REV15 gateway 配置测试密钥对（P3）：测试密钥对移至并仅保留于 src/test/resources/application.yaml，主配置删除全部 pk_/sk_ 对（services/gateway-service/src/main/resources/application.yaml:32，services/gateway-service/src/test/resources/application.yaml:9）；gateway-service check tests=248 failures=0
- [x] T-REV16 k8s postgres 占位口令（P3）：Secret 改空模板 + Deployment initContainer 校验非空且非占位，漏配 fail-closed 拒绝启动（deploy/k8s/postgres-secrets.yaml:14，deploy/k8s/postgres.yaml:64）；ReleaseDeployContractTest 钉空模板；docker 实证 POSTGRES_PASSWORD="" → exit 1
- [x] T-REV17 k8s redis 占位口令（P3）：Secret 改空模板 + Deployment initContainer 校验非空且非占位，漏配 fail-closed 拒绝启动（deploy/k8s/redis-secrets.yaml:13，deploy/k8s/redis.yaml:43）；ReleaseDeployContractTest 钉空模板
- [x] T-REV18 MlRetrainScheduler 内部 http://（P3）：支持 JDBC ssl=true→https 派生，显式训练 URL 限制 http(s) scheme（services/control-service/src/main/java/io/oddsmaker/control/service/MlRetrainScheduler.java:198）；MlRetrainSchedulerTest 22 tests / 0 fail
- [x] T-REV19 webhook/集成 URL 只查前缀（P3）：新增 UrlSecurityValidator（http/https + 拒绝私网/环回/链路本地，可配置 allow-private-networks 豁免），webhook/集成/维度拉取三处统一接入（services/control-service/src/main/java/io/oddsmaker/control/security/UrlSecurityValidator.java:1）；control-service check tests=2118 failures=0
- [x] T-REV20 EventQueue diskEvents 少计（P3）：超大溢出文件分批读后余量写回时补记 diskEvents，防止计数下偏破坏 maxDiskEvents（sdks/server/src/main/java/io/oddsmaker/sdk/EventQueue.java:167）；sdks:server check tests=9 failures=0
- [x] T-REV21 RiskJob toJson 只转义 "（P3）：未转义 \，反斜杠/证据值致 risk_events 主题畸形 JSON（jobs/flink/risk-job/.../RiskJob.java:1055）；证据：RiskJob.java:1048 统一 jsonEscape，risk-job 100 tests 0 fail
- [x] T-REV22 RetentionJob 迟发 day<first 被忽略（P3）：队列已设后更早首日事件直接丢弃，无队列校正（jobs/flink/retention-job/.../RetentionJob.java:195-238）；证据：RetentionJob.java:217 队列校正，retention-job 22 tests 0 fail
- [x] T-REV23 窗口/键控键裸拼接无长度前缀（P3）：game_id|environment|subject 裸拼接，含 | 的 game_id 撞键并状态；RiskJob:988、RetentionJob:123、FunnelsJob:121、IdentityMergeJob:140 同型；证据：KeyCodec.java:11 长度前缀，RiskJob.java:990/995、RetentionJob.java:125、FunnelsJob.java:123、ConfigurableFunnelsJob.java:159、IdentityMergeJob.java:142 全量切换，events-enrich 53 / risk 100 / funnels 48 / identity-merge 18 / retention 22 tests 0 fail
- [x] T-REV24 QuotaEntity 可空列拆箱 NPE（P3）：currentUsage 为可空 BIGINT，isOverLimit/getUsagePercent/incrementUsage 未判空拆箱；RateLimitService:166-167 同型（services/control-service/.../jpa/QuotaEntity.java:98,113,133）；证据：QuotaEntity.java:98 usage() 兜底，RateLimitService.java:167 复用，control-service 2118 tests 0 fail
- [x] T-REV25 ClickHouse Statement 未关（P3）：execute(conn -> conn.createStatement().execute(...)) 只关连接不关 Statement；PlayerErasureService:624 同型（services/control-service/.../RetentionEnforcementService.java:237）；证据：RetentionEnforcementService.java:238 try-with-resources、PlayerErasureService.java:625 同型，control-service 2118 tests 0 fail
- [x] T-REV26 @CreationTimestamp 不回填内存实体（P3）：createGame 返回 GameDTO 时 createdAt/updatedAt 仍 null；GameService:247 同型（services/control-service/.../GameService.java:97）；证据：GameService.java:88/92 时间戳兜底，createEnvironment 同型（:251），control-service 2118 tests 0 fail
- [x] T-REV27 ReportService timeRange NumberFormatException（P3）：TIME_RANGE_PATTERN 允许无界数字，20+ 位 timeRange 抛未捕获 NumberFormatException→500（services/control-service/.../ReportService.java:508）；证据：ReportService.java:510 位数上限，control-service 2119 tests 0 fail
- [x] T-REV28 RiskEventConsumer 吞异常致 BLOCK 未强制（P3）：handleBlock 瞬时失败被吞、offset 提交、BLOCK 永不执行；risk_case 已显 BLOCK 决策（services/control-service/.../RiskEventConsumer.java:162-165）；证据：RiskEventConsumer.java:168 BLOCK 失败重抛，:213 同判定幂等，control-service 2120 tests 0 fail

## 已完成需求

- [x] T-AUDIT 外部生产审计整改收口（P0）：审计 11 条全部核验、仓内可动整改全部落地（ML/SITE 两面由 T-ML/T-DEMO502 同源挂账不双记），§10 终验清单 9 勾 + 2 条纯外部卡点（首次真实发版健康检查/事件冒烟、生产流量告警实测）；整改链 aae925e/27df30b/0569935/0ba912f/a51fa90（0ba912f：control-service 184 suite/2106 例 0 失败、promtool 49 例 SUCCESS），明细见 docs/review/review-2026-10-18-production-audit.md
- [x] docs 内部链接指向一致性（P2）：兄弟页内部链接改指同语言对应页——4 处真 404 + 12 处中文兄弟页静默解析修正；全仓 46 条路由链接 + 140 条相对 `.md` 链接 0 失效，`pnpm docs:build` 绿（决策见 docs/决策记录.md）
- [x] docs 英文 locale 正文语言缺口（P2）：英文 locale 下 18 页中文正文与既有 `.en.md` 译版互换（英文为正本、中文归 `.zh.md`，已代决），EN 页正文无中文残留，36 文件互换后切换器双向对称、`pnpm docs:build` 绿
- [x] docs 子目录双语配对（P2）：52 个 .md 全配对（同目录兄弟文件，已代决）+ 51 个源文件插切换器；59 对结构计数一致 + 代码块逐字节相同，`pnpm docs:build` 绿；顺带修 06 §4.1 笔误（中英同批）
- [x] ruleConditions 引 EventSchema 字段（P2，已代决）：FEATURE 条件 feature 名取值域=risk-job 特征登记表（`RiskFeatures` 6 特征），写路径未知名显式拒绝，不做 EventSchema 交叉校验（5c501aa：control-service 174 suite/2039 例、risk-job 99 例 0 失败）
- [x] Signal 显式信号清单（P2）：两侧 `RiskSignals` 9 信号清单 + 各 3 例测试同钉 `DOCUMENTED` 名单，不改既有暴露面（7602a72：risk-job 全模块 96 例 0 失败）
- [x] ruleConditions 结构化 JSON schema（P2）：`RiskRuleEntity.ruleConditions` 收敛为类型化结构（严格/容忍双档），FEATURE 写路径收紧、存量行不改写、读路径保持容忍（7602a72：受影响 5 测试类 25/25 绿、control-service 173 suite/2034 例 0 失败）
- [x] B1 产品定位与文档对齐：GitHub description 一句话定位、三份旧评估归档横幅、redesign index 补 06 行（0f8617a、4a86775、568ffcb）
- [x] B2 权限单真源收敛：user_role_assignments 回填迁移 V0.9.16、UserService 唯一写路径、demo README 删手工 psql 兜底、真 PG 复现验证（ace946f，208 suite 绿）
- [x] B3 Server SDK 骨架：Java SDK 四方法、内存溢出落盘队列、批量 gzip HMAC 上报、网关验签正负臂、SERVER key 发放校验（115b51f，211 suite 绿）
- [x] B4 事件契约 v2：event_version/source/trust_level/event_origin 四字段、三枚 schema 同步、网关 TrustPolicy 防自抬、Flink 全 job 透传（70c9929，213 suite/2456 例绿）
- [x] B5 风控 Feature 层：risk_features 表 V0.9.18、risk-job 首 6 特征滑动窗口、规则条件三段解耦、真 PG 端到端（3c592f2，特征测试 16 例加全量绿）
- [x] B6 RiskScore 独立与 Decision 状态机：CH risk_scores 主体快照、状态机严格升层、trust 门槛双侧双保险、PG/CH 端到端各一（980ad8b，全量绿加状态机 11 例）
- [x] B7 EventSchema 一等资源化：五字段与发布兼容门、未知事件默认拒收、web schemas 页（88f99d7，发布 10 例、视图 13 例、全量绿）
- [x] B8 实验平台形式化：ExperimentEntity 字段化、schema 升级旧配置兼容、exposure 入平台事件清单（7b2c1f6，221 suite/2531 例绿）
- [x] B9 运行模式矩阵：deploy/MODES.md 三档组件矩阵、四处编排头注档位标记（0a16991，compose config 四文件全过）
- [x] B10 数据质量与共享特征收口：五指标落库与健康页、feature_store 双写、DLQ 消费者、网关 14 维埋点（8eba96c，228 suite/2551 例绿）
- [x] 策略实验室 V0.2 复盘聚合：规则维度聚合 API、案例样本下钻、/risk-lab 页（f4cef12，236 suite/2591 例、web 165 绿）
- [x] 策略实验室 V0.3 试算回放 dry-run：批量样本逐事件试算、流式类明示跳过、replay API 加试算面板（b2bb8a3，237/2599、web 168 绿）
- [x] 策略实验室 V0.4 样本集持久化：命名留档、载入重放对比、删除重建（6e99248，239/2612、web 171 绿）
- [x] 主体累计风险分读取端点：CH risk_scores 最新快照、reasons 三形态兼容解析（5135dc3，7 例绿）
- [x] 案例详情注入主体累计分：subjectRiskScore、CH 读取去事务防 rollback-only、控制台累计分行（17c521a、62d6424、80ae782，web 174 绿）
- [x] Webhook 派发事务隔离：sendCustomWebhook 标 REQUIRES_NEW，同族陷阱全仓 21 处扫清（42cffa7，13 例绿）
- [x] PII 优先级链生效：环境级 ACTIVE Schema piiPolicy 下发网关、字段级收敛压 ApiKey 级（7ed0d0f，241 suite/2627 例绿）
- [x] PII 策略控制台入口：SchemasView 策略列与 DRAFT 编辑弹层（c919626，web 180 绿）
- [x] 维度同步 HTTP Pull 链路：dimension_pull_config 表 V0.9.25、凭证 AES-GCM 托管、断点续传调度、手动触发 API（97b491e，24 例绿）
- [x] 维度同步 Control YAML 配置面：export/import 两端点、字段白名单、方法级事务（8b6c49c，22 例绿）
- [x] 维度同步控制台 Pull 管理面：配置表、建档编辑弹层、立即拉取、YAML 导入导出弹层（5be3931，web 191 绿）
- [x] 维度同步 Agent 属性面 Control 托管：dimension_agent_config 表 V0.9.26、props 白名单加六密钥 AES-GCM、CRUD 与下发口（cd26de6，25 例绿）
- [x] 维度同步 Agent 端取配置链路：ControlConfigFetcher 启动拉取、本地/Control/System property 三层合并（f9b41be，8 例绿）
- [x] 维度同步 Agent 托管配置控制台入口：配置表、新建编辑弹层、secrets 缺省保留或整体轮换、删除（d0ee2d2，web 198 绿）
- [x] 托管配置轮询热更新：control.config-refresh-seconds 门槛轮询、下发原文有变换组件、断点不动（a2b50e0，agent 211 例绿）
- [x] ml 训练管线测试纳入 CI：ml-tests job、Python 3.12、editable 安装含 dev extras（a707a32，68 例绿）
- [x] API Key 自动轮换执行端：`auto_rotate`/`rotation_days` 死字段接活（V0.9.27 `last_rotated_at`、每小时扫描换发 sk_、轮换写审计）（68ce564，10 例绿，模块 1998 例 0 失败）
- [x] 文档对账勾账：operations 清单「限流」改判已实现（`RateLimitFilter` per-key `rpm` + per-IP `ipRpm` 超限 429 + `Retry-After`）（e9ebbac，网关 237 例 0 失败）
- [x] DR 缺口入库：postgres WAL 归档（archive_mode=on 等四参数 + 30 天就地留存）、Redis 快照+AOF、ClickHouse 全量/增量备份脚本（90 天留存）（39e2a1c，纯配置，YAML 解析与 `docker compose config` 全过）
- [x] Redis 备份 CronJob 去 RWO 共挂：`redis-cli --rdb` 网络流式导出替代数据 PVC 挂载 + 7 天留存清理（6e4bf1d，纯配置，YAML 解析与 shell 语法校验过）
- [x] K8s 控制面清单镜像指真：control-service.yaml 改指 `ghcr.io/cuihairu/oddsmaker:nightly` + ConfigMap 显式 `SERVICE=control`，MODES.md K8s 形态行补 gateway（58e7b48，YAML 解析过）
- [x] K8s 部署文档对账：`deploy.k8s.md` 方式二改指 `deploy/k8s/` 清单，镜像段改述单镜像双服务 + `SERVICE`（665d630，docs:build 绿）
- [x] Web SDK 游戏事件 helper 出口接线：`game-events.ts` 经 `index.ts` 桶文件导出 + tsconfig `rewriteRelativeImportExtensions` + 桶文件导出断言（1009ecb，web 47 例 0 失败，docs:build 绿）
- [x] K8s 备份作业 aws 缺失掐断留存清理：上传分支加 `command -v aws` 判存降级，同修 postgres-backup.sh（LOG_FILE 不可写中止、pg_restore 校验形同虚设、死变量）（a0c1b9f，BackupScriptTest 5 例、control-service 167 suite/2003 例 0 失败）
- [x] 运维文档三处失效部署指令对账：monitoring 改述配置文件挂载语义、Helm 段改指仓内 chart `./infra/helm/oddsmaker`、`deploy/k8s/` 目录整体应用改逐清单（0550588/996780f/abccfe2，docs:build 绿）
- [x] BackupScriptTest 沙箱前提 CI 击穿修复：PATH 收敛为纯桩 bin（宿主目录不进 PATH），aws 天然不可见（4e99450，6 例 0 失败、control-service 2004 例 0 失败）
- [x] Nightly 镜像构建 Boot 4 双层失效修复：三 Dockerfile 统一 `gradle:9.8.1-jdk21` 与 wrapper 对齐；COPY 清单补 sdks/server 与 agents/，.dockerignore 窄反排除（28c3a20，docker build exit 0 并抽验镜像内双 jar）
- [x] Boot 4 拆包缺自动配置模块修复：补 `spring-boot-kafka` 与 `spring-boot-flyway` + 全量 context 启动烟测（8f2a898，全仓 2736 例 0 失败；真 PG 空库 65 迁移全落库、health UP）
- [x] Deploy Demo 健康预算与回滚重建缺陷修复：探活预算两段化、job timeout 15→30、回滚显式 `--force-recreate`（214167f，YAML 解析过）
- [x] Deploy Demo 假绿修复：`no-deploy` 哨兵 job，deploy 被跳过时显式变红，保证绿=真部署过且健康（6ccf1b6，实测 deploy 绿 + 哨兵 skipped）
- [x] Web 控制台测试纳入 push/PR 门禁：ci.yaml 补 web-console-tests job，与 nightly 同形同源（8435700，全量门禁实跑 gradle 2736/web 202/ml 68 例均 0 失败）
- [x] Java 测试门禁扩到全模块：bare `test` 覆盖 15 模块（此前其余 13 模块 493 例只在 nightly 跑）（f86f218，2736 例 0 失败、254 suite）
- [x] Release 流水线拆弹：走仓库 wrapper + bare `test` 全模块，与 ci/nightly 同源同口径（878dd48，零源码变更沿用 2736 例 0 失败；未打 tag 未触发发版）
- [x] Web SDK 测试门禁补全：`npm test` 三套全跑（test:game/test:src 此前 CI 从未执行）（1115c6b，test:src 47 例 0 失败）
- [x] Android SDK 测试纳入门禁：ci.yaml 增 android-sdk-tests job、nightly 补第五腿，均走根 wrapper + `-p`（b757f1d，冷树 70 例 0 失败）
- [x] 审计事件分类常量：`AuditEventType` 枚举 + `getEventType()` 派生 + resource-first metadata 落 details，修复 main 编译红（e8d870d，新增 18 例、control-service 169 suite/2019 例 0 失败）
- [x] Nightly Full Test Suite 口径补齐：补 ml 与 Web SDK 三套两腿，与 ci.yaml 同口径；web 步骤命名对正 Web Console（eaab3f5 + 351b690，全量四腿 0 失败）
- [x] 文档一致性审计台账回填：D8/F11 两行「已修」记录被后续批次打反，改「先标 X；2026-10-15 已落地」+ 表头现状更新块（a0f3408，docs:build 绿）
- [x] 审计 API 事件分类出参对账：`AuditLogEntityJsonTest` 钉序列化契约，control.md 审计段补全 14 GET + 1 DELETE 与权限列、eventType 判定表（c778133，control-service 170 suite/2021 例 0 失败）
- [x] docs 站点首页浏览器语言自动跳转：首页内联 VitePress script，仅站点根路径生效，手动选择优先于 navigator.languages（67d3cd8，结构计数不受影响，docs:build 绿）

## 边界（决定不做，勿再提）

- 多租户/SaaS：Organization/Workspace/Project/Tenant 概念不入新代码
- 不删 Gateway project_id 兼容层：删除破坏 v0 SDK 兼容
- 事件自由流动不做：dev 模式外未注册 schema 的事件默认拒收
- SDK 与业务层不持有物理路由：storage_profile 是唯一桥
- Data Lineage 系统化不做；移动端原生队列之外的新客户端端不做
- provider 多态与 auth.token_ref 密钥引用表不另行排期：inline credential 加 AES-GCM 已覆盖现有 provider 面（2026-10-11 拍板；R1015F 代决维持不动，用户可推翻）
- HarmonyOS SDK 不开工：绿地新建而非补缺，维持低优先级（2026-10-11 拍板；R1015F 代决维持 parked）
- 站内 IM 不做：webhook 加邮件已覆盖离线触达，IM 属产品级新方向留待用户
- unmerge 身份拆分不做：修正路径已齐（tombstone 反查、GDPR 擦除、再 merge 纠错）
- CF DNS 域名切换属用户侧操作，本仓不动
- P8 MMP 归因接入暂停：前置 MMP 原始数据导出权限未达成
- 旧评估完成度数字（80/80 等）不得再引用为现状

## 审核发现

- [x] 审核发现：T-CDC 行「parseDebezium 信封解析已单测」不成立（仅注释残留、无实现无测试）——表述已修正、注释已清（96953c3，dimension-sync-job 16 例 0 失败）
- [x] 审核发现：审核范围上界误记为移动 `..HEAD`——已钉死固定上界 41f2049（`git rev-list --count cce16e6..41f2049`=1）
- [x] 审核发现：审核范围误记 `772b1ff..HEAD` 与实际范围不一致——已更正固定上界
- [x] 审核发现：记录提交自身落入移动 HEAD 范围致 count 失真——已钉死上界并删移动 count 断言
- [x] 审核发现：T-DEMO502 引无源「10-27 GTS 读数」——已删，信任态记未核验
- [x] 审核发现：issuer/SAN/Verify return code 细节非仓内取证腿可产出——已删
- [x] 审核发现：T-ML 服务键「control」实为 `control-service`——已更正（deploy/demo/docker-compose.yml:64）
- [x] 审核发现：「取到 GTS 证书」不可由仓内工作流复现——已删
- [x] 审核发现：核实摘要 `--all` 计数随提交漂移（多起同类）——断言已钉死声明提交树，不再用移动口径
- [x] 审核发现：决策文档「2026-10-14 用户授权」无出处——已改「已代决」台账口径（docs/zh/决策记录.md:3）
- [x] 审核发现：纯中文决策文档置于英文 locale 根目录、无镜像——已迁 docs/zh/review/ + 英文 stub（与既有 review 系列同构）
- [x] 审核发现：「现仓」断言随提交漂移——已钉死声明提交树
- [x] 审核发现：`2026-10-30-decisions` 引用检索口径偏窄——已补全 docs/** 与全仓双口径
> 审核发现（不属实，已排除）：zh 决策文档第 18/31/44 行引用 stub 路径——不成立，该文档仅提 commit 号
- [x] 审核发现：架构图四 SDK 节点 id 与边 path id 重复——边 path 加 `-edge` 后缀（5bd4f1f，XML 解析过、id 30/30 唯一）
- [x] 审核发现：risk-redis 边 data-from 指向不存在节点、起点错位——改指 `flink`、起点移 Risk Detection 框底（5bd4f1f）
- [x] 审核发现：架构图「对象存储/归档」节点图示超前于实现——改述「原始事件导出 · 本地目录 · 运维同步」（5bd4f1f）
- [x] 审核发现：risk-redis 白色衬底路径未随边同步——两文件 halo 已同步 `M820 680`，几何重合（docs:build 绿）
- [x] 审核发现：UI 审计报告「34 路由」实为 32——已改（02ddf75）
- [x] 审核发现：「所有 37 张表格都套 overflow-x-auto」实有 5 张未套——已改口径（02ddf75）
- [x] 审核发现：P3-3「9 处遮罩」实为 18 处——已改（02ddf75）
- [x] 审核发现：「侧边栏 27 项扁平」实为 29 项含分组标签——已改（02ddf75）
- [x] 审核发现：MetricAlertBell 弹层漏修 Escape/role/aria-modal（第 19 处）——已补（85185f6，web 202 例全绿）
- [x] 审核发现：Escape 关闭行为未被测试实际覆盖——补 keydown 触发 + 关闭断言（e09a1be，spec 5 例绿）
- [x] 审核发现：124 行修复位置行号偏移——已更正 `MetricAlertBell.vue:20/:106`、`spec.js:41`
- [x] 审核发现：2026-11-01 复核重引不可复现 `s_client` subject=issuer 断言——已删
- [x] 审核发现：核实命令 `pnpm -C web vitest run web/tests/...` 前缀错误不可复现——已更正（web/vitest.config.js:16）
- [x] 审核发现：「本 commit 修复」覆盖范围误括 demo-diagnose.yml（实为背景引用）——已标注区分
- [x] 审核发现：spec 新增断言行号范围 42-45 实为 42-44——已更正
- [x] 审核发现：vitest.config include 行号 :12/:16 自相矛盾——统一 :16
> 审核发现（不属实，已排除）：16b06030..HEAD 无问题——不成立：0b09222 曾引入 README Docs 链接双斜杠回归（后续 6ca6625 修复）

- [x] 审核发现：docs/审计-文档一致性.md:245 称「all 11 `deploy/k8s/` manifests」被 operations/index.md:52-62 引用，但该段仅含 10 条 `kubectl apply`，且 deploy/k8s/ 下第 11 个文件是 `rollback-release.sh`（脚本而非 manifest），实际被引用的是 10 个 manifest——已更正为 10 个 manifest + rollback-release.sh 注（00c79bb）
- [x] 审核发现：docs/审计-文档一致性.md:247 称「RBAC with 8 roles」匹配 `V0.2.3__rbac_permissions_roles.sql:134-143`，但 134-143 行仅含 6 个 `role_*` 插入（role_operator/role_game_admin/role_analyst/role_marketing/role_finance/role_developer），8 个角色需到 134-147 行（含 role_viewer/role_qa）才完整——已更正为 :134-146（00c79bb）
- [x] 审核发现：docs/审计-文档一致性.md:253 称 todo.md「139 lines before」整理后减少，但整理前（8320be0）实际为 138 行、整理后（0033f76）为 127 行；「139」与实测 138 不符——已更正为 138（8320be0）→127（0033f76）（00c79bb）

- [x] 审核发现：docs/reference/environment-and-storage.md:295 称网关 ReplayGuard 为「Redis，入口幂等」，但 ReplayGuard 实为本地内存分代缓存（ReplayGuard.java:19-20「本地分代缓存…完整方案需接入共享 Redis」，gateway 全模块无 Redis 依赖），文档描述与实现不符（文档超前于码）。——已更正为「本地分代缓存（实例局部）」+ 注记共享 Redis 为未来硬化（HEAD，docs/reference/environment-and-storage.md:295）
- [x] 审核发现：docs/reference/environment-and-storage.md:335-336 称重复事件「走 DLQ 侧输出」且「DLQ 中 duplicate 计数是上游去重拦截量的直接读数」，但 events-enrich-job 仅 `validated.getSideOutput(DLQ)` 接 DLQ sink（EventsEnrichJob.java:158），dedup 流（DedupFunction 经 `ctx.output(dlq,…)` 输出，EventsEnrichJob.java:432）的侧输出未接线、被孤立，重复事件实际不进 DLQ（与 todo 已挂 T-REV04 同源），该观察语义主张不成立。——已更正为「dedup 侧输出未接线、duplicates_enrich 非去重读数（已知缺口 T-REV04）」（HEAD，docs/reference/environment-and-storage.md:335-339、EventsEnrichJob.java:158,432）
- [x] 审核发现：AvroPublisher.java:99-106 异步发送失败仅路由 DLQ、未释放幂等位；BatchController.java:277-281 的 `releaseEventId` 只在同步抛错 catch 触发，异步 broker 故障（P1-3 处理场景）时 `publish()` 正常返回、事件计 ACCEPTED 且位未释放，SDK 重试仍被 `consumeEventId` 吸收为 duplicate——P1-2/P1-3 声称「耦合一并修复」，实际异步失败路径仍留永久重复丢弃（docs/review/2026-11-01-code-review.md P1-2/P1-3 耦合说明与实现不符）。——已修复：失败回调内 `replayGuard.releaseEventId`（AvroPublisher.java:101），SDK 重试可重发；新测试 `PublishersTest.publishRoutesAsyncFailureToDlq` 断言位已释放（HEAD，AvroPublisher.java:99-106、PublishersTest.java:271-287）
- [x] 审核发现：AvroPublisher.java:98 注释及 docs/review/2026-11-01-code-review.md P1-3 称 DLQ 路由使失败「可观测、可恢复」，但仓内 DLQ 消费者仅计数不重投（DeadLetterConsumer.java 按 reason 计 duplicates_enrich/…，无回放/重投），DLQ 消息不会回到 events_raw，「可恢复」表述失实。——已更正：注释改「可观测」+ 明示 DLQ 不构成回放恢复；review 双语补「边界注」（HEAD，AvroPublisher.java:96-102、docs/review/2026-11-01-code-review.md:60、docs/zh/review/2026-11-01-code-review.md:60）
- [x] 审核发现：docs/review/2026-11-01-code-review.md:38（zh:38）P1-1 问题示例 URL `GET /events/{gameId}/export?environment=…` 与真实路由 `GET /api/games/{gameId}/events-export`（EventsExportController.java:55）不符。——已更正为 `POST /api/games/{gameId}/events-export`（HEAD，docs/review/2026-11-01-code-review.md:38、EventsExportController.java:44）
- [x] 审核发现：AvroPublisher.java:99-107 异步失败经回调路由 DLQ，但 DlqPublisher.publish 为 fire-and-forget（DlqPublisher.java:37，acks=1，`producer.send` 无回调/无失败日志），DLQ 发送失败时事件既不在 events_raw 也不在 DLQ、且无任何日志与指标——「可观测」主张在 DLQ 投递失败时也不成立（错误处理缺失）。——已修复：DlqPublisher.publish 加失败回调（error 日志 + `oddsmaker.dlq.publish.failures` 指标）；新测试 `PublishersTest.dlqPublishFailureIsObservable`（HEAD，DlqPublisher.java:32-38、PublishersTest.java:255-269）
- [x] 审核发现：EventsExportServiceTest.java:159 合法路径分支（导出 game_a/prod 后断言文件存在）依赖未显式 stub 的 `ch.query` 返回 Mockito 默认空 List（隐式默认值），一旦 ClickHouseClient.query 默认返回改为 null 或出错即抛 EXPORT_FAILED、断言静默失效；同文件兄弟测试（:74/:90/:118）均显式 `when(ch.query(...)).thenReturn(...)`，此处应显式 stub（测试健壮性/缺口）。——已修复：`:159` 前显式 `when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of())`（HEAD，EventsExportServiceTest.java:159）
- [x] 审核发现：docs/审计-文档一致性.md:316 新增「2026-11-02 独立复核」节称四个根目录自评产物（ASSESSMENT_INDEX.md、oddsmaker_completion_assessment.md、oddsmaker_quick_summary.md、oddsmaker_technical_recommendations.md）「unreferenced from README/docs」，但 docs/redesign/index.md:51-54 明列全部四个文件名（「根目录自评产物」清单），「未被 docs 引用」主张与仓内事实不符（正确性：文档审计结论失实）。——已更正为「列为历史材料（docs/redesign/index.md:51-54），非未引用」（HEAD，docs/审计-文档一致性.md:316）
- [x] 审核发现：docs/zh/审计-文档一致性.md:316 同节中文对应句称上述四产物「未被 README/docs 引用」，但 docs/zh/redesign/index.md:51-54 明列全部四个，同一失实主张在中文版复现（正确性：文档审计结论失实）。——已更正为「列为历史材料（docs/zh/redesign/index.md:51-54），非未引用」（HEAD，docs/zh/审计-文档一致性.md:316）

- [x] 审核发现：AvroPublisher.java:107 异步失败回调调用 replayGuard.releaseEventId，违反 ReplayGuard.releaseEventId 文档契约（ReplayGuard.java:103「仅当发布同步抛错（事件未进 Kafka）时调用」）：同步 catch 路径（BatchController.java:280）仅在 send() 同步抛错时到达（记录必然未投递），而异步回调在记录已交给 Kafka 且 delivery.timeout.ms（默认 120s，AvroPublisher 未覆盖）到期后触发——acks=all 下 TimeoutException 不保证未投递（broker 可能已提交但 ack 丢失/ISR 慢），故注释「与同步 catch 路径同口径」（AvroPublisher.java:101）失实；释放幂等位可令 SDK 重试重发可能已入 events_raw 的事件 → 重复写入，网关对原请求与重试各计一次 ACCEPTED（重复仅被下游 7 天去重吸收）。——已修复：异步失败回调不再释放幂等位，注释改述异步/同步差异；新增成功回调不释放断言（HEAD，services/gateway-service/src/main/java/io/oddsmaker/gateway/kafka/AvroPublisher.java:96-113、services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersTest.java:302-318）
- [x] 审核发现：PublishersTest.java:230 @DisplayName「DlqPublisher：broker 不可达时 send() 挂满 max.block.ms 后静默吞掉失败（future 被忽略）」与本 diff 新增的失败回调（log.error + oddsmaker.dlq.publish.failures 指标）矛盾——失败已非「完全静默」（内联注释 :236 也改口「不再是完全静默」），DisplayName 未同步更新，测试名与行为漂移。——已修复：DisplayName 改述「失败经回调日志+指标观测（非完全静默）」（HEAD，services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersTest.java:230）
- [x] 审核发现：docs/reference/environment-and-storage.md:296,337-339 已更正 EN 版（ReplayGuard→「instance-local（共享 Redis 为未来硬化）」；「side output is not wired to the DLQ sink」「duplicates_enrich 非上游去重读数（已知缺口 T-REV04）」），但 zh 兄弟页未同步：docs/zh/reference/environment-and-storage.md:294 仍称「网关 ReplayGuard（Redis，入口幂等）」、:328-330 仍称「重复事件走 DLQ 侧输出…duplicate 计数是上游去重拦截量的直接读数」——EN/zh 文档一致性被打破（todo.md:164-165「已更正」仅引 EN 文件、未覆盖 zh，zh 仍载已否定的描述）。——已修复：zh 页 ReplayGuard 改述「本地分代缓存…共享 Redis 属后续硬化」、观测口径改述「侧输出未接 DLQ sink（已知缺口 T-REV04）」（HEAD，docs/zh/reference/environment-and-storage.md:294、:328-330）
- [x] 审核发现：AvroPublisher.java:108 异步失败回调以 reason「kafka_send_failed」写 DLQ，但该值不在 DeadLetterConsumer.GATEWAY_REASONS（DeadLetterConsumer.java:37-40，含「invalid_schema/unknown_event/…/api_key_scope_mismatch/kafka_error」，无 kafka_send_failed），故此类异步失败消息落入 dlq_other 兜底桶（:64 不在集合内→:74-77 计 other，而非被 :65 当作网关拒绝跳过）；同步失败路径用「kafka_error」（BatchController.java:281，在 GATEWAY_REASONS 内→:65 跳过双计）——同一次 broker 故障在同步/异步两路径被不同记账：异步路径事件已计 ACCEPTED（BatchController.java:271，publish() 正常返回），失败仅落 dlq_other，DataQualityController.java:43「kafka_error 为平台故障单列」漏掉异步故障（异步 kafka 故障不被单列为平台故障）。——已修复：异步回调 reason 统一为 kafka_error（在 GATEWAY_REASONS 内→跳过双计），DeadLetterConsumerTest 增 kafka_error 跳过双计断言（HEAD，services/gateway-service/src/main/java/io/oddsmaker/gateway/kafka/AvroPublisher.java:108、services/control-service/src/test/java/io/oddsmaker/control/service/DeadLetterConsumerTest.java:78-85）
- [x] 审核发现：测试缺口：PublishersTest 仅覆盖失败回调释放幂等位（publishRoutesAsyncFailureToDlq，:273-287，断言 consumeEventId 复真），未断言成功回调（exception==null）不释放（ReplayGuard.java:103「成功路径不释放」契约），亦未覆盖 DlqPublisher 成功路径不增 oddsmaker.dlq.publish.failures（dlqPublishFailureIsObservable，:257-269 仅断言失败路径计数=1）；PublishersBrokerE2ETest 仅更新构造点（:201,238,277），无端到端释放/指标断言——成功路径的幂等位保留与 DLQ 成功投递零失败计数均无测试守护。——已修复：新增 publishSuccessDoesNotReleaseIdempotencyBit（成功回调不释放+不写 DLQ）、dlqPublishSuccessDoesNotIncrementFailureMetric（成功投递零失败指标）；构造点同步 2 参（HEAD，services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersTest.java:302-318、:286-299；PublishersBrokerE2ETest.java:238、:277）
- [x] 审核发现：8a92563 自建 exporter 镜像以「供应链治理」替代个人维护者 Docker Hub 镜像，但上游源码仅以可变 tag 拉取（infra/exporters/Dockerfile.kafka-exporter:18、Dockerfile.redis-exporter:18 `git clone --depth 1 --branch "${…_VERSION}"`），未固定 commit SHA；基础镜像已钉 digest（golang:1.22-alpine@sha256:1699…、alpine:3.20@sha256:d9e8…），而被治理的核心对象——上游应用源码——每次构建在线重新解析 tag，tag 被移动或上游仓库/账号被攻陷时构建产物静默变化且无任何校验——与「版本冻结/供应链治理」主张不一致（冻结的是 tag 名而非不可变 commit，属未完成的硬化）。——已修复：两 Dockerfile 增 *_COMMIT ARG 并改 fetch 指定 SHA（kafka 7e840e81、redis e9871c14），compose 传参；docker build 实测 exit 0，镜像内 --version 输出 revision 即钉定 SHA（HEAD，infra/exporters/Dockerfile.kafka-exporter:13-20、Dockerfile.redis-exporter:13-20、infra/docker-compose.yml:136、:151）
- [x] 审核发现：8a92563 两 Dockerfile 运行时层硬化不对称：redis 运行时显式装 ca-certificates（Dockerfile.redis-exporter:31-33，注「rediss:// TLS 时需要」），kafka 运行时仅 adduser（Dockerfile.kafka-exporter:33；构建层 :15 的 apk add ca-certificates 不进入运行层），但 kafka_exporter 的 --tls.enabled/--server.tls.enabled 同样需 CA 证书且 alpine:3.20 基础镜像默认不含——配对变更中一处防 TLS、一处未防（潜在缺口；当前 compose 走明文 --kafka.server=…:29092 故未即爆）。——已修复：kafka 运行层补 apk add ca-certificates（与 redis 对称），镜像内 /etc/ssl/certs/ca-certificates.crt 存在（HEAD，infra/exporters/Dockerfile.kafka-exporter:31-33）

- [x] 审核发现：异步失败回调改以 reason「kafka_error」写 DLQ（AvroPublisher.java:106），但该值在 DeadLetterConsumer.GATEWAY_REASONS 内（:37-40）→:64-67 跳过；而异步路径从未经 BatchController.reject()——publish() 正常返回、事件仅计 ACCEPTED（BatchController.java:269-271），DLQ 跳过假设「网关已计 rejected_kafka_error」（DeadLetterConsumer.java:65 注释）对异步消息不成立。结果：异步投递失败不落任何计数（既非 rejected_kafka_error 亦非 dlq_other），此前「kafka_send_failed→dlq_other」尚可见；todo.md:177 声称「kafka_error 为平台故障单列/异步 kafka 故障不被单列为平台故障」的修复目标未达成，反而把异步故障从 dlq_other 降为完全不可见（观测回归，DataQualityController.java:43「kafka_error 平台故障单列」对异步故障仍失效）。——已修复：异步回调改回专用 reason「kafka_send_failed」（避开 GATEWAY_REASONS 防双计跳过集），consumer 按 dlq_other 兜底计入（异步路径已计 accepted，落 dlq_other 而非 rejected_kafka_error 才不破坏恒等式），异步故障重新可见；新增 DeadLetterConsumerTest.kafkaSendFailedCountsToDlqOther 与 PublishersTest 断言更新（d32eb6c，全仓 gradle 2926 例 0 失败；AvroPublisher.java:107、PublishersTest.java:302、DeadLetterConsumerTest.java:96-100）

- [x] 审核发现：docs/review/2026-11-01-code-review.md:60 与 docs/zh/review/2026-11-01-code-review.md:60（P1-3「已修」节）仍称异步失败回调「路由至 dlq.publish(eventId, "kafka_send_failed", json)」且「回调同时释放幂等位（ReplayGuard.releaseEventId），SDK 重试可重新占用并重发」，但本提交已改回调写「kafka_error」（AvroPublisher.java:106）且不再释放幂等位（:99-100）——review 文档描述与当前代码矛盾，读者会被误导（文档-代码漂移，EN/zh 双页同病）。——已修复：EN/zh :60/:62/:64 更正为异步回调不释放幂等位（同步路径仍释放）、成功回调不释放不写 DLQ、kafka_send_failed 计入 dlq_other（HEAD，docs/review/2026-11-01-code-review.md:60、:62、:64；docs/zh/review/2026-11-01-code-review.md:60、:62、:64；纯文档更正，aa01366）

- [x] 审核发现：Dockerfile.kafka-exporter:13 的 KAFKA_EXPORTER_VERSION ARG 在改 commit-SHA 拉取后已不被构建使用（build ldflags 改读源码树 VERSION 文件，:30 `cat VERSION`），但 infra/docker-compose.yml:152 仍传 KAFKA_EXPORTER_VERSION:v1.7.0（对 kafka 无效果）——与 redis 侧不对称（Dockerfile.redis-exporter:29 仍用 REDIS_EXPORTER_VERSION 注入 main.BuildVersion），改 kafka 版本 ARG 静默无效、且易被误当有效；头部注释 :3「版本冻结在上游 tag v1.7.0（与 compose 引用同一注记）」已失实（实际冻结在不可变 commit 7e840e8，:14），「改版本只动 ARG」指引也随失效。（infra/exporters/Dockerfile.kafka-exporter:13、:3；infra/docker-compose.yml:152）；证据：Dockerfile.kafka-exporter:30 注入 KAFKA_EXPORTER_VERSION，docker build + run --version 实测 v9.9.9 生效（revision 7e840e8）

- [x] 审核发现：PublishersTest 以裸 `new ObjectMapper()`（默认 camelCase）构造 AvroPublisher（services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersTest.java:64），与生产 `JacksonConfig` 注入的 SNAKE_CASE 策略不一致（services/gateway-service/src/main/java/io/oddsmaker/gateway/config/JacksonConfig.java:17）；本轮将异步 DLQ reason 改为 `kafka_send_failed`（services/gateway-service/src/main/java/io/oddsmaker/gateway/kafka/AvroPublisher.java:107），使 DeadLetterConsumer 必须解析 `raw.game_id`/`raw.environment`/`raw.ts_server` 才能按作用域/窗口正确分桶（services/control-service/src/main/java/io/oddsmaker/control/service/DeadLetterConsumer.java:70-74），但 `publishRoutesAsyncFailureToDlq` 仅断言 payload 含 `"reason":"kafka_send_failed"`（services/gateway-service/src/test/java/io/oddsmaker/gateway/kafka/PublishersTest.java:299-303）、未校验真实 SNAKE_CASE 序列化下 raw 字段名——测试 camelCase 序列化与生产 SNAKE_CASE 不一致，若生产 ObjectMapper 误配则 consumer 会落 `unknown` 桶而测试不报（生产当前配置正确，属测试保真度/契约缺口，本轮改动使其成为实际风险点）。；证据：PublishersTest.java:64-66 SNAKE_CASE ObjectMapper，:300-305 断言 raw 字段 snake_case 名，gateway 248 tests 0 fail
- [x] 审核发现：SSRF 防护未接线——DimensionPullService 构造函数新注入 `allowPrivateNetworks`（:84/:94/:103）但从不使用（死字段）；`fetchPage` 对用户配置的 `config.endpoint` 经 RestTemplate.exchange 抓取（:305）仅过 `requireEndpoint` 的 scheme 前缀检查（:616-621），未走 `UrlSecurityValidator.validateHttpUrl` 的私网/环回/链路本地拦截——与同批改的 IntegrationService（:191）/WebhookService（:348）形成不一致，维度拉取端点可指向云元数据等内网地址（SSRF 缺口，修复不完整）（services/control-service/src/main/java/io/oddsmaker/control/service/DimensionPullService.java:84,94,103,305,616-621）。——已修复：`requireEndpoint` 改经 `UrlSecurityValidator.validateHttpUrl`（私网/环回/链路本地拦截，`allowPrivateNetworks` 显式豁免），`fetchPage` 拉取前再校验一次（配置可来自旧库/导入路径，深度防御）（DimensionPullService.java:619-625/:303-305；control-service 2124 tests 0 fail；HEAD）
- [x] 审核发现：UrlSecurityValidator 私网判定有可绕过盲区——`isPrivateOrLoopback` 依赖 `InetAddress.isSiteLocalAddress()`，但 Java 对 IPv6 唯一本地地址 fc00::/7 返回 false（实测 `[fd00::1]` siteLocal=false），且 CGNAT 100.64.0.0/10 仅用 `startsWith("100.64.")` 覆盖 100.64.0.0–100.64.255.255（100.65–100.127.255.255 漏检，实测 100.65.0.1 全 false）——`http://[fd00::1]:8080/` 与 `http://100.65.0.1/` 均可通过校验（安全控制绕过）；另 `InetAddress.getByName` 在验证时解析、实际请求时再解析，存在 DNS rebinding TOCTOU（services/control-service/src/main/java/io/oddsmaker/control/security/UrlSecurityValidator.java:46-50）。——已修复：`isPrivateOrLoopback` 改为按 RFC 4291/4193/6598 字节判定（IPv4 10/8、172.16/12、192.168/16、127/8、169.254/16、100.64.0.0/10；IPv6 ::1、fc00::/7、fe80::/10、fec0::/10），`http://[fd00::1]`/`http://100.65.0.1/` 现均拒绝；DNS rebinding TOCTOU 残余（校验与实际请求各解析一次）保留为已知边界并注释（UrlSecurityValidator.java:40-113；control-service 2124 tests 0 fail；HEAD）
- [x] 审核发现：跨游戏批次封禁 fail-open——封禁检查以 `eventsToPublish.get(0).gameId` 为唯一游戏键发起 `batchCheck`（BatchController.java:244），而 `isBlocked` 按每事件 `event.gameId` 拼接查询键（:310-312）；非游戏作用域（unscoped）API key 下发、单个 /v1/batch 含多 gameId 事件时，非首事件游戏的事件其键不在返回 map 中→跳过封禁检查（放行，fail-open）——T-REV10 跨游戏隔离修复对混合游戏批次不成立（改键前按 target 无游戏前缀至少会评估，改后直接漏检，方向由误封转漏封）（services/gateway-service/src/main/java/io/oddsmaker/gateway/api/BatchController.java:244,310-312）。——已修复：封禁目标改按 `event.gameId` 分组，每个游戏各发一次 `batchCheck(gameId, targets)` 后合并结果（键含 gameId），混合游戏批次下各游戏事件均按自身键评估，不再漏检；新增混合游戏批次回归测试（BatchController.java:244-269；BatchControllerCoverageTest.mixedGameBatchChecksEachGame；gateway-service 249 tests 0 fail；HEAD）
- [x] 审核发现：RetentionJob 队列校正不彻底——`day < first` 时重置 first、清空 seen_d_*/seen_r_*、只补发新 cohort 的 d=0 行（RetentionJob.java:215-236），但此前已按旧 first 发出到 sink 的 N-Day/Rolling 行不撤销（同一 subject 在新旧两个 cohort 被重复计数，下游无重算/回撤），且 newFirst 与旧 last 之间已流逝日期的 N-Day/Rolling 不会回填（仅靠未来事件重触发），校正后 cohort 欠计数；`last` 亦不随 first 前移重算（jobs/flink/retention-job/src/main/java/io/oddsmaker/jobs/retention/RetentionJob.java:215-236）。——部分修复：`last` 随 first 前移回退到 newFirst（此前残留旧 last，新 cohort 下的早 Rolling 阈值漏记），校正后后续事件按新 first 重算并补记 Rolling；旧 cohort 已发 N-Day/Rolling 行无法在本 sink 撤销——SummingMergeTree users 为 UInt64 不支持负数，完整撤回需带 generation/token 表结构与 REPLACE/增量列（后续独立改造，已注释）。新增校正后 Rolling 补记断言（RetentionJob.java:218-236；RetentionJobTest.lateEarlierEventCorrectsCohort；retention-job 22 tests 0 fail；HEAD）
- [x] 审核发现：Flink 键编码格式变更使既有 keyed 状态失效——funnelKey/retentionKey/identityKey/scoreKey/subjectWindowKey/receiptWindowKey/ipWindowKey/FeatureRow.stateKey 由 `a|b|c` 改长度前缀（KeyCodec.join），部署从 savepoint/checkpoint 恢复后旧格式键全部不可达：窗口累加器、漏斗推进、留存基线、广播特征行（无 TTL）成为不可达残留，各作业静默重置/泄漏；diff 未提供状态迁移或兼容处理，亦未在 docs 注明（jobs/flink/funnels-job/.../FunnelsJob.java:123、ConfigurableFunnelsJob.java:159、RetentionJob.java:125、IdentityMergeJob.java:142、FeatureRow.java:37、RiskJob.java:425,924,990,995）。【不属实：当前部署形态为 local executor（非 Flink 集群），作业状态仅存进程内、无外置状态后端/HA 管理器（deploy/MODES.md DEPLOY-002，容器重启 checkpoint 不可恢复、keyed state 全空），不存在可从 savepoint/checkpoint 恢复的旧格式键——键格式变更不产生遗留状态。】
- [x] 审核发现：测试缺口——UrlSecurityValidatorTest 仅覆盖 IPv4 私网/环回/链路本地与 scheme（127.0.0.1/10.0.0.5/192.168.1.1/169.254.1.1/ftp/file），未覆盖 IPv6（[::1]/[fd00::1]/[fe80::1]）与 CGNAT 100.64/10（100.65.x），上述绕过路径无测试守护（services/control-service/src/test/java/io/oddsmaker/control/security/UrlSecurityValidatorTest.java:22-38）。——已修复：新增 IPv6 环回/唯一本地/链路本地、CGNAT 100.64/10 全段（100.64–100.127 边界内外）、172.16/12 与字节判定直测用例（UrlSecurityValidatorTest.rejectsIpv6PrivateTargets/rejectsCgnatRange/rejectsIpv4AdditionalPrivateSegments/isPrivateOrLoopbackBytes；control-service 2124 tests 0 fail；HEAD）
- [x] 审核发现：测试缺口——EventQueueTest 注释/名称称「diskEvents 保持准确（T-REV20）」，但 `diskEvents` 为私有且无访问器，测试仅断言 take() 内容与 isEmpty()，从未断言 diskEvents 或 enforceDiskCap 行为，`diskEvents += rest.size()`（EventQueue.java:167）回归无法被该测试察觉（sdks/server/src/test/java/io/oddsmaker/sdk/EventQueueTest.java:22-47）。——已修复：新增 `diskEventCount()` 访问器，测试经真实溢写路径断言 diskEvents 计数（enqueue 溢写加计、take 读走减计）与 enforceDiskCap 删最旧压回上限（EventQueue.java:115-118；EventQueueTest.diskEventsCountsThroughRealSpillPath/diskCapTrimsOldest；sdks:server 11 tests 0 fail；HEAD）
- [x] 审核发现：RiskJob.jsonEscape 仅转义反斜杠与双引号，未转义控制字符（换行/制表/CR）——evidence key/value 或 reason 含原始控制字符时产出非法 JSON（字符串字面量内裸 \n），下游解析失败；新测试仅覆盖引号转义，控制字符无覆盖（jobs/flink/risk-job/src/main/java/io/oddsmaker/jobs/risk/RiskJob.java:939-941）。——已修复：jsonEscape 改为逐字符转义，反斜杠/引号/换行/回车/制表/退格/换页与其余 <0x20 控制字符（\uXXXX）全覆盖；新增控制字符转义直测（RiskJob.java:939-962；RiskJobTest.jsonEscapeControlChars；risk-job 101 tests 0 fail；HEAD）
