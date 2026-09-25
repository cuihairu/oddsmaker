# Oddsmaker 竞品对比与功能差距分析

> 调研时间：2026-09 ｜ 方法：网络搜索 + 各产品官方文档（Unity docs.unity.com / firebase.google.com / gameanalytics.com / learn.microsoft.com PlayFab / posthog.com）
> 结论用途：更新 `todo.md` 路线图优先级（新增 P7 差距收敛批次），并驱动首个落地功能（P7-1 实时事件检视器）。

## 1. 调研对象

选取 5 个同领域成熟产品，覆盖商业巨头（Unity/Google/Microsoft）、垂直免费工具（GameAnalytics）、开源新势力（PostHog）三种形态：

| 产品 | 厂商 | 形态 | 定位 | 与本项目的关系 |
|------|------|------|------|----------------|
| Unity Gaming Services (Analytics + Remote Config + Cloud Diagnostics) | Unity | 商业 SaaS | Unity 生态默认游戏分析与运营后端 | 事件模型、漏斗、崩溃、远程配置的全链路对标 |
| Firebase (Analytics + Crashlytics + Remote Config + A/B Testing) | Google | 商业 SaaS | 移动/游戏通用分析与实验平台 | 实验闭环、DebugView、App Check 设备完整性对标 |
| GameAnalytics | GameAnalytics (Moboku) | 免费 SaaS（增值付费） | 游戏原生产品分析标准件 | 事件分类法（本项目 P3 事件类型化的蓝本）、实时视图、基准 |
| PlayFab | Microsoft | 商业 SaaS | 游戏后端 + LiveOps + 分析全家桶 | LiveOps 面（公告/邮件/兑换/定向投放）的对标 |
| PostHog | PostHog | 开源（可自托管） | 产品分析 + 实验 + 回放一体化 | 开源形态与自托管诉求的直接对标；flags/实验一体化设计 |

来源（主要）：
- Unity：docs.unity.com（Analytics funnels 定义、Standard Events、Remote Config 文档，2026-09 更新）
- Firebase：firebase.google.com/docs（A/B Testing 教程、Remote Config for Unity pathway、App Check/Play Integrity）
- GameAnalytics：2026 移动游戏变现工具栈评测（undrads.com 2026-09）、2026 留存基准报告（gamegrowthadvisor.com，D1 22% / 11,600 款游戏样本）、LinkedIn 官方（A/B + Remote Config 能力）
- PlayFab：learn.microsoft.com（2026-07 仍在更新文档；个别子功能有 deprecation 记录，服务整体在运营；无整体退役公告）
- PostHog：posthog.com（2026-02 feature flag 工具对比：flags 与分析/实验一体化为第一卖点）

## 2. 功能对比矩阵

图例：✅ 完整 ｜ 🟡 部分/雏形 ｜ ❌ 无 ｜ N/A 不适用（单公司部署形态下无意义）

### 2.1 数据接入与治理

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| 多端 SDK（Web/Android/iOS/Unity/Server） | ✅（5 端，含 Server HMAC） | ✅（Unity 生态为主） | ✅ | ✅ | ✅ | ✅（Web/Mobile 为主） |
| 事件 Schema 治理 / Tracking Plan | ✅（JSON Schema + Tracking Plan + 属性白名单 + cardinality 上限） | 🟡（标准事件+校验） | 🟡（建议性事件） | 🟡（标准事件强约束） | ❌ | 🟡（data management） |
| PII 策略（mask/drop/allow、IP 粗化） | ✅（策略可按环境覆盖） | ❌ | 🟡 | ❌ | ❌ | 🟡 |
| 环境隔离（dev/staging/prod 数据边界） | ✅（game_id+environment 全链路） | 🟡 | ✅（project 维度） | 🟡 | ✅（title 维度） | ✅（project 维度） |
| 数据保留分级/分区 | ✅（按环境策略 + toYYYYMM 分区） | ❌（平台托管） | 🟡 | ❌ | ❌ | ✅ |

