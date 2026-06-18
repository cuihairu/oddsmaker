# 03 - 开源参考项目对标

## 1. 横向对比矩阵

| 项目 | 技术栈 | 核心能力 | 多租户 | 游戏适配 | Pit 可借鉴 |
|---|---|---|---|---|---|
| **PostHog** | Django+ClickHouse+Kafka+Redis+PG | 产品分析+Session Replay+Feature Flag+A/B+错误追踪+Survey | Org/Project/Team | 中 | 全栈架构 + ClickHouse 多租户 |
| **Plausible** | Elixir/Phoenix+PG+ClickHouse | 隐私优先 Web 分析、<1KB 脚本 | 多 site | 低（仅 Web） | ClickHouse 单表设计 |
| **Countly** | Node.js+MongoDB（插件化） | 移动/Web 事件+Session+Crash+Push+Remote Config | 多 App（App Key） | **高**（Unity SDK + 游戏指标） | 插件化 + 移动 SDK |
| **RudderStack** | Go+Warehouse-native CDP | 事件采集+路由+数仓+Reverse ETL | 企业级 | 中 | Destination 抽象 + Schema 治理 |
| **Snowplow** | Scala+Kinesis+Iglu+Warehouse | 严格 Schema 验证事件管道 | 多项目 | 中 | Schema Registry + Tracking Plan |
| **Matomo** | PHP+MySQL（+CH 插件） | 全功能 Web 分析+热图+A/B+SEO | 多 site | 低 | ❌ 反面教材：别用 MySQL 存事件 |
| **Jitsu** | Go/TypeScript | 实时事件收集+路由到数仓 | 中 | 中 | 轻量、Segment 替代 |

## 2. 商业参考（功能对标）

| 项目 | 核心价值 | 借鉴点 |
|---|---|---|
| **GameAnalytics** | 游戏事实标准事件分类 | 7 类事件 + 13 个核心指标 |
| **Unity Gaming Services**（含 deltaDNA） | Funnel/Cohort/A/B/Remote Config/LiveOps 一体化 | LiveOps 工作流 |
| **Mixpanel** | Event-based，用户表与事件表分离 | Identity Merge（$identify/$merge） |
| **Amplitude** | Event + Compass + Persona | 预测 LTV + 流失建模 |
| **Statsig / GrowthBook** | A/B + Feature Flag | CUPED 方差缩减 + SRM 检查 |

## 3. 三大架构范式

### 3.1 CDP 范式（RudderStack/Snowplow/Jitsu）
- 管道优先，分析交给 BI/数仓
- Schema 治理最强（Snowplow Iglu Schema Registry）
- 不锁数据，Warehouse-native
- **Pit 借鉴**：事件 → Kafka → 多 Sink 抽象、Schema Registry

### 3.2 All-in-One 范式（PostHog/Countly）
- 采集+存储+分析+Feature Flag+Replay 全栈
- 对中小团队最友好
- ClickHouse 已成为事实标准
- **Pit 借鉴**：一站式产品形态

### 3.3 行业垂直范式（GameAnalytics/Unity/deltaDNA）
- 游戏专用事件 + 业务指标
- 7 类事件 + 13 个核心 KPI
- LiveOps 工作流（推送/Remote Config/A/B）
- **Pit 借鉴**：事件 Taxonomy + 游戏专业指标

## 4. GameAnalytics 标准 7 类事件（事实标准）

游戏行业事实标准，Pit 必须对齐：

| 事件类型 | 用途 | 典型字段 |
|---|---|---|
| **Session** | 自动 session start/end | `session_id`、`duration`、`ts` |
| **User** | 玩家属性/画像（性别、年龄、SDK 版本等） | `user_id`、`custom dimensions` |
| **Business（IAP）** | 真实货币购买；**服务端收据校验** | `cart_type`、`currency`、`amount`、`receipt` |
| **Resource** | 虚拟经济（货币的 source/sink） | `flow_type`(`Source`/`Sink`)、`currency_type`、`amount`、`item_type` |
| **Progression** | 关卡进度 | `level_start`/`level_complete`/`level_fail`/`level_restart`、`progression_01/02/03`（多级命名）、`score`、`attempt_num` |
| **Design（自定义）** | 业务自定义事件 | `category:sub_category:outcome` 命名 + `value` |
| **Error** | 崩溃/异常 | `severity`、`message`、`stacktrace` |

Pit 增补第 8 类：**Ad**（广告）—— 商业游戏必备

