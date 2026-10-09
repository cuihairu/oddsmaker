# TODO（执行跟踪）

当前跟踪：**Oddsmaker 2.0 重构批次（B1~B10）**，契约 = `docs/zh/redesign/06-od2-restructure-plan.md`。
按批次顺序执行；每批完成后勾选任务并回填验收结论（全绿才提交）。

> **旧清单作废（2026-10-04）**：P0~P7 与覆盖率巡检等 80 项已全闭环，其中完成度数字（80/80、98.x%、Web 9 个 spec 等）均属**归档快照**，不再作为跟踪表与任何排期依据（历史留存在 git 与旧评估文件横幅中，口径见 06 计划书 §1）。

## B1 产品定位与文档对齐（零代码）

- [x] GitHub 仓库 description 更新为「Game Intelligence Platform」一句话定位（`gh repo edit`，单公司多游戏多环境 + 分析/实验/风控内建）
- [x] `oddsmaker_completion_assessment.md` 补 2026-10 归档横幅（同 technical_recommendations 口径），PIT→Oddsmaker（历史代号 PIT）、管理项目→管理游戏（0f8617a）
- [x] `oddsmaker_technical_recommendations.md` / `oddsmaker_quick_summary.md` 横幅中「80/80 全闭环」父句改指 2.0 批次跟踪（防陈旧，4a86775）
- [x] `docs/zh/redesign/index.md` 文档结构表补 06 行（568ffcb）

**验收：** ✅ description/横幅×3/index 四处就位（2026-10-04 核对：gh description 查询回显新定位；三份旧评估横幅口径一致指向 B1~B10 跟踪表；index.md 文档结构表含 06-od2-restructure-plan 行）；纯文档，未触发测试。

## B2 权限单真源收敛

- [x] 一次性回填迁移 `V0.9.16__...`：`user_role_assignments` 由 `users.roles` 回填（幂等）
- [x] `UserService.createUser` / `updateRoles`（UserService.java:241）改写 `user_role_assignments` 为唯一写路径；`users.roles` 降级为展示列（读多写少）
- [x] `deploy/demo/README.md §5` 删除手工 psql 兜底段，改口「角色分配随建号自动落表」
- [x] 真 PG 复现法验证：建号→分配角色→受权限门端点 200

**验收：** ✅（ace946f 后回填）全量 208 suite + web build 双绿；真 PG（postgres:16:15433 + bootRun SQL bind TRACE）验证：V0.9.16 迁移 54→v0.9.16 一次应用、回填跑两遍 INSERT 1 行→0 行（幂等，ADMIN 无 roles 行被 JOIN 滤掉不落哑行）；createUser 落 role_viewer 全局行（enabled、assigned_by=admin）；demo 登录受权限门端点 game:read 200 / audit:sensitive 403（B2 前无 assignment 行会双 403）；PUT roles→ANALYST 后 role_viewer 删、role_analyst 落；updateUser 携带 roles 同走同步；§5 psql 段已删、改口自动落表。

## B3 Server SDK 骨架（一等公民）

- [x] `sdks/server`（Java）：`Oddsmaker.Initialize/SetUser/Track/Flush` + Memory→Disk Queue→Batch→Gzip→HMAC（SERVER 型 key）
- [x] Gateway 验签：SERVER key 请求 200；伪造/过期 HMAC 401
- [x] SERVER 型 key 发放校验（game 未启用 server 事件能力拒绝创建）

**验收：** ✅（115b51f 后回填）全量 211 suite + web build 双绿；`sdks/server` 零依赖模块落地（subtree 可拆，自带独立 settings）：`OddsmakerIntegrationTest` 4 臂——内存溢出落盘+跨进程续传（close 时内存残留强制溢写，死 endpoint 下 close 不丢事件）、HMAC 正臂（JDK HttpServer 按 HmacFilter 同口径验签 200）、伪造签名负臂（secret 轮换→401 整批丢弃不重试）、5xx 整批退回止步恢复重发；`ControlServiceTest` +2（SERVER 发放拒绝/放行）。Gateway 正/负臂已有在仓测试（HmacSignatureWindowTest 200/过期 401/缺签名 401、HmacFilterCoverageTest 伪造 401、ReplayGuardRotationTest 重放 401），B3 未改网关代码。发放校验：`games.server_events_enabled`（V0.9.17 迁移）+ createKey SERVER 档位拒绝未启用游戏。

