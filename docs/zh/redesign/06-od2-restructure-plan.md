# 06 - Oddsmaker 2.0 重构计划书（Code Agent 可执行版）

> **身份**：本文是把 04-redesign（新架构）与 05-roadmap（实施路线）收束成一份**可直接交给 Code Agent 分批执行**的契约：
> 领域模型、模块边界、数据契约一次性定死，后续按 §8 批次逐批执行，每批附验收标准与门禁。
> 依据材料：本文 + `04-redesign.md` + `05-roadmap.md` + 根目录 `todo.md`（80/80 全部勾销即当时状态）。
> 编制日期：2026-10-04。涉及旧评估文件（`oddsmaker_completion_assessment.md` 等）的措辞与数字**一律视为归档快照，不构成现状**。

---

## 0. 执行约定（对 Code Agent 的要求）

1. **门禁**：任何包含代码的提交前，`services/` 全量测试 + `web` build 全绿；禁 tag/发版/force push；小问题自主修+推送。
2. **Schema 真源**：加字段/加表只动 `services/control-service/src/main/resources/db/migration/` 前缀新迁移（如 `V0.9.16__...sql`），实体对齐，**禁止改已发布迁移**。
3. **测试档盲区**：H2 测试 profile（create-drop + Flyway 关）看不见迁移约束——涉及 NOT NULL/DEFAULT/UNIQUE/类型漂移的改动必须真 schema 验证：`docker run -d --name oddsmaker-repro-pg -e POSTGRES_USER=oddsmaker -e POSTGRES_PASSWORD=oddsmaker -e POSTGRES_DB=oddsmaker -p 15433:5432 postgres:16-alpine`，然后 `DB_HOST=127.0.0.1 DB_PORT=15433 ./gradlew :services:control-service:bootRun --args='--logging.level.org.hibernate.SQL=DEBUG --logging.level.org.hibernate.orm.jdbc.bind=TRACE'`，curl 实测 SQL bind 定位撞约束字段，用完删容器（演示站本体别碰）。
4. **每个批次完成后**：回写根目录 `todo.md`（勾掉已完成项/新增条目），并在本文对应批次行首标 `[x]`。
5. **本文 §2–§7 是契约**：与现状不符处以本文为准；执行中发现本文与现实冲突，先记 `[OPEN]` 不改契约。

---

## 1. 产品定位（P0，零代码先行）

**一句话定位（全仓统一口径）**：

> Single-company, multi-game, multi-environment **Game Intelligence Platform** with built-in Analytics, Experimentation and Risk.
> 单公司部署的游戏智能数据平台：实时分析 + 实验平台 + 风控内建。

约束：

- 目标用户是**单个游戏公司的自建/私有化部署**，不做 SaaS 多租户（`04-redesign §1` 设计原则）。
- 禁止出现的概念名词：`Project`、`Workspace`、`Application`、`Tenant`、`Organization`（作多租户语义时）——统一 `Game`。`project_id` 仅存在于 Gateway 兼容层（见 §3.3）。
- 旧评估数字（完成度 80%、SDK 覆盖 90/85/80/60%、“12 周计划”）不作为任何排期与现状的依据。

**执行项（B1）**：

| 位置 | 现状 | 改法 |
|---|---|---|
| GitHub 仓库 description | “lightweight game analytics toolkit that turns gameplay events into actionable insights…”（与 README 定位冲突） | 改为上述一句话定位 |
| `oddsmaker_completion_assessment.md`（仓库根） | 无“历史快照”横幅，仍称 “PIT…管理项目/API密钥/实验” | 加与 `oddsmaker_technical_recommendations.md` 第 3 行同款横幅（2026-10 收尾归档，指向 todo.md），正文 Project 措辞改 Game 口径 |
| `docs/zh/redesign/index.md` | 文档结构表只到 05 | 补 06 行 |

---

## 2. 领域模型（一次性定死）

第一类对象共 13 个。每个：标识 / 归属边界 / 生命周期 / 关键不变式 / 现状落点 / 2.0 动作。