## 5. 13 个游戏核心指标（Pit 必备）

### 5.1 活跃与粘性
- **DAU / WAU / MAU**：单日/周/月活跃独立用户
- **Stickiness = DAU/MAU**（健康 20%+，顶级 50%+）
- **New / Returning / Resurrected**（≥30d 未活跃后回归）

### 5.2 留存（两种口径都要支持）
- **N-Day Retention（Bounded）**：第 N 天**当天**是否回来
- **Rolling Retention（Unbounded）**：到第 N 天**及以后**是否回来过
- **健康基准**：D1 ~35-40%，D7 ~15-20%，D30 ~5-10%（休闲游戏）

### 5.3 商业化
- **ARPU** = 总收入 / DAU
- **ARPDAU** = 当日 IAP + Ad 收入 / DAU
- **ARPPU** = 总收入 / 付费用户数
- **Conversion Rate** = 付费用户 / DAU
- **首次付费时长**（FTD Time to First Purchase）

### 5.4 LTV 与预测
- **公式**：`LTV = ARPDAU × Lifetime`（Lifetime 由留存曲线拟合）
- **预测 LTV（pLTV）**：用前 1-7 天数据 + 留存曲线 + 付费曲线训练
- **ROAS** = 收入 / UA 投放成本；**CAC** = UA 投放成本 / 新增
- 健康基准：**LTV:CAC ≥ 3:1**，**D7 ROAS ≥ 30%, D30 ≥ 60%, D90 ≥ 100%**

### 5.5 游戏特有指标
- **会话**：session 数、平均时长、每个 DAU 的 session 数
- **关卡**：通过率、首次通过率、失败次数分布、难度系数
- **虚拟经济**：每个 currency 的 source/sink 净流、玩家货币分布（P50/P90/P99）、通胀率
- **广告**：impressions、fill rate、eCPM、ARPU(Ad)
- **匹配/排位**：MMR/Elo 分布、匹配时长、对局胜率（应接近 50%）
- **社交**：公会成员数、活跃公会比例、好友连接数、社交参与度 → 留存相关性
- **UA 归因**：渠道、campaign、adgroup、creative 维度的 ROI/ROAS

## 6. 关键技术决策依据

| 设计决策 | 参考来源 | 原因 |
|---|---|---|
| 7 类事件 + Taxonomy | GameAnalytics | 游戏行业事实标准 |
| Postgres + ClickHouse 双库 | PostHog | 元数据 vs 事件分离，避免锁竞争 |
| ClickHouse Map 类型 + tenant 分区 | PostHog 多租户 | 避免 schema 爆炸 + 行级隔离 |
| Identity Merge 表 | Mixpanel `$identify` | 解决游戏游客→注册身份合并 |
| N-Day + Rolling 双留存 | Devtodev | 不同分析场景需要不同口径 |
| CUPED 显著性加速 | Microsoft/MetricLab | 减半 A/B 周期 |
| LTV = ARPDAU × Lifetime | GameAnalytics | 标准公式 |
| 插件化架构 | Countly | 保持核心精简，按需扩展 |
| 客户端只持 api_key | Stripe/PostHog | 安全反模式（不学 Mixpanel 早期 secret 嵌入） |

## 7. ClickHouse 多租户最佳实践（来自官方 + OneUptime 实战）

| 策略 | 适用 | 备注 |
|---|---|---|
| **共享表 + tenant_id 行级隔离** | 通用 SaaS 分析（推荐 Pit） | 1 张 events 表，所有查询带 tenant_id；配 RLS + 配额 |
| **每租户独立库** | 合规/隔离要求极高 | 运维成本高 |
| **混合**：热数据共享表 + 冷数据按租户分区 | 大规模 | Pit 起步可不做 |

**必须做的隔离**：
1. ClickHouse `Row Policy` 强制 `tenant_id` 过滤
2. `Quota`/`Workload` 限制单租户 query 数/内存，防 noisy neighbor
3. API 层（SDK 网关）每次写入都校验 AppKey → tenant
4. 元数据（租户、应用、API Key、用户、权限）放 Postgres，事件数据放 ClickHouse

## 8. 一句话总结

Pit 应当采用 **"GameAnalytics 事件体系 + PostHog 全栈产品架构 + Snowplow Schema 治理 + ClickHouse 多租户存储 + Countly 插件化扩展"** 的混合范式 —— 事件统一化、存储列存化、指标游戏化、租户隔离化、Schema 治理化。