## B4 事件契约 v2 增量

- [x] 事件 schema 加 `event_version`（缺省 1）/ `source`（client|server|system|derived）/ `trust_level`（LOW|HIGH|COMPUTED，由 source 推导不可自抬）/ `event_origin`（SDK 名+版本）
- [x] `schema/json` 三枚 schema 文件同步 + Gateway `JsonSchemaValidator`/`PropsPolicy` 增量校验
- [x] Flink 各 job 对新增字段透传（不改 key）

**验收：** ✅（70c9929 后回填）全量 213 suite / 2456 用例 + web build 双绿；`EventContractV2Test` 钉验收三口径：v1 事件（不收 source）仍 200 且网关回填 client/LOW/event_version=1/event_origin=gateway；自抬拒绝——新增网关策略组件 `TrustPolicy`（config 包）按 key 档位推导：CLIENT key 声明 server 档、任何 key 声明保留档 system/derived、trust_level 高于推导档 → `trust_escalation` 整事件拒绝（不发布、不占幂等位）；source=client 时 trust_level 恒回填 LOW（声明 LOW 放行、声明 HIGH/COMPUTED 拒、server key 声明 LOW 静默纠正为 HIGH）；SERVER key 缺省回填 server/HIGH、保留 SDK 声明的 event_origin。Server SDK 自动声明 event_version=1/source=server/event_origin=server-java/VERSION（trust_level 不声明，由网关推导）。schema 出品同步：oddsmaker-event-schema.json（canonical + gateway classpath 副本）、game-events-schema.json（json+avro+clickhouse DDL 三形态）、schema/avro/oddsmaker-event.avsc（canonical+副本，四字段带 default 保 Avro 新旧读写兼容）——`schema/json` 第三枚 experiment-config.schema.json 无事件信封、归 B8 升级，本批未动；JsonSchemaValidator 增量 `minimum` 规则（event_version ≥1，0 → invalid_schema/below_minimum）+ 新字段 enum/maxLength；PropsPolicy 语义未动（props 白名单/字节上限不涉信任推导，网关增量校验=JsonSchemaValidator 规则 + TrustPolicy）。Flink 透传：RawEvent（七 job 共享 POJO）+4 字段（旧 schema 记录读 null 不抛，RawEventFromEdgeTest 钉），events-enrich-job INSERT 43→47 列落 ClickHouse（events/game_events 表 +4 列：schema.sql + 幂等迁移 `2026-10-event-contract-v2.sql`，`''`=契约 v2 前历史行，信任判定须精确匹配 `trust_level='HIGH'`）；路由键 game_id|environment 与 event_id 幂等键未动。

## B5 风控 Feature 层

- [x] 迁移 + `RiskFeatureEntity`：`risk_features`（game_id, environment, scope_key, feature_name, window, value, as_of）
- [x] `jobs/flink/risk-job` 特征作业分支：首批 6 特征（gold_gain_1h/24h、device_count、account_count_per_ip、win_rate…清单以计划书 §5.1 为准）
- [x] `RiskRuleEntity.ruleConditions` 改从特征取值（事件→特征→规则三段解耦）