### 2.1 核心身份（已收敛，加固即可）

| 对象 | 标识 | 归属 | 关键不变式 | 现状 | 2.0 动作 |
|---|---|---|---|---|---|
| **Game** | `game_id`（小写 `[a-z0-9_]+`，≤64） | 全局唯一、创建后**不可变** | 平台上每个 game_id 恰存在一个 Game（含 SUNSET 态） | `GameEntity`（jpa 包） | 无结构性动作；补 `game_id` 校验单元测试 |
| **Environment** | `environment`：`dev/qa/staging/prod/loadtest` 五标准值 | `(game_id, environment)` 全局唯一 | 同一 game 内环境名不重复；环境必须挂一个 `storageProfileId` | `GameEnvironmentEntity`（含 `dataNamespace`/`kafkaTopicPrefix`/`databaseName` 直连物理字段） | **物理字段收敛**：`kafkaTopicPrefix`/`clickhouse...` 类直连字段移至 StorageProfile（见 2.2），Environment 只留 `storageProfileId` + 逻辑 `dataNamespace` |
| **StorageProfile** | `storage_profile_id` | 全局 | 业务/事件层**不持有** profile 内部物理细节；`isolationStrategy ∈ {SHARED, DEDICATED}` | `StorageProfileEntity`（IsolationStrategy.SHARED 已实现） | 成为环境物理路由的**唯一入口**；补“从 profile 解析出 kafka topic/CH 库名/归档桶”的单一解析器 |

### 2.2 网关与密钥

| 对象 | 标识 | 关键不变式 | 现状 | 2.0 动作 |
|---|---|---|---|---|
| **ApiKey（APIKey）** | `api_key`（public id）+ `secret` | `keyType ∈ {CLIENT, SERVER}`；**CLIENT 无 secret、无 HMAC；SERVER 持 secret 且为唯一 HMAC 持有方** | `ApiKeyEntity`（`ApiKeyType.CLIENT/SERVER`、propsAllowlist、piiEmail/piiPhone、rpm 限额）已具备 | Server SDK（§4.3）消费 SERVER 型 key；补 SERVER 型 key 的发放校验：game 未启用 server 事件能力时禁止创建 |

### 2.3 数据契约域（事件与 Schema）

| 对象 | 标识 | 关键不变式 | 现状 | 2.0 动作 |
|---|---|---|---|---|
| **Event** | `event_id`+`event_name`+`ts_client` 三元组；契约主键 `game_id+environment+event_id`（见 index.md） | 事件**不是控制面资源**（流对象），但契约定死，见 §4 | `schema/json/oddsmaker-event-schema.json`（v1，约 50 属性，无版本/来源/信任字段） | 按 §4 增量升 v2：`event_version`、`source`、`trust_level`、`event_origin` |
| **EventSchema** | `(game_id, event_name, major_version)` | 同一 event_name 只允许一个 ACTIVE 版本；版本兼容策略由 schema 指定 | `TrackingPlanEntity` + `EventDefinitionEntity`/`EventPropertyDefinitionEntity` 已具雏形（strictness/rejectUnknownEvents/enableAutoValidation） | ✅（B7 落地：tracking_plans 补 compatibility(NONE/BACKWARD/FORWARD/FULL)/pii_policy/retention_days/sampling_rate/owner_id 五列（V0.9.20）；publish 兼容门——compatibility≠NONE 时对同 game+env 最近 ACTIVE 基线做事件集 diff（BACKWARD 违例=removed 非空、FORWARD 违例=added 非空、changed 仅信息项），不兼容拒绝发布保持 DRAFT；`/api/games/{gameId}/schemas` 资源 API（publish/compatibility/deactivate 子资源，与 tracking-plans 同底层）+ web `/schemas` 页面） |

### 2.4 风控域（六层抽象，见 §5）

