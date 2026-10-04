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

**验收：** ✅（提交后回填）全量 211 suite + web build 双绿；`sdks/server` 零依赖模块落地（subtree 可拆，自带独立 settings）：`OddsmakerIntegrationTest` 4 臂——内存溢出落盘+跨进程续传（close 时内存残留强制溢写，死 endpoint 下 close 不丢事件）、HMAC 正臂（JDK HttpServer 按 HmacFilter 同口径验签 200）、伪造签名负臂（secret 轮换→401 整批丢弃不重试）、5xx 整批退回止步恢复重发；`ControlServiceTest` +2（SERVER 发放拒绝/放行）。Gateway 正/负臂已有在仓测试（HmacSignatureWindowTest 200/过期 401/缺签名 401、HmacFilterCoverageTest 伪造 401、ReplayGuardRotationTest 重放 401），B3 未改网关代码。发放校验：`games.server_events_enabled`（V0.9.17 迁移）+ createKey SERVER 档位拒绝未启用游戏。

## B4 事件契约 v2 增量

- [ ] 事件 schema 加 `event_version`（缺省 1）/ `source`（client|server|system|derived）/ `trust_level`（LOW|HIGH|COMPUTED，由 source 推导不可自抬）/ `event_origin`（SDK 名+版本）
- [ ] `schema/json` 三枚 schema 文件同步 + Gateway `JsonSchemaValidator`/`PropsPolicy` 增量校验
- [ ] Flink 各 job 对新增字段透传（不改 key）

**验收：** 全绿；v1 事件不收 `source` 仍 200（向后兼容）；`source=client` 时 `trust_level` 恒 LOW、自抬重写拒绝。

## B5 风控 Feature 层

- [ ] 迁移 + `RiskFeatureEntity`：`risk_features`（game_id, environment, scope_key, feature_name, window, value, as_of）
- [ ] `jobs/flink/risk-job` 特征作业分支：首批 6 特征（gold_gain_1h/24h、device_count、account_count_per_ip、win_rate…清单以计划书 §5.1 为准）
- [ ] `RiskRuleEntity.ruleConditions` 改从特征取值（事件→特征→规则三段解耦）

**验收：** 见计划书 §5.4 端到端：灌超阈值金币事件 → `risk_features` 出 1h 窗口行 → 规则命中 → 决策链路。

## B6 RiskScore 独立 + Decision 状态机

- [ ] 评估产出 `risk_scores`（subject、累计分、规则明细 JSON）；Rule 静态 riskScore 降为“该规则最大贡献分”
- [ ] `RiskCaseEntity.status` 状态机：`OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED`（流转合法表入测试）
- [ ] Block 级动作要求输入事件 `trust_level=HIGH`（server 事件才能驱动）

**验收：** 全绿；状态机合法/非法流转各 ≥3 用例；§5.4 端到端全链路可跑。

## B7 EventSchema 一等资源化

- [ ] TrackingPlan→EventSchema 升级：补 compatibility / PII policy / retention / sampling / owner 字段与版本发布/兼容检查 API
- [ ] `rejectUnknownEvents` 默认值改 true（随本批验收，dev 模式豁免）
- [ ] web 控制台 schemas 资源组页面/入口

**验收：** 全绿；EventSchema CRUD + 版本发布与兼容检查测试；未知事件默认拒收行为有测试钉住。

## B8 实验平台形式化

- [ ] `ExperimentEntity` 显式字段化：Audience（segment 引用）/ Guardrail / Decision（status ∈ {DRAFT, LIVE, PAUSED, ENDED} 枚举化）/ Variant 与 Allocation 从 configJson 提列
- [ ] `experiment-config.schema.json` 同步升级、旧 configJson 向前兼容
- [ ] `experiment.exposure` 列入平台事件清单（§4）

**验收：** 全绿；Splitter/Aggregator 存量测试零改动通过；audience/guardrail 创建与发布动作测试。

## B9 运行模式矩阵

- [ ] `deploy/MODES.md`：Lite / Standard / Production 三档组件矩阵（对齐现有三套编排）
- [ ] `deploy/demo` 编排注明「本档 = Lite」；quickstart = Standard；infra = Production

**验收：** 矩阵与仓库现有编排逐一核对一致（读码比对，改错即拒）。

## B10 Data Quality 与共享特征（收口批）

- [ ] `event_valid_rate / drop_rate / unknown_event_rate / duplicate_rate / late_event_rate` 指标落库 + Game Data Health 页
- [ ] 共享 Feature 双写 schema 定稿（risk_features + feature_store 摘要表）——**Data Lineage 仅记方向不建系统**

**验收：** 指标表可查、健康页有数可看；feature_store 建表迁移与双写合成器测试；无 Lineage 代码。

---

## 边界（不跟进，评审转审查项）

- 不做多租户/SaaS（Organization/Workspace/Project/Tenant 概念不入新代码）
- 不删 Gateway `project_id` 兼容层（已闭环项，删除破坏 v0 SDK 兼容）
- 不搞事件自由流动：dev 模式外未注册 schema 的事件默认拒收（B7 后）
- SDK/业务层不持有物理路由（storage_profile 是唯一桥）
- 不做 Data Lineage 系统化、不做新客户端端（移动端原生队列之外）
- P8 MMP 归因接入保持暂停（前置：MMP 原始数据导出权限，未达成，仅存调研文档）
- 旧评估完成度数字不得再引用为现状