**验收：** ✅（3c592f2 后回填）全量 gradle 测试 + web test/build 绿；V0.9.18 迁移（`risk_features` 六列唯一约束 + JDBC ON CONFLICT upsert）+ `RiskFeatureEntity`/`RiskFeatureRepo`（EntitiesSchemaAlignmentTest 自动对账列对齐）；risk-job 特征作业分支 SlidingEventTimeWindows 首 6 特征——gold_gain_1h（1h/5m）、gold_gain_24h（24h/30m）、device_count（DEVICE 口径）、account_count_per_ip（IP 口径）、win_rate（match:complete/:end 胜场比，无结果不产出）、event_count_10m（第 6 特征取事件计数，10m/1m）——窗口行真落 PostgreSQL control 库（watermark delay/特征开关入 config 槽）；三段解耦：新增 `ruleType=FEATURE`（实体枚举 + RuleFetcher 解析 `ruleConditions.features` + web 规则页色/标签），评估在 KeyedBroadcastProcessFunction 按特征广播快照逐条件全 AND 取值，无值不判真，IP 条件取本事件 IP 退化主体最近上报（keyed TTL 1 天兜底），命中 RiskHit 进既有 risk_events Kafka+ClickHouse 双 sink；既有 7 类事件规则与规则轮询原样保留，risk_scores/Decision/Action 段属 B6 未含。测试：RiskFeatureTest 16 例（真实 local env 窗口滑动：1h/5m 12 行错位、24h 48 行、跨桶拆分、IP/DEVICE 口径、win_rate；条件解析合法+8 非法；快照语义；命中证据形状；RuleFetcher FEATURE 合并；新 config 槽；JDBC 绑定对齐；特征开关接线）；§5.4 真 PG 端到端 RiskFeaturePgE2eTest（postgres:16 容器 + bootRun Flyway 全量迁移复现：2 条 600k 超阈值金币事件 → `risk_features` 1h 行值=600000 → FEATURE 规则命中；`-Drisk.pg.e2e=true` 显式门禁、CI 无 PG 自跳过；本地执行无 checkpoint 时跨 task partial buffer 无周期 flush，广播行到达有秒级抖动——评估载体排 3 连梯子拉长观测窗保证确定性）。

## B6 RiskScore 独立 + Decision 状态机

- [x] 评估产出 `risk_scores`（subject、累计分、规则明细 JSON）；Rule 静态 riskScore 降为“该规则最大贡献分”
- [x] `RiskCaseEntity.status` 状态机：`OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED`（流转合法表入测试）
- [x] Block 级动作要求输入事件 `trust_level=HIGH`（server 事件才能驱动）

**验收：** ✅（980ad8b 后回填）全量 gradle 测试 + web test/build 双绿；三箱落地：①risk_scores——risk-job `RiskScoreFunction` 按主体累计各规则最大贡献分（静态 riskScore 降为该规则最大贡献分，重复命中取高不叠加），每次评估落 CH `risk_scores`（7 列 ReplacingMergeTree 主体快照，明细 JSON `{"rule_id","contribution"}` 排序确定），risk_scores 归评估侧单写——`RiskActionRecorder.updateSubjectScore` 移除、收敛 risk_actions-only（判定型处置携 risk_case_id，非法判定流转归档 decision_rejected）；②状态机——V0.9.19 迁移 status 列+索引，DecisionStatus 分层前向 OPEN(0)→REVIEW/ALERT/MARK(1)→THROTTLE/BLOCK(2)→RESOLVED(3)，canTransition 严格升层+OPEN 不直达 RESOLVED，RiskEventConsumer Decision-first（decide 先落判定、非法流转拒绝执行动作仅归档、actionTaken=actionOf(decision)），`RiskDecisionStateMachineTest` 11 例覆盖**合法/非法流转各 ≥3**（分带、OPEN 全去向、一级→二级、判定后结案、OPEN↛RESOLVED、同层改判×2、回退与复活、null、transitionTo 执行表、completeReview 存量兼容）；③trust 门槛——risk-job union 后 `applyTrustGate`（Kafka/risk_events/risk_scores 三出口同一已定动作）+ control onRiskEvent 入口二次 gate（非 HIGH 的 BLOCK fail-closed 降级 REVIEW + `[trust_gate]` 标记），两侧同语义双保险。**§5.4 端到端实跑通**：`RiskScorePgE2eTest`（risk-job——2×600k 金币 → PG risk_features 1h 行 → FEATURE 命中 → trust 门槛 → CH risk_events 落行 + risk_scores 累计分 85/明细 JSON；RiskJob 双出口抽 sink 工厂与 buildPipeline 共用）+ `RiskDecisionPgE2eTest`（control——@SpringBootTest 全上下文 + postgres:16 容器空库 Flyway 57 迁移直跑 + CH 容器，直驱 onRiskEvent 钉四链：BLOCK+HIGH 判定落库+block_lists+BLOCK 审计+risk_actions 归档携 rc_ 案件 id；BLOCK+非 HIGH 降级 REVIEW 入审核队列不入名单；ALERT→THROTTLE 同案件 tier1→2 升层两次处置均归档；BLOCK 后 MARK 非法拒绝——案件保持 BLOCK、无审计、归档 decision_rejected），均 `-Drisk.pg.e2e=true` 显式门禁 CI 自跳过。测试增量：RiskEventConsumerTest 增 BLOCK 双门槛/非法流转拒绝（verifyNoInteractions 全处置依赖）/webhook 不建案；RiskActionRecorderTest/FinalSweep2Test 换 never 回写 risk_scores 断言；BranchTopUpBTest/ServicesFinalSweepTest/FinalSweep6Test 补 trust_level=HIGH 契约；两模块 build.gradle 补 risk.pg.e2e*/risk.ch.e2e.* 属性透传。**边界：risk_scores 只落 CH 暂无控制面读取端点；decision_rejected 不触发审计/通知（动作未执行）；e2e 事件引用主数据须真实存在（外键），空库环境行由测试 @BeforeEach 补种子**。