| 对象 | 标识 | 现状 | 2.0 动作 |
|---|---|---|---|
| **Signal** | 流水，非实体 | Gateway 校验后的原始事件即信号（`risk_context` 字段已入事件契约） | 显式定义信号清单（gold_gain/login_ip/device/…），不需要新表 |
| **Feature**（**新增**） | `(game_id, scope_key, feature_name)` 时间窗值 | ✅（B5 落地：`risk_features` 表 + risk-job 特征作业首 6 特征滑动窗口） | ANALYTICS 侧共享 `feature_store` 双写见 §5.3（B10） |
| **RiskRule** | `rule_id`，`environmentId` 可空（全局规则） | `RiskRuleEntity` 已具（THRESHOLD/SEQUENCE、riskScore、actionType、enableAutoBlock） | 收敛 `ruleConditions` 为结构化 JSON schema（引 EventSchema 字段），保持现状字段名 |
| **RiskScore** | `(game_id, subject, as_of)` 值对象 | ✅（B6 落地：risk-job 评估产出 CH `risk_scores`——主体累计各规则最大贡献分 + 规则触发明细 JSON，ReplacingMergeTree 主体快照） | **从 rule 提出**：一次评估产生一个累计分数记录（B6） |
| **Decision**（**新增**） | 判定 `(case_id → status)` | ✅（B6 落地：`RiskCaseEntity.status` 分层前向状态机，Decision-first 判定先行、非法流转拒绝执行动作） | B6：判定状态机 `OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED` |
| **Action** | 处置动作 | `RiskRuleEntity.ActionType`（ALERT/MARK/REVIEW/THROTTLE/BLOCK 语义已实现）+ BlockList/封禁 | 与 Decision 绑定（Decision 决定动作，不反推） |

### 2.5 实验域

| 对象 | 标识 | 现状 | 2.0 动作 |
|---|---|---|---|
| **Experiment** | `(game_id, environment_id, name)` | experiment 包已具：`ExperimentEntity`（id/name/status/salt/configJson）+ `ExperimentSplitter`（FNV-1a 分流）+ `ExperimentMetricsAggregator`（ClickHouse 按天归因，幂等回填 `experiment_metric_snapshots`：exposure_users/events_count/revenue）+ `ExperimentStatsService` | B8 补齐真缺件：Audience（Segment 引用）、Guardrail、Decision（status 枚举化）、Variant 显式化、Allocation 从 configJson 提列；**Splitter 与 Aggregator 不动** |

### 2.6 用户与权限

| 对象 | 标识 | 现状 | 2.0 动作 |
|---|---|---|---|
| **User/Permission** | 用户 + 角色 | **双轨**：`UserEntity.roles`（users.roles 列）与 `user_role_assignments` 表并存。创建与更新（`UserService.createUser`、`updateRoles` UserService.java:241）**只写 users.roles**；权限门读 `user_role_assignments`；唯一写手是 `deploy/demo/README.md §5` 的手工 psql | **B2 收敛为单一真源**：`user_role_assignments`；`users.roles` 降级为展示/兼容列（读多写少）；补一次性回填迁移 + `deploy/demo/README.md §5` 删除手工 psql 段 |

审计：`AuditLogEntity`（V0.2.4）已是唯一留痕通道；2.0 增加事件分类常量（RISK_POLICY_CHANGED / API_KEY_ROTATED / DATA_EXPORTED / PERMISSION_CHANGED / EXPERIMENT_PUBLISHED），不做新表。

---

## 3. 模块边界

### 3.1 模块清单（目标态）

| 模块 | 职责 | 现状 |
|---|---|---|
| `services/gateway-service` | 采集唯一入口：校验/Auth/限流/PII/风控闸/事件分发 | ✅ |
| `services/control-service` | 控制面 7 个资源组（见 3.2） | ✅ |
| `jobs/flink/*`（7 作业） | 状态计算：enrich / identity-merge / sessions / retention / funnels / risk / dimension-sync | ✅ |
| `sdks/{web,android,ios,unity}` | 客户端采集（documented 边界：无 secret、无 HMAC） | ✅ |
| **`sdks/server`（新增）** | **一等公民**：服务端可信事件 + HMAC（B3） | ✅（B3 落地：零依赖 Java 模块，Memory→Disk Queue→Batch→Gzip→HMAC） |
| `web/` | 控制台 SPA | ✅ |
| `deploy/` + `docker/` | 组合矩阵（§7） | ✅ |

