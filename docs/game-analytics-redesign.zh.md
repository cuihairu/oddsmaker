# complier 游戏数据分析项目重设计

更新时间：`2026-06-05`

## 1. 命名与目标

`complier` 取自赌场语境中的职业名，用来表达这个项目的核心意图：不是被动存日志，而是持续观察玩家行为、局内变化、收益结构和风险信号，并把这些信息转成运营决策。

当前仓库已逐步统一到 `Oddsmaker` 命名；早期 `Pit` 代号只作为历史迁移背景，不再作为新的 SDK、Schema 或包名。

项目目标也因此需要从“通用事件平台”收敛为“游戏团队的数据工作台”：

- 给制作人看经营结果和版本变化。
- 给数值和关卡策划看流失点、卡点和经济平衡。
- 给运营看活动、分群、实验和召回。
- 给技术团队看崩溃、性能和异常行为。

## 2. 为什么需要重设计

现有仓库在基础设施层已经比较完整：

- 采集链路完整：`SDK -> Gateway -> Kafka -> Flink -> ClickHouse -> Superset`
- 多端 SDK 已有雏形：`Web / Android / iOS / Unity`
- 实时计算链路已具备：富化、会话、留存、漏斗
- BI 资产已落地：Superset bundle 和 starter SQL

但现阶段仍然偏“平台能力”，离游戏分析产品还差 3 件事：

1. 缺少明确的游戏语义：教程、关卡、虚拟经济、广告、商城、活动这些对象还没有被固化成默认模型。
2. 缺少角色工作台：制作人、运营、策划、分析师看到的不是一套面向职责的工作界面。
3. 缺少运营闭环：分析、分群、实验、配置下发和复盘还没有形成单条链路。

## 3. 产品方向

### 3.1 五个核心工作台

| 工作台 | 解决的问题 | 必备指标 | 需要的产品动作 |
| --- | --- | --- | --- |
| 增长与买量 | 哪些渠道和素材带来高质量玩家 | CAC, ROAS, 回收周期, 首日付费率 | 渠道对比、素材归因、回收看板 |
| 新手与进度 | 玩家在哪一步掉队，为什么掉 | 教程完成率, 首关通过率, 关卡失败率, 平均尝试次数 | 漏斗、关卡热力、失败原因分布 |
| 经济与变现 | 收入来自哪里，虚拟经济是否健康 | ARPU, ARPPU, 付费转化率, ILRD, 货币产消比 | 商城分析、货币水位、礼包和广告位效果 |
| LiveOps 与实验 | 哪种配置、活动或数值改动更有效 | uplift, 留存变化, 收入变化, 活动参与率 | 玩家分群、A/B 测试、Remote Config |
| 稳定性与风控 | 技术问题和异常行为是否影响体验与收入 | crash-free users, FPS, 启动时长, 网络错误率, cheat flags | 异常告警、版本对比、可疑玩家筛查 |

### 3.2 预置事件字典

建议把 SDK 和服务端事件统一收敛到一套预置事件字典：

- 身份与会话：`install`, `login`, `session_start`, `session_end`
- 新手与进度：`tutorial_start`, `tutorial_complete`, `level_start`, `level_fail`, `level_complete`
- 经济系统：`currency_source`, `currency_sink`, `item_grant`, `item_consume`
- 变现：`iap_order`, `ad_impression`, `rewarded_ad_complete`, `webshop_order`
- 社交与活动：`guild_join`, `invite_sent`, `event_entry`, `event_reward_claim`
- 稳定性与风险：`crash`, `fps_drop`, `network_timeout`, `cheat_flag`

策略上推荐采用“预置核心事件 + 自定义扩展字段”：

- 核心字段固定，保证跨游戏可对比。
- 业务字段在链路内统一编码到 `props_json`，避免表结构爆炸。
- 金额和资源数量字段使用 Decimal/Double 语义，避免道具、虚拟币和广告收入的小数精度丢失。
- 重点对象单独建宽表或物化视图，如 `level_progress`, `economy_flows`, `monetization_daily`。

字典草案见 [game-event-dictionary.zh.md](game-event-dictionary.zh.md)。

## 4. 对标案例

下面这些例子分成两类：成熟游戏分析产品，以及可借鉴的开源或自建型底座。

### 4.1 成熟游戏分析产品