## B7 EventSchema 一等资源化

- [x] TrackingPlan→EventSchema 升级：补 compatibility / PII policy / retention / sampling / owner 字段与版本发布/兼容检查 API
- [x] `rejectUnknownEvents` 默认值改 true（随本批验收，dev 模式豁免）
- [x] web 控制台 schemas 资源组页面/入口

**验收：** ✅（88f99d7 后回填）全量 gradle 测试 + web test/build 双绿；三箱落地：①五字段与发布兼容门（686f700）——V0.9.20 补 `compatibility`（NONE/BACKWARD/FORWARD/FULL 默认 NONE）/`pii_policy`/`retention_days`/`sampling_rate`/`owner_id` 五列 + 实体/DTO 全链映射；`publishTrackingPlan` 兼容门=compatibility≠NONE 时先跑只读 `compatibilityCheck`（基线=同 game+同 environmentId、排除自身、最近 activatedAt 的 ACTIVE 未删除版；事件集 ACTIVE 定义按名 diff 三分类——BACKWARD 违例=removed 非空、FORWARD 违例=added 非空、changed=同名签名（类型/重要性/三必填位）变化仅信息项；NONE/无基线恒兼容），不兼容 IAE→400 且保持 DRAFT 不落库；`/api/games/{gameId}/schemas` 资源 API（SchemasController 全镜像 tracking-plans 子资源 + publish/compatibility/deactivate 子资源，无 AccessGuard 与现状对齐）+ `EventSchemaPublishTest` 10 例钉住（NONE 跳检查激活/BACKWARD removed 拒绝保持 DRAFT/仅新增通过/FORWARD added 拒绝/FULL 双向/changed 不阻塞/无基线/三分类/基线选择排除自身他环境取最近/五字段往返）；②未知事件默认拒收（6925770）——`rejectUnknownEvents` 实体默认 + toEntity 兜底 + V0.9.20 `SET DEFAULT TRUE`（既有行不动）；事件面下发=ControlService `toInternalDetail` 注入两仓取 ACTIVE Schema（环境绑定优先、回退全局、无则两字段 null）出 `rejectUnknownEvents`+`eventNames`，网关 ApiKeyContext JSON 透传，BatchController 校验链 api_key_scope 后插 `isUnknownEvent`（仅 scoped+开关 true 生效、dev 字面豁免、eventNames 空=全未知诚实语义）→ `unknown_event` 事件级拒收 + 检视面明细；`EventSchemaGatewayFeedTest` 5 例 + gateway 分支矩阵 + 端到端三例（未知拒收/事件面命中/dev 豁免）；③web 页面（88f99d7）——`/schemas` 路由 + 侧边导航"事件 Schema" + SchemasView（游戏选择联动、状态/兼容策略/拒收徽标列表、事件清单展开、兼容检查结果面板、发布/弃用/删除 confirm 门控），SchemasView.spec 13 例 + router 契约 28→29 同步；文档对账 plan §2.3/§9、control.md（Event Schema 节）、api.md（unknown_event）、CHANGELOG。**边界：compatibility 只 diff 事件名集与同名签名（属性字典级 diff 不在内）；EventSchema 与 TrackingPlan 同存储双前缀并存未拆表；事件面随网关 key 上下文 60s 缓存收敛；piiPolicy/retentionDays/samplingRate/ownerId 本批仅落库与展示无下游消费；feed 失联时网关沿用缓存/回退本地静态 key 即不启用拒收（fail-open）；dev 豁免按 key 上下文 environment 字面 'dev' 判定**。