### 3.2 控制面资源组（control-service 内分组）

```
games        → Game / Environment / StorageProfile
keys         → APIKey（含 Server Key 发放）
schemas      → EventSchema / TrackingPlan（过渡期并轨）
experiments  → Experiment / Variant / Exposure / Metric / Guardrail（B8 后）
risk         → RiskRule / Feature(B5) / RiskCase / ReviewQueue / BlockList
data         → Dashboard / Segment / Report / Cohort / Export / Funnel / Retention / Identity / PlayerData
system       → User / Role / AuditLog / Webhook / RateLimit / Security / FlinkJob / ML / Integration
```

现状控制器散落（`api/` 包 ~55 个 controller），**2.0 只做分组文档化，不搬文件**——包级重命名属 B10 以外的“可缓行”项，避免一次评审海量 diff。

### 3.3 依赖纪律（穿越即算缺陷）

1. **业务层不接触物理路由**：topic/CH 库名/归档桶只能经 StorageProfile 解析器得出（§2.1）。
2. **事件只进 Gateway**：SDK、Flink、任何模块不得直写 Kafka/ClickHouse 事件表（control 侧分析查询走 ClickHouseClient 只读接口除外）。
3. **`project_id` 兼容层保留**：todo.md 已 `[x]` 勾销（旧字段映射 game_id、纯遗留字段 invalid_schema 拒绝）。本计划**不删除兼容层**（删了会破坏 v0 SDK 兼容），只禁止兼容层之外的 `project_id` 出现。
4. **SDK 契约**：客户端只发事件、只持 `api_key`；SEREVR SDK 可发可信事件 + HMAC，二者都不得内置存储/路由知识。

---

## 4. 数据契约

### 4.1 事件 v1（现状，不可变基座）

`schema/json/oddsmaker-event-schema.json`：`$id` 定位、约 50 顶层属性、`props` 自由扩展区、`required` 含 `event_id/event_name/ts_client/game_id/environment` 等。Gateway `JsonSchemaValidator` + `PropsPolicy` 执行校验；未携带废弃字段的事件按 `invalid_schema` 拒。

### 4.2 事件 v2 增量（B4）

在 v1 上**只加字段、不改删**：

| 新增字段 | 类型 | 语义 | 校验 |
|---|---|---|---|
| `event_version` | integer | 事件契约（EventSchema）主版本 | ≥1；缺省 1 |
| `source` | enum | `client \| server \| system \| derived` | 必填（server SDK 与网关内部自动填） |
| `trust_level` | enum | `LOW \| HIGH \| COMPUTED`（client→LOW；server/system→HIGH；derived→COMPUTED） | 由 source 推导，客户端不可自抬 |
| `event_origin` | string | SDK 名+版本（如 `server-java/0.1.0`），审计回溯用 | 可选，网关填 default |

### 4.3 Server 可信事件约定（B3 同步写入契约）

- 服务端事件命名约定：`server.<domain>.<action>`（如 `server.purchase.confirmed`），与客户端 `client.` 语义区分；**充值/经济类结算只认 server 事件**（`trust_level=HIGH` 事件才能驱动风控决策的 BLOCK 级别动作）。
- 同一业务事实的客户端事件与服务器事件**不合并去重**，以 `source` 区分留存，风控/结算消费方显式声明依赖哪个。

### 4.4 Schema 三模式（复用 TrackingPlan 雏形）

`ValidationStrictness`（STRICT 已有）外扩两个值：`COMPATIBILITY`（未知字段警告不拒）/ `DEVELOPMENT`（dev 环境允许拒绝降级为跳过）。模式优先级：`环境级别 TrackingPlan > ApiKey.propsAllowlist > 网关默认`。网关与 schema 文件（`schema/json/*.schema.json`）保持同步出品。

