# 03 - 参考项目取舍

本章只讨论 Oddsmaker 应该借鉴哪些能力。**不要照搬开源分析产品的 SaaS 多租户模型**。

## 1. 参考矩阵

> **结论：**六家参考全部走「能力可借、形态不搬」——分析/治理/实验能力逐项吸收，SaaS 多租户模型一律不进本仓；逐条判定与落点见 §5。

| 项目 | 可借鉴 | 不应照搬 |
|---|---|---|
| PostHog | ClickHouse 事件分析、Feature Flag、A/B、Session Replay 思路 | Org/Project/Team 多租户模型 |
| Countly | 移动和游戏分析、插件化、Crash、Remote Config | 面向多客户 SaaS 的应用隔离方式 |
| GameAnalytics | 游戏事件 Taxonomy、核心游戏指标 | 黑盒 SaaS 产品形态 |
| Snowplow | Schema Registry、Tracking Plan、事件治理 | 复杂的多 pipeline 企业 CDP 抽象 |
| Mixpanel | Identity Merge、用户画像、漏斗 | 通用产品分析优先的事件口径 |
| Statsig/GrowthBook | 实验分桶、SRM、显著性、Feature Flag | 多组织 SaaS 管理模型 |

## 2. Oddsmaker 的正确取舍

Oddsmaker 是私有化或单公司内部平台，应该吸收：

- GameAnalytics 的游戏事件分类。
- PostHog 的全栈事件分析架构。
- Snowplow 的 Schema 治理。
- Mixpanel 的 Identity Merge。
- Statsig/GrowthBook 的实验统计。
- Countly 的 Crash、Remote Config 和插件化边界。

Oddsmaker 不应该吸收：

- 多公司租户隔离。
- SaaS 套餐和租户升级。
- 跨公司 Row Policy。
- 为 noisy neighbor 设计的租户配额。
- 复杂的组织层级销售模型。

## 3. 游戏标准事件体系

> **结论：**九类与 GameAnalytics 七大类同源并带风控扩展（`risk`），已在 Tracking Plan 中强约束为行业通用语言。

| 类型 | 用途 | 示例 |
|---|---|---|
| `session` | 会话开始、结束、心跳 | `session:start`、`session:end` |
| `user` | 玩家属性和账号状态 | `user:login`、`user:bind_account` |
| `business` | 真实货币收入 | `business:purchase:success` |
| `resource` | 虚拟经济 source/sink | `resource:gold:source`、`resource:gem:sink` |
| `progression` | 关卡、任务、新手引导 | `progression:level:complete` |
| `design` | 自定义玩法事件 | `design:gacha:draw` |
| `error` | 崩溃、异常、客户端错误 | `error:crash:fatal` |
| `ad` | 广告展示、点击、激励 | `ad:rewarded:complete` |
| `risk` | 风控信号和处置 | `risk:payment:receipt_reused` |

## 4. 风控参考原则

风控不能只靠离线报表。游戏场景需要实时和准实时能力：

- 作弊检测：脚本刷关、资源异常增长、战斗结果异常。
- 账号安全：撞库、多地登录、设备异常、模拟器农场。
- 支付风控：重复收据、沙盒单、退款、短时大额。
- 经济风控：虚拟货币 source/sink 失衡、异常赠送、交易洗钱。
- 广告风控：异常 impression/click/reward、设备农场。

可借鉴的通用模式：

- Gateway 做请求级硬拦截。
- Flink 做状态化规则和短窗检测。
- ClickHouse 做回溯分析。
- Redis 保存短期计数器和黑白名单。
- Control Service 管理规则、阈值、动作和审计。

## 5. 可参考分析（逐条判定与落点）

> **结论：**六家参考的可借鉴项大半已落地（事件分类、Schema 治理、实验统计、Flag/实验、Crash/Remote Config），Identity Merge 部分落地、Session Replay 经竞品分析改判不适用；不适用项全部集中于 SaaS 多租户形态。

| 判定 | 参考项 | 为什么 | 本仓落点 |
|------|--------|--------|----------|
| 可参考 | PostHog：ClickHouse 事件分析 / Feature Flag / A/B | 全栈事件分析架构与自托管形态同向 | 已落地：ClickHouse 事件主链路 + `FeatureFlagsView` + `ExperimentsView`（B8 实验形式化） |
| 不适用 | PostHog：Session Replay（原矩阵记「思路可借鉴」） | 竞品分析已改判不跟进：客户端录制 SDK 存储高一个量级、隐私面反噬 PII 治理承诺 | 不实现；以 `competitive-analysis.md` §4 为准（本表改判即对账） |
| 可参考 | Countly：Crash / Remote Config / 插件化边界 | 移动与游戏向的成品能力组合 | 已落地：`CrashView`（指纹规范化 + 版本崩溃率）、`FeatureFlagsView`（配置下发）；插件化仅作模块边界参考 |
| 可参考 | GameAnalytics：游戏事件 Taxonomy | 游戏原生分类是行业通用语言 | 已落地：P3 九类事件（§3 表）+ B7 EventSchema/Tracking Plan 强约束 |
| 可参考 | Snowplow：Schema Registry / Tracking Plan / 事件治理 | 治理是分析可信的前提 | 已落地：EventSchema 一等资源化（B7：compatibility / PII policy / retention / sampling / owner + 版本发布与兼容检查） |
| 可借鉴 | Mixpanel：Identity Merge / 画像 / 漏斗 | 主体口径统一是多端归因的前提 | 部分落地：subject 回退口径（player_id → user_id → device_id）贯穿报表与风控；显式 Identity Merge（跨端身份合并）列后续候选 |
| 可参考 | Statsig/GrowthBook：分桶 / SRM / 显著性 | 实验统计的正确性底线 | 已落地：SHA-256 确定性分桶 + z/t 检验 + 卡方 SRM（B8），Audience/Guardrail/Decision 已字段化 |
| 不适用 | 六家共同的 SaaS 多租户形态（Org/Project/Team、套餐与升级、跨公司 Row Policy、租户配额、组织销售层级） | 单公司部署是产品边界，隔离只围绕 `game_id + environment` | 不做（本章 §2 负面清单 + 06 计划书领域模型） |
| 可参考 | §4 风控通用模式（Gateway 硬拦截 / Flink 状态化 / ClickHouse 回溯 / Redis 计数 / Control 规则审计） | 分层拦截 + 状态化检测 + 可审计处置是风控链路的成熟分法 | 已同构落地：Gateway 前置校验、risk-job（B5 特征层）、ClickHouse risk_events、Redis 计数器/黑名单、RiskRule/RiskCase（B6） |

## 6. 架构结论

Oddsmaker 的目标范式是：

**单公司多游戏控制面 + 游戏事件 Taxonomy + 实时风控流处理 + ClickHouse 分析存储 + 可治理 Tracking Plan**

核心分区和权限都应围绕 `game_id + environment`，而不是 `tenant_id`。
