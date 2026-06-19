# 03 - 参考项目取舍

本章只讨论 Oddsmaker 应该借鉴哪些能力。**不要照搬开源分析产品的 SaaS 多租户模型**。

## 1. 参考矩阵

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

## 5. 架构结论

Oddsmaker 的目标范式是：

**单公司多游戏控制面 + 游戏事件 Taxonomy + 实时风控流处理 + ClickHouse 分析存储 + 可治理 Tracking Plan**

核心分区和权限都应围绕 `game_id + environment`，而不是 `tenant_id`。
