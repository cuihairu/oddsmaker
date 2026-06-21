# Oddsmaker 游戏分析平台产品路线图

目标：建设一个游戏公司内部可用的多游戏实时分析与风控平台。平台不做多公司共用，也不把 Organization/Tenant 作为业务模型。

## 当前判断

已经具备的基础：

- SDK → Gateway → Kafka → Flink → ClickHouse 主链路。
- Web/Android/iOS/Unity SDK 雏形。
- 基础限流、PII、Schema、HMAC 能力。
- Flink 富化、会话、留存、漏斗作业骨架。
- Superset 基础看板。

必须纠正的问题：

- `Project`、`tenant_id`、`app_id`、`Organization` 概念混杂。
- 多租户方向不符合单游戏公司私有化使用场景。
- 游戏专业事件字段不足。
- 风控不是一等模块。
- 客户端 HMAC secret 存在安全风险。

## Phase 1：模型统一与安全修复

目标：把平台主语改成 `game_id + environment`。

交付：

- 事件契约 v1：`game_id`、`environment`、`event_type`、`event_name`。
- ClickHouse 新表按 `(game_id, environment, event_date)` 分区。
- Gateway 兼容旧字段并输出新模型。
- 客户端 SDK 移除 HMAC secret。
- Server SDK 保留 HMAC。
- 文档和 API 不再把多租户作为目标能力。

## Phase 2：单公司多游戏控制面

目标：服务一个公司内部多个游戏和多个团队角色。

交付：

- Game CRUD。
- Environment CRUD。
- API Key 管理，绑定 `(game_id, environment)`。
- Tracking Plan 管理。
- PII/采样/限流策略。
- 公司内 RBAC：`global | game | environment` 范围。
- 审计日志。

不做：

- Organization CRUD。
- 租户套餐。
- 跨公司 Row Policy。
- 多租户计费。

## Phase 3：游戏专业分析

目标：从通用埋点平台变成真正的游戏分析平台。

交付：

- 标准事件：session、user、business、resource、progression、design、error、ad。
- Identity Merge：设备、账号、玩家、角色合并。
- N-Day 和 Rolling 留存。
- N 步可配置漏斗。
- IAP 和广告收入聚合。
- 关卡通过率、失败率、尝试次数。
- 虚拟经济 source/sink 和通胀监控。

## Phase 4：风控闭环

目标：实时识别作弊、支付欺诈、广告异常和经济异常，并输出可执行处置。

交付：

- RiskRule 管理。
- Gateway 黑名单、重放、超限、非法环境硬拦截。
- Flink risk job。
- ClickHouse `risk_events`、`risk_scores`。
- 风控大屏。
- Webhook 到游戏服。
- 审核队列和处置审计。

重点规则：

- 高频事件和脚本行为。
- 重复收据和异常付费。
- 多设备、多 IP、异地登录。
- 虚拟货币异常增长。
- 广告 reward 异常。
- 关卡耗时和战斗结果异常。

## Phase 5：实验、Crash 和智能化

交付：

- A/B 实验配置、分桶、目标事件、SRM、显著性。
- Crash/Error 收集、符号化和 stack hash 聚合。
- Cohort LTV 和 pLTV。
- 流失预测。
- 风险评分模型。
- Remote Config 和 LiveOps 联动。

## 成功指标

- 采集入口 P95 < 100ms。
- 事件可查询 P50 < 5s。
- 风控命中输出 P95 < 10s。
- 常用指标查询 P95 < 2s。
- 支持单公司多游戏，单游戏千万级 DAU。
- 风控策略命中可解释、可审计、可回放。

## 下一步

立即做 Phase 1：统一 `game_id + environment`。在这个基础没统一之前，不继续扩展 Organization、多租户或 Project 相关能力。