## B8 实验平台形式化

- [x] `ExperimentEntity` 显式字段化：Audience（segment 引用）/ Guardrail / Decision（status ∈ {DRAFT, LIVE, PAUSED, ENDED} 枚举化）/ Variant 与 Allocation 从 configJson 提列
- [x] `experiment-config.schema.json` 同步升级、旧 configJson 向前兼容
- [x] `experiment.exposure` 列入平台事件清单（§4）

**验收：** ✅（7b2c1f6 后回填）全量 221 suite / 2531 用例绿（failures=0 errors=0）；Entity 字段化 `audienceSegmentId` + variants/allocationInfo/guardrails/decision 四字段 + `ExperimentStatus` 枚举，V0.9.21 迁移含存量 status 大小写归一（running/live 别名→LIVE）与 segments FK；schema 双份（canonical+resources）同步 allocation/guardrails/decision 三节，variants 旧 configJson 回退兼容（`createSyncsVariantsFromLegacyConfig`/`readFallsBackToLegacyConfigVariants` 钉）；exposure 列入 `04-redesign §4.1` 平台事件约定，曝光列经 `/api/experiments/{id}/results` 可查（计划书原写 /exposures 专端点未单设，口径已改）；Splitter 存量测试零改动，Aggregator 测试仅 findByStatus 签名 string→enum 适配 6 行（场景零改动）；audience/guardrail 创建与发布动作测试齐（本游戏 segment 落列+他游戏拒绝、guardrails/decision/allocation/variants 往返、publish DRAFT→LIVE、end LIVE→ENDED 单向终态）。接手收尾修复冻结现场遗留：compileTestJava 不过（4 测试方法缺 throws）；ObjectMapper @Mock 被注入产线致 mock 工厂方法返回 null（6 例 NPE，改 @Spy 真实实例）；GameEnvironmentRepo 同类型双 @Mock 构造注入歧义（合并单 mock）；`createAudienceSegment` save 校验 never→times(1)；环境桩 anyString→any（null 不匹配）；过期用例 status "live"→"archived"（live 已为合法别名）；ResultsController status 输出枚举名；清除无调用方的 `mapStatusToEnum`。

## B9 运行模式矩阵

- [x] `deploy/MODES.md`：Lite / Standard / Production 三档组件矩阵（对齐现有三套编排）
- [x] `deploy/demo` 编排注明「本档 = Lite」；quickstart = Standard；infra = Production

**验收：** ✅ 纯文档批（不触发测试）；`deploy/MODES.md` 三档矩阵逐格读码核对四处编排实际 service 定义（demo 三服务 / quickstart 九服务 / 根 compose 九服务+Flink×7 / infra 七服务），四处编排头注标记「本档 = ×」并经 `docker compose config --quiet` 四文件全过；与计划书 §7 的一处对账修正已回写计划书（infra 并非"全量"单档，Production 全组件=根 compose 业务栈 ∥ infra 观测/中间件面两件套；对象存储归档不在任何 compose 走 `deploy/backup`+k8s cronjob；Standard 档无 Flink 作业、CH 有 schema 无数据已如实入矩阵）。

## B10 Data Quality 与共享特征（收口批）

- [x] `event_valid_rate / drop_rate / unknown_event_rate / duplicate_rate / late_event_rate` 指标落库 + Game Data Health 页
- [x] 共享 Feature 双写 schema 定稿（risk_features + feature_store 摘要表）——**Data Lineage 仅记方向不建系统**