| 项目 | 参考价值 | 对 `complier` 的启发 | 官方链接 |
| --- | --- | --- | --- |
| GameAnalytics | 典型的游戏专用分析 SaaS，覆盖预置 dashboard、funnel、cohort、progression、monetization、health、benchmark | 我们应该内置游戏专属语义，而不是只给一个事件表和 SQL 编辑器 | [Overview](https://docs.gameanalytics.com/) / [AnalyticsIQ Overview](https://docs.gameanalytics.com/products-and-features/analytics-iq/overview/) |
| GameAnalytics Monetization | 支持 IAP、广告 ILRD、webshop，并强调 purchase validation | 变现分析必须同时覆盖 IAP、广告和 Webshop，且要有收入可信度校验 | [Monetization](https://docs.gameanalytics.com/products-and-features/analytics-iq/monetization) / [Purchase Validation](https://docs.gameanalytics.com/advanced-tracking/purchase-validation) |
| Unity Analytics | 官方强调 core metrics、A/B testing、push、audiences、funnels | 分析不该停在看板，应和分群、触达、实验联动 | [Overview](https://docs.unity.com/en-us/analytics) / [Funnels](https://docs.unity.com/en-us/analytics/funnels/funnels) / [Audiences](https://docs.unity.com/en-us/analytics/audiences/audiences) |
| Unity Game Overrides | A/B 测试与配置发布直接连接，且带统计显著性 | 后续实验模块应直接挂在配置分发能力上，不做孤立报表 | [A/B testing](https://docs.unity.com/en-us/game-overrides/ab-testing) |
| PlayFab | 强调 PlayStream 事件、实时事件处理、玩家分群和 LiveOps | 可借鉴“事件是 LiveOps 基础设施”的思想，而不是把分析和运营拆开 | [What is PlayFab](https://learn.microsoft.com/en-us/gaming/playfab/get-started/what-is-playfab) / [PlayStream overview](https://learn.microsoft.com/en-us/gaming/playfab/data-analytics/ingest-data/playstream-overview) / [Player segments](https://learn.microsoft.com/en-us/gaming/playfab/player-progression/player-data/player-segments) |

关于 PlayFab 需要单独说明一件事：微软当前官方文档写明 `PlayFab Insights Management` 将在 `2026-03-31` 退役，并建议转向 `Data Connections` 来管理性能与成本。因此它更适合作为“事件和运营一体化”的参考，而不适合作为我们要照搬的分析后端形态。

参考：

- [What is PlayFab Insights](https://learn.microsoft.com/en-us/gaming/playfab/data-analytics/legacy/insights/overview)

### 4.2 开源或自建型参考

| 项目 | 参考价值 | 对 `complier` 的启发 | 官方链接 |
| --- | --- | --- | --- |
| Countly | 自托管、插件化、Write API、多端 SDK、看板、告警、Remote Config、权限管理 | 适合作为“私有化分析产品”的形态参考，但要注意 Lite/Enterprise 的授权边界 | [Countly GitHub](https://github.com/Countly/countly-server) / [How Countly Works](https://support.countly.com/hc/en-us/articles/900004373266-How-Countly-Works) |
| PostHog | 把 product analytics、feature flags、experiments、warehouse 放到一个工作区里 | 值得借鉴“同一套数据上做分析和实验”的体验，但不建议直接拿它做高吞吐游戏事件主存储 | [PostHog GitHub](https://github.com/posthog/posthog) / [PostHog](https://posthog.com/) |
| GrowthBook | 强调 warehouse-native experimentation、SQL 指标透明、轻量 SDK、本地评估 | 我们的实验模块应直接复用 ClickHouse 指标体系，而不是再造一个数据孤岛 | [GrowthBook Docs](https://docs.growthbook.io/) / [GrowthBook GitHub](https://github.com/growthbook/growthbook) |
| ClickHouse | 官方已经把 gaming analytics 和 telemetry 作为明确场景，强调高并发查询、高吞吐写入、OTel 支持 | 继续把 ClickHouse 作为核心事实层是合理的，后续只需要补齐更强的游戏主题数据集 | [Gaming Analytics](https://clickhouse.com/industries/gaming) / [ClickHouse](https://clickhouse.com/clickhouse) |

## 5. 设计取舍

综合上面的案例，建议 `complier` 采用下面这组取舍：

### 5.1 要借鉴的

- 借鉴 `GameAnalytics` 和 `Unity Analytics` 的预置游戏看板能力。
- 借鉴 `PlayFab` 的事件优先和 LiveOps 思路。
- 借鉴 `Countly` 的自建、自托管、插件化管理能力。
- 借鉴 `PostHog` 和 `GrowthBook` 的“分析 + 实验”一体化体验。
- 借鉴 `ClickHouse` 的高吞吐实时分析底座。

### 5.2 不要照搬的

- 不要只做通用埋点和 SQL 平台，这会失去游戏产品差异化。
- 不要把实验统计锁在单独服务里，实验必须复用统一指标口径。
- 不要把分析和运营完全分离，最终用户需要的是“看见问题后能立刻动作”。
- 不要过度依赖单一云厂商托管分析面，尤其是已有废弃历史的能力。

## 6. 建议的实现路径

### Phase 1：游戏语义固化

- 固化预置事件字典和参数规范。
- 补 `level`, `economy`, `shop`, `ads`, `liveops` 主题表或物化视图。
- 在 SDK 里提供游戏事件 helper，而不是只暴露原始 track API。

### Phase 2：三个一线工作台

- 新手与进度工作台
- 经济与变现工作台
- LiveOps 与实验工作台

这三个模块最贴近制作人、策划和运营的日常工作，优先级最高。

### Phase 3：分群、策略和实验闭环

- 玩家分群服务
- Remote Config 或配置覆盖能力
- 实验分流、指标归因、结果复盘

### Phase 4：技术健康与异常检测

- crash、FPS、网络、启动时间
- 作弊与异常行为识别
- 面向运营和技术团队的实时告警

## 7. 仓库内建议落点

为了让这次重设计能映射到现有代码，建议后续优先改这些目录：

- `schema/avro/`：补游戏预置事件 schema
- `schema/sql/clickhouse/`：补游戏主题明细表和聚合视图
- `jobs/flink/`：把关卡、经济、广告、实验曝光做成独立作业或算子
- `services/control-service/`：补分群、实验、配置管理 API
- `bi/superset/`：按工作台重建 dashboard，而不是只堆通用图表
- `sdks/`：补预置事件 helper、实验曝光、技术健康采集

## 8. 一句话结论

`complier` 最有价值的方向，不是成为另一个“埋点平台”，而是成为一套面向游戏制作、运营和数值团队的自建数据操作系统：用统一事件底座连接进度、经济、变现、实验和技术健康，再把这些信号变成可以执行的运营动作。