### 2.2 分析能力

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| 实时看板（DAU/在线/分钟趋势） | ✅ | ✅ | ✅（Realtime） | ✅（Real-time Overview） | 🟡 | ✅（Live） |
| 留存（N-Day + Rolling，cohort 趋势） | ✅ | ✅ | 🟡 | ✅ | 🟡 | ✅ |
| 漏斗（有序/无序/时间窗） | ✅ | ✅ | 🟡（funnel 探索） | ✅ | 🟡 | ✅ |
| 可复用用户分群（Segment 定义一次、处处过滤） | ❌（仅平台/版本/渠道维度切分） | ✅ | ✅（Audiences） | ✅ | ✅（Segments） | ✅ |
| 商业化（IAP/广告/LTV/pLTV） | ✅ | 🟡 | ✅ | ✅ | 🟡 | 🟡 |
| 流失预测 | ✅（启发式可解释） | ❌ | 🟡（GA predictive audiences） | ❌ | ❌ | ❌ |
| 行业基准（Benchmark） | N/A（单公司无跨公司数据池） | 🟡 | ❌ | ✅（11,600+ 游戏样本） | ❌ | ❌ |
| 归因/买量（MMP 集成、CPI/ROAS） | ❌（仅 attribution.channel 字段回退维度） | 🟡 | ✅（with Google Ads） | 🟡 | ❌ | 🟡 |

### 2.3 实验与配置下发

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| A/B 实验（分流+统计检验+SRM） | ✅（SHA-256 确定性分桶、z/t 检验、卡方 SRM） | ✅（Game Overrides） | ✅ | 🟡（付费档） | ✅（Experiments） | ✅ |
| Remote Config（环境覆盖+增量拉取） | ✅ | ✅ | ✅ | 🟡 | ✅（Title Data） | ✅ |
| Feature Flags（百分比放量/advance） | ✅ | 🟡（借 Remote Config） | 🟡 | ❌ | 🟡 | ✅（强项） |

### 2.4 稳定性与风控

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| Crash/Error 上报与聚合 | ✅（指纹规范化+符号化规则+版本崩溃率） | ✅（Cloud Diagnostics） | ✅（Crashlytics） | 🟡（error 事件） | 🟡 | 🟡 |
| 实时风控规则（阈值/速度/序列/黑名单/重放） | ✅（内建主链路，Gateway 前置 + Flink 实时） | ❌ | 🟡（仅 App Check 设备完整性，非行为风控） | ❌ | 🟡（封禁/审计，无实时规则） | ❌ |
| 风险评分与处置（block/review/mark/throttle + Webhook） | ✅ | ❌ | ❌ | ❌ | 🟡（人工 moderation） | ❌ |

### 2.5 LiveOps 与玩家运营

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| 公告/邮件/兑换码 | ✅ | 🟡 | ❌ | ❌ | ✅ | ❌ |
| 玩家档案查询/导出/删除（GDPR） | ✅（跨表打包导出+审计） | 🟡 | 🟡 | 🟡 | ✅ | ✅ |
| 定向触达（按分群投放） | 🟡（公告可定时，未接分群） | ❌ | ✅（Audience 定向） | ❌ | ✅（Segments+Offers） | 🟡 |

### 2.6 工程体验（接入与调试）

| 能力 | Oddsmaker | Unity UGS | Firebase | GameAnalytics | PlayFab | PostHog |
|------|-----------|-----------|----------|---------------|---------|---------|
| 实时事件检视（Live/Debug View：看到每条事件到达与否及拒绝原因） | ❌ | 🟡 | ✅（DebugView/StreamView） | ✅（Real-time 事件流） | 🟡 | ✅（Live events） |
| 自定义仪表盘（widget 组合） | 🟡（Report 有雏形，非自由组合） | 🟡 | ✅ | 🟡 | 🟡 | ✅ |
| 原始数据导出/SQL 访问 | 🟡（玩家级导出+报表 CSV；无全量原始导出/SQL） | 🟡 | ✅（BigQuery） | 🟡 | 🟡（PlayFab Explorer） | ✅（HogQL/Warehouse） |
| 指标告警（阈值+通知） | ✅ | 🟡 | ✅ | ❌ | 🟡 | ✅ |
| 权限/审计（RBAC + 审计日志） | ✅（scope 化 RBAC + 全量审计） | 🟡 | 🟡 | ❌ | ✅ | ✅ |

## 3. 差距结论：缺失的关键功能（按用户价值排序）

1. **实时事件检视器（Live Inspector / Debug View）** —— 价值★★★★★ 成本★
   接入期第一痛点：SDK 接入后"我的事件到底到没到、为什么被拒"。竞品标配（Firebase DebugView、GA Real-time、PostHog Live）。本项目所有拒绝原因（invalid_schema/api_key_scope_mismatch/pii_blocked/invalid_timestamp/采样/去重/封禁）目前只进 DLQ 与响应体，无处可视化。落地为 Gateway 内存环形缓冲 + 检视 API + 控制台页面。