**验收：** ✅（8eba96c 后回填）全量 228 suite / 2551 用例绿（failures=0 errors=0）+ web build 绿 + docs build 绿。分五批落地：设计定稿（b782201，五指标口径/恒等式/落库 DDL/feature_store schema/Lineage 方向表）→ control 落库与取数面（ffab91c，V0.9.22 双表迁移 + DataQualityService 摄取/序列/汇总 + /internal/data-quality 摄取端点 + /api/data-quality 与 /api/feature-store 只读端点，恒等式 received = accepted + Σrejected + sampled_out 落表校验破式告警不拒收，率一律读时算）→ 网关计数与上报（3316d73，BatchController 全路径埋点 14 维计数（缺作用域落 unknown 桶）、幂等吸收占 accepted 位使恒等式在网关边缘即守恒、60s 快照 POST internal-token 同通道、payload 键集与 control Snapshot 逐字段对账测试）→ DLQ 消费者（e43901a，闭合 deadletter 无消费者缺口，防双计口径：duplicate→duplicates_enrich（仅 enrich 产生）/网关 9 拒绝 reason 跳过（埋点已计）/未知→dlq_other；enrich 两列列所有权归 DLQ consumer，网关快照覆盖保留既有值）→ risk-job 双写（4cc8c29，featureRows 流第二 sink，长格式行经 jsonb 合并聚行，合并语义一次性 postgres:16 容器实测）→ 健康页（8eba96c，五率卡片/恒等式警示条/rejectTop（kafka_error 单列平台故障）/窗口序列/feature_store 取数）。设计文档对账四处已回写 07-b10（列所有权/防双计口径/TEXT 替代 JSONB 与升级路径/双写机制实况）。Lineage 仅 §6 方向表，无代码。

---

## 挂起项拍板（2026-10-09 首拍，2026-10-10 巡检复核，依据 = 成熟产品调研 §5/§6/§7 与既有批次边界）

> B1~B10 清零后的四项挂起逐项定夺；2026-10-10 巡检复核：「样本集上传+批量回放」改为做（V0.3 试算回放），其余维持原判；同日巡检点火令续判：样本集持久化落地为 V0.4。

- [x] **策略实验室：做（V0.2 复盘聚合已落地）**——依据调研 §7「可借鉴」行（规则上线后第一诉求是复盘，误杀回看已落地、实验室仍为增量）。增量：`GET /api/games/{gameId}/risk-lab/rule-stats` 规则复盘聚合（误杀率分母=已处置、平均复盘时长、零案例/孤儿规则行）+ 案例列表 `ruleId`/`disposition` 样本下钻 + 控制台 `/risk-lab` 页。
- [x] **样本集上传+批量回放打分：做（2026-10-10 复核改判，V0.3 试算回放，本批开工）**——原判「回放执行器级新方向留待拍板」复核后收窄边界改为做：THRESHOLD（amount 严格大于阈值，`RiskJob.overThreshold` 纯函数）与 FEATURE（条件 op 对比全 AND，`RuleFetcher.parseFeatureConditions` 校验语义）均为逐事件纯函数，control-service 可同步试算，**不需要 Flink 执行器**；边界：FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD/PATTERN 依赖流式窗口/序列聚合，dry-run 不模拟（标 `needsStreaming` 明示跳过，不造假命中），ANOMALY/MACHINE_LEARNING 生产链路本就不评估（标 `notCovered`）；同 ruleType 只生效 riskScore 最高者（与 RuleFetcher 收敛一致），ruleResults 保留逐规则原始命中便于同型对比；samples 走请求体即传即算；样本集持久化（命名/留档/多次对比）同日巡检点火令再改判为做（V0.4，落点见本文件「策略实验室 V0.4」）。落点见本文件「策略实验室 V0.3」。
- [x] **站内 IM：不做（维持远期，复核无变化）**——依据调研 §7 结论「通道收敛落了 webhook+邮件双通道，站内 IM 为远期」；webhook（含按配置超时）+ 邮件已覆盖离线触达，IM 消息中心/未读已读是全新产品面，属产品级新方向**留待用户**，不排期。
- [x] **ML 异常检测：排期（条件触发，2026-10-10 复核把触发条件量化）**——满足任一即进评估批次，否则维持不排期：(a) 指标历史可稳定回看 ≥ 7 天且 7d/28d 分位基线可计算（`MetricAlertService` 基线对比已就绪，等数据积累）；(b) 单游戏阈值告警规则月均调整 ≥ 3 次（审计日志可查证，说明人工阈值维护成本高到需要自适应基线）；(c) 告警噪音率（复盘结论为误杀的占比）> 30% 持续 2 周。依据调研 §5「需要每指标历史基线训练与回填，成本前置」。
- [x] **unmerge/身份拆分：不做（维持 merge 批次边界）**——依据：merge 落地行边界即「无反向拆分」；修正路径已齐（tombstone `identity_id` 反查 + GDPR erasure + 再 merge 纠错），真 unmerge 需合并日志（计数已归并不可逆拆），产品语义级新方向不做。
- [x] **CF DNS 域名切换：用户侧不动（复核确认）**——属用户侧操作，本仓无动作。