### 4.5 Control API 契约

统一 `ApiResponse` 壳 + 全局异常映射（400 IllegalArgument / 401 未认证 / 403 AccessGuard 拒绝 / 404 not found / 409 唯一冲突 / 500 兜底）——现状已成立，v2 增量一律沿用；新增资源组端点命名 `/api/schemas`、`/api/features`、`/api/risk/decisions` 与现 `/api/games|environments|experiments|risk-rules` 同风格。

---

## 5. 风控六层抽象（P0 重点，B5/B6 落地）

```
Signal → Feature → Rule → Risk Score → Decision → Action
```

现状映射（已核）：

- **Signal** ✅：Gateway 校验后事件 + `risk_context`；risk-job `RuleConfig` 按 ruleType 索引、同型多条取最高 riskScore（`jobs/flink/risk-job/.../RuleConfig.java:13`）。
- **Feature** ✅（B5 落地）：risk-job 特征作业首 6 特征滑动窗口 upsert `risk_features`；`FEATURE` 规则 `ruleConditions.features` 从特征快照取值（共享 `feature_store` 双写留 B10，§5.3）。
- **Rule** ✅：`RiskRuleEntity`（THRESHOLD/SEQUENCE、riskLevel、actionType、enableAutoBlock/blockDuration）。
- **Risk Score** ✅（B6 落地）：risk-job 评估侧 `RiskScoreFunction` 按主体累计各规则最大贡献分（Rule 静态 riskScore 降为该规则最大贡献分，同规则重复命中取高不叠加），每次评估落 CH `risk_scores` 行（主体累计分 + 规则触发明细 JSON）；处置动作侧不回写（control 单写 `risk_actions`）。
- **Decision** ✅（B6 落地）：`RiskCaseEntity.status` 分层前向状态机（`OPEN → REVIEW/ALERT/MARK → THROTTLE/BLOCK → RESOLVED`，严格升层 + OPEN 不直达 RESOLVED），`RiskEventConsumer` Decision-first——判定先落 status、动作执行只发生在 Decision 之后，非法流转拒绝执行并归档 `decision_rejected`；BLOCK 级动作要求输入 `trust_level=HIGH`（risk-job/control 两侧同语义双门槛）。
- **Action** ✅：ActionType + BlockList/封禁 + Webhook 告警已通。

### 5.1 B5：Feature 层（风险先行）

- 新表 `risk_features`（`game_id, environment, scope_key(identity), feature_name, window_start, window_end, value, as_of`）+ `RiskFeatureEntity` + 仓库。
- `jobs/flink/risk-job` 新增特征作业分支：gold_gain_1h/24h、device_count、account_count_per_ip、win_rate 等首批特征（**特征清单以本文为准，先 6 个**）。
- Rule 的 `ruleConditions` 不再直接从事件取数，改从特征取值（事件→特征→规则三段解耦）。

### 5.2 B6：Score 与 Decision

- 评估随事件触发，产出 `risk_scores` 行（subject、累计分、规则触发明细 JSON）；Rule 的静态 riskScore 变“该规则的最大贡献分”。
- 判定状态机 `RiskCaseEntity.status`：`OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED`（流转合法表入测试）。
- 动作执行只发生在 Decision 之后；BLOCK 级动作要求 `trust_level=HIGH` 输入（4.3 依赖，B6 验收覆盖此条）。

### 5.3 共享 Feature Layer（P1，B10 可选）

Analytics 的 DAU/留存/ARPU 与风控特征同源（玩家行为特征），B5 的 `risk_features` 表不模拟共享——共享在 **risk-job 特征作业产出双写**：一份进 `risk_features`，一份进共享 `feature_store` 摘要表（B10 定 schema，不占用 B5/B6 关键路径）。

### 5.4 验收（B5/B6 共用）