2. **可复用用户分群（Segments）** —— 价值★★★★☆ 成本★★★
   竞品全有，且是定向触达（Firebase Audiences、PlayFab Offers）的基础设施。定义（属性+行为条件）→ 物化（ClickHouse 主体集合）→ 注入留存/漏斗/财务/在线报表过滤 + LiveOps 定向投放。注意与现有留存 CohortService 区分（那是留存口径 cohort 计算，非用户分群）。
3. **自定义仪表盘 widget 化** —— 价值★★★☆ 成本★★
   Report 模块已有雏形；补 widget 卡片（图/表/KPI）自由组合与保存，追赶全部竞品的 Dashboard Builder。
4. **全量原始数据导出（S3/对象存储 定期导出）** —— 价值★★★ 成本★★
   对标 Firebase BigQuery。归档 infra 已在架构图中（Object Storage/Archive），补按日分区导出任务即可，是 BI 下钻（Superset/Metabase）的喂料通道。
5. **归因/买量分析（MMP 集成）** —— 价值★★★（买量型工作室为★★★★★）成本★★★★
   依赖外部 MMP（AppsFlyer/Adjust）数据源接入与建表，外部依赖重，放中期评估。

## 4. 明确不跟进的功能及理由

| 功能 | 来源竞品 | 不跟进理由 |
|------|----------|------------|
| Session Replay（会话回放） | PostHog | 需要客户端渲染级录制 SDK，存储成本高一个数量级；与"分析+风控"定位的边际收益低；隐私面扩大（PII 治理承诺反噬） |
| 行业基准 Benchmark | GameAnalytics | 单公司部署是产品边界（README 定位），无跨公司数据池；做匿名数据联盟违背数据治理卖点 |
| 游戏后端（排行榜/成就/多人服务器/经济系统） | PlayFab、Unity UGS | 与"分析+风控+LiveOps 触达"正交；游戏服属于客户域，本项目以 Webhook/集成面协作即可 |
| 推送通知通道（FCM/APNs 自建） | Firebase | 需厂商账号体系与推送基建；触达已由公告+邮件+Webhook 覆盖，后续可作为 Integration 扩展点开放而非自建 |
| 广告平台直连优化（bidding/ROAS 自动化） | Firebase(Google Ads) | 超出分析平台职责；买量决策留给 MMP/BI |
| 多租户 SaaS 化 | （本项目历史包袱） | 已在 todo.md 暂停项明确：单公司多游戏是产品边界 |

## 5. 设计启发（来自竞品）

1. **接入体验决定平台信任**（Firebase DebugView / GA Real-time）：数据"黑盒感"是自建分析平台被弃用的首因 → Live Inspector 列入 P7-1 最高优先级。
2. **Flags 与实验同源**（PostHog 2026 卖点）：flag 是实验的载体、实验是 flag 的度量。本项目 Feature Flags / Remote Config / Experiments 三件套能力齐全但入口分散，中期应收敛为统一"交付面"（一个实体三种角色），短期不动。
3. **标准事件分类法是行业通用语言**（GA 七大类）：本项目 P3 九类（+experiment/risk）与之一致并有风控扩展，保持并继续在 Tracking Plan 中做强约束——这是相对 PostHog 等通用工具的领域优势。
4. **风控是差异化长板而非补丁**（对照五竞品均无完整实时行为风控）：继续深挖（评分/处置/证据链已有），不因追赶分析面而稀释。
5. **Segment 是运营效率的乘数**（Firebase Audiences / PlayFab Segments）：一次定义、处处可用（报表过滤+定向触达），是 LiveOps 从"广播"升级到"精准"的前提 → P7-2。
6. **数据出口开放性**（BigQuery/HogQL）：自托管客户终将要求原始数据自主权 → 全量导出（P7-4）优先于花哨报表。

## 6. 路线图衔接

新增 `todo.md` **P7 竞品差距收敛**批次：

- P7-1 实时事件检视器（本次落地）：Gateway 检视缓冲 + `/v1/inspector/recent` + Control 代理 API + 控制台 Live Inspector 页
- P7-2 可复用用户分群（定义→物化→报表注入→LiveOps 定向）
- P7-3 仪表盘 widget 化
- P7-4 全量原始数据导出（对象存储按日分区）
- P7-5 MMP 归因接入评估（先调研后立项）

优先级依据：用户价值 × 实现成本 ÷ 外部依赖，见 §3 排序。