## 策略实验室 V0.2（复盘聚合，2026-10-09 增量）

- [x] `RiskLabService.ruleStats`：规则维度聚合（案例/误杀/确认违规/证据不足/未复盘分桶、误杀率分母=已处置、平均复盘时长、零案例规则与孤儿规则行、案例数降序稳定排序）
- [x] `GET /api/games/{gameId}/risk-lab/rule-stats`（`game:read`）+ 案例列表 `ruleId`/`disposition` 后过滤（最近 2000 条窗口内内存过滤）
- [x] 控制台 `/risk-lab` 页（汇总卡 + 规则聚合表 + 点行下钻案例样本 + 处置 chips），路由契约 31→32
- [x] 文档对账：risk.md 端点表、调研 §5/§6/§7 落点行与本节

**验收：** ✅ 全量 gradle 236 suite / 2591 用例绿（failures=0 errors=0）+ web 165/165 + web/docs build 双绿。边界：聚合在每游戏最近 5000 条案例内进行；样本下钻过滤在最近 2000 条窗口内截断；不提供回放打分（2026-10-10 复核改判，见下节）。

## 策略实验室 V0.3（试算回放 dry-run，2026-10-10 增量）

- [x] `RiskLabReplayService.dryRun`：批量样本试算（THRESHOLD/FEATURE 逐事件评估，语义对齐 `RiskJob.overThreshold`/`FeatureCalc.matches`/`RuleFetcher.parseFeatureConditions`；FREQUENCY 等 6 类标 `needsStreaming`、ANOMALY/MACHINE_LEARNING 标 `notCovered`、条件非法标 `invalid` 均明示跳过；同 ruleType 生效规则按 riskScore 收敛，ruleResults 保留逐规则命中）
- [x] `POST /api/games/{gameId}/risk-lab/replay`（`game:read`；samples 1~500 条即传即算不落库，ruleIds 可选过滤）
- [x] 控制台 `/risk-lab` 页「试算回放」面板（JSON 编辑区 + 示例载入 + 试算按钮 + 规则命中表/样本结果表）
- [x] 文档对账：risk.md 端点行、调研 §7 样本管理行、挂起项拍板复核与本节

**验收：** ✅ 全量 gradle 237 suite / 2599 用例绿（failures=0 errors=0）+ web 168/168 + web/docs build 双绿。边界：THRESHOLD/FEATURE 逐事件纯函数试算，流式窗口类规则不模拟；样本不落库；样本集持久化随后落地为 V0.4（见下节）。
**实机走查（2026-10-10）：** ✅ postgres:16 一次性容器 + bootRun 全量 Flyway 迁移直连——API 层 curl 四样本（严格大于/等于不命中/FEATURE 全 AND/字符串金额/默认编号/needsStreaming 明示）逐项符合语义，空样本 400 文案透出、ruleIds 过滤、rule-stats 真库出 3 规则行；UI 层 playwright 实操 `/risk-lab`（登录→聚合表渲染→载入示例→试算→命中徽章/状态徽章/逐样本生效行），DEFAULT 游戏种子规则把 evaluable/needsStreaming/notCovered 三状态全 exercise 到，截图双张核对无误。