- 全量测试绿；新增 `RiskFeature*Test`（特征计算、窗口滑动、规则引用特征）与 `RiskDecisionStateMachineTest`（状态机合法/非法流转各 ≥3 条）。
- bash 级端到端（真 PG 复现法）：灌 2 条超阈值「金币获取」事件 → `risk_features` 出现 1h 窗口行 → 触发规则 → `risk_scores` 落行 → Decision=REVIEW → Action=ALERT。

---

## 6. 实验平台形式化（B8）

现状（已核实，评估低估了这块）：`ExperimentSplitter`（FNV-1a/weighted/salt 分流，`experiments` 配置由 `experiment-config.schema.json` JSON Schema 约束）、`ExperimentMetricsAggregator`（ClickHouse 按天窗口归因、幂等回填 `experiment_metric_snapshots`，已含 exposure_users 去重与 experiments map 归因、SRM 样本量基础）、`ExperimentStatsService` 与 `/api/experiments/{id}/results`；唯一短板是**控制面资源形态**——`ExperimentEntity` 仍是 7 字段裸表（status 为自由字符串），Audience/Guardrail/Decision 全缺。

目标字段化（**不动 Splitter 与 Aggregator 算法**）：

| 对象 | 现状 | 2.0 形态 |
|---|---|---|
| Audience | 缺 | 目标条件（复用 Segment 概念，`audience_segment_id` 引用） |
| Allocation / Variant | 藏在 configJson | 显式 `variants[]`（name/weight/salt key）提列；`variant_id` 实验创建即生成 |
| Exposure | ✅ `experiment_exposure` 事件已入 ClickHouse 归因 | 已列入 `04-redesign §4.1` 平台事件约定，曝光列经 `/api/experiments/{id}/results` 可查 |
| Metric | ✅ `experiment_metric_snapshots` 已回填 | Guardrail 布尔置位（反转指标 crash/refund/complaint，超出即预警） |
| Decision | 缺（status 自由字符串） | `status ∈ {DRAFT, LIVE, PAUSED, ENDED}` 枚举化 + 发布动作 |

验收：ExperimentController 增补 Guardrail/发布动作与 status 枚举化；`experiment-config.schema.json` 同步升级并保持旧 configJson 向前兼容；Splitter 相关存量测试全绿不改。

---

## 7. 运行模式矩阵（B9）

现状三档编排已存在：`deploy/demo`（无 Kafka，control designed 降级 available=false）、根 `docker-compose.quickstart.yml`（Kafka+Apicurio+topic-init）、`infra/docker-compose.yml`（全量）。**B9 不改编排，产出一张声明矩阵**：

| 模式 | 组件 | 收益/代价 |
|---|---|---|
| Lite | gateway → control（内存队列直落，可选 ClickHouse） | 开发机 1C 可跑；实时分析降级 |
| Standard | + Kafka + ClickHouse + redis + apicurio | 单机默认档（≈ 现状 quickstart） |
| Production | + Flink 全作业 + Superset/Grafana/Prometheus + 对象存储归档 | 1.6GB 机器不适用（现状 deploy/demo 已为此降档） |

矩阵落 `deploy/` 下 `MODES.md`，并在 demo 编排中注明“本档 = Lite”。

> **✅（B9 落地）** `deploy/MODES.md` 已就位；四处编排头注标记：demo=Lite、quickstart=Standard、
> 根 compose=Production 业务栈、infra=Production 观测/中间件面。与上表的一处对账修正：
> infra 并非“全量”单档——它是无 postgres/redis/业务服务的中间件+观测面子集，Production 全组件
> = 根 compose（业务栈 + Flink ×7 + Superset）∥ infra（Kafka/CH/Apicurio/观测面）两件套；
> 对象存储归档不在任何 compose（`deploy/backup` 脚本 + `deploy/k8s/backup-cronjob`）。
> Standard 档无 Flink 作业（`events_raw` 无下游消费者、CH 只有 schema 无数据）也已如实入矩阵。