## 策略实验室 V0.4（样本集持久化，2026-10-10 增量）

- [x] `risk_sample_sets`（V0.9.24 迁移）：命名留档样本批次——game+name 唯一、samples JSON（1~500 条）、sample_count、created_by
- [x] `RiskSampleSetService`：create/list/get/delete——写入校验与 dry-run 抽公共（`RiskLabReplayService.validateSamples`），保证留档样本任何一次重放都能算；留档不可改、删除重建；create/delete 12 参审计
- [x] `RiskSampleSetController`：`GET/POST /api/games/{gameId}/risk-lab/sample-sets`、`GET/DELETE .../{id}`——读 `game:read`、写删 `risk:manage`（与解除封禁同权限位）
- [x] 控制台 `/risk-lab` 页「样本集」区：存为样本集（当前试算 JSON + 名称/描述）→ 列表（名称/样本数/描述/创建时间）→ 载入回填试算 JSON 改规则重放对比 → 删除（带确认）
- [x] 文档对账：risk.md 端点行与控制台页行、api-reference Risk Cases & Strategy Lab 节、调研 §7 样本管理行与小结行、CHANGELOG、挂起项拍板复核与本节

**验收：** ✅ 全量 gradle 239 suite / 2612 用例绿（failures=0 errors=0，基线 237/2599 + 样本集 service 9 例 + API 4 例）+ web 171/171（基线 168 + 样本集区 3 例）+ web/docs build 双绿。边界：留档不可改（删除重建）、校验与 dry-run 同款口径、读 `game:read`/写删 `risk:manage`。
**实机走查（2026-10-10）：** ✅ postgres:16 一次性容器 + bootRun 全量 Flyway 迁移直连——API 层九步全过：create（rss_ 前缀、字符串金额收编、createdBy=admin、createdAt 预置非空——`@CreationTimestamp` 落库正确但不回填内存值，响应需显式预置，仓内惯例同 `TrackingPlanService`）→ 同名 400 → 坏金额 400 文案与 dry-run 同款 → 列表元信息 → 详情解析 samples → 用留档样本 replay 命中 evaluable 规则 → 删除 → 列表空 → 重复删 `deleted:false`；UI 层 playwright 实操 `/risk-lab`（登录→存为样本集→列表渲染→载入回填 textarea→试算出命中→删除），截图四张核对无误。

## 主体累计风险分读取（B6 边界闭合，2026-10-10 增量）

- [x] `RiskScoreService.latest`：读 CH `risk_scores` 主体最新快照（`ORDER BY updated_at DESC LIMIT 1`，ReplacingMergeTree 合并前后都正确）；`reasons` JSON 数组解析为 `[{ruleId,contribution}]`（坏条目跳过、`java.sql.Array`/`List` 两形态兼容）；未落过分 `found:false`；CH 未配置抛 `CH_UNAVAILABLE`（BusinessException，与导出同款）
- [x] `GET /api/games/{gameId}/risk-scores?subjectType=&subjectId=`（`game:read`，subject 空白 400）
- [x] 文档对账：risk.md 端点行、api-reference Risk Cases & Strategy Lab 节、CHANGELOG 与本节（B6 验收行的「暂无读取端点」边界自此闭合，历史行不改写）

**验收：** ✅ 单测 7 例（快照解析/空行/CH 不可用/空白 subject/SQL Array 形态/坏条目跳过/鉴权委托）随全量门禁绿。

---

## 边界（不跟进，评审转审查项）

- 不做多租户/SaaS（Organization/Workspace/Project/Tenant 概念不入新代码）
- 不删 Gateway `project_id` 兼容层（已闭环项，删除破坏 v0 SDK 兼容）
- 不搞事件自由流动：dev 模式外未注册 schema 的事件默认拒收（B7 后）
- SDK/业务层不持有物理路由（storage_profile 是唯一桥）
- 不做 Data Lineage 系统化、不做新客户端端（移动端原生队列之外）
- P8 MMP 归因接入保持暂停（前置：MMP 原始数据导出权限，未达成，仅存调研文档）
- 旧评估完成度数字不得再引用为现状