---

## 8. 排期批次（Code Agent 执行序）

批次依赖关系：B1 → B2 → B3 → B4 → B5 → B6；B7 ∥ B5；B8 ∥ B5；B9 任意；B10 最后。

| 批 | 内容（§锚点） | 估算副作用面 | 验收标准（退出条件） |
|---|---|---|---|
| **B1** | 产品定位对齐：GitHub description、completion_assessment 归档横幅、index.md 补 06（§1） | 3 个纯文档改动 | 无代码；横幅/description/index 三处就位；不触发测试 |
| **B2** | 权限单真源收敛：一次性回填迁移 + `updateRoles`/`createUser` 改写 assignments + README §5 删手工 psql（§2.6） | control-service、迁移 ×1、deploy/demo README | 全绿；建号→分配角色→受权限门端点 200（真 PG 复现法）；README §5 无 psql 段 |
| **B3** | Server SDK 骨架（`sdks/server`，Java）：Initialize/SetUser/Track/Flush/Memory→Disk Queue→Batch→Gzip→HMAC（§3.1、§4.3） | 新模块 | SDK 集成测试通过（queue 溢出落盘、HMAC 请求被网关验签 200/伪造 401）；网关 SERVER key 发验证测试 |
| **B4** | 事件 v2 增量：`event_version/source/trust_level/event_origin` + schema 文件三枚同步 + 网关校验（§4.2） | gateway、schema/json×3、Flink 各 job 字段透传 | 全绿；v1 事件不收 source 仍 200（向后兼容）；source=client 时信 LOW 不可自抬重写 |
| **B5** | 风控 Feature 层（§5.1） | control、risk-job、迁移 ×1 | 见 5.4 验收 |
| **B6** | RiskScore 独立 + Decision 状态机 + Block 动作的 HIGH 信任门槛（§5.2） | control、risk-job | 见 5.4 验收 |
| **B7** | EventSchema 一等资源化（TrackingPlan 升级、compatibility/PII/retention 字段、UI 树 schemas 组）（§2.3） | control、web | 全绿；EventSchema API CRUD + 版本发布/兼容检查测试 |
| **B8** | 实验平台形式化（§6） | control、web、schema 事件清单 | 全绿；Audience/Guardrail/Decision 字段化 + status 枚举化；Splitter/Aggregator 存量测试零改动通过；exposure 可查 |
| **B9** | MODES.md + 编排档位注释（§7） | deploy/ 文档 | 三档组件矩阵与现状编排一致（读码核对） |
| **B10** | Data Quality（event_valid_rate/drop_rate/duplicate/late 指标 + Game Data Health 页）与共享 Feature 双写定 schema（§5.3）；**不做 Data Lineage 排期** | control、web、Flink | 指标表落库可查；健康页有数可看 |

> B10 排最后的理由（正确性优先）：Data Quality 是 B2/B4/B5 稳定之后的观测手段，不是先行建设；Data Lineage 仅记录方向不自建系统（`04-redesign §5` 已给出指标来源链，够用）。

---

## 9. 边界（不做清单，写进代码评审 checklist）

1. **不做**多租户/SaaS 化（Organization/Workspace/Project/Tenant 概念不入新代码）。
2. **不删** Gateway `project_id` 兼容层（§3.3）。
3. **不搞**事件自由流动：dev 模式外，未注册 schema 的事件默认拒收（B7 落地：新 Schema 默认 `rejectUnknownEvents=true`（V0.9.20 收口），ACTIVE Schema 事件面经 internal feed 下发网关，未定义事件按 `unknown_event` 事件级拒收，dev 环境豁免）。
4. **不让** SDK/业务层持有物理路由（storage_profile 是唯一桥）。
5. **不做** Data Lineage 系统化、不做跨公司数据交换、不做移动端原生队列之外的新端。
6. 旧评估文件中的“权限 15% 完成度”“12 周计划”等数字**任何人不得再引用为现状**（B1 横幅补齐后全仓口径统一）。