# Oddsmaker 平台重设计方案（索引）

本目录记录 Oddsmaker 游戏分析平台的新目标架构：**单公司部署、多游戏管理、多环境隔离、内置风控**。

## 设计边界

- Oddsmaker 面向一个游戏公司内部使用，不做对外 SaaS，也不做多公司共用。
- 平台只有一个公司上下文；公司信息属于部署配置，不进入事件主键、分区键或权限模型。
- 业务隔离维度只有 `game_id` 和 `environment`，其中 `environment` 通常为 `dev`、`staging`、`prod`。
- 用户权限围绕游戏和环境授权，不再设计 `Organization/Tenant`。
- 风控是核心链路，不是后期插件：采集、实时计算、告警、处置和审计都必须内建。

## 文档结构

| 文档 | 内容 |
|---|---|
| [01-assessment.zh.md](./01-assessment.zh.md) | 现状评估：当前代码和文档中多租户/Project/Game 混杂的问题 |
| [02-design-issues.zh.md](./02-design-issues.zh.md) | 关键设计问题：必须移除的复杂度和必须补齐的游戏能力 |
| [03-open-source-reference.zh.md](./03-open-source-reference.zh.md) | 对标参考：只吸收架构和分析能力，不复制 SaaS 多租户模型 |
| [04-redesign.zh.md](./04-redesign.zh.md) | 新架构：单公司多游戏、事件模型、数据链路、风控体系 |
| [05-roadmap.zh.md](./05-roadmap.zh.md) | 实施路线：先统一模型，再补游戏分析和风控 |

## 一句话结论

Oddsmaker 不应该做“多公司共享的一套分析 SaaS”。更真实的场景是一个游戏公司自建或私有化部署，用同一套平台服务多个游戏、多个环境和多个团队角色。

## 新架构核心理念

**GameAnalytics 事件体系 + ClickHouse 实时分析 + Flink 状态计算 + 单公司多游戏控制面 + 内置风控闭环**

- 事件主键：`game_id + environment + event_id`
- 查询分区：`game_id + environment + event_date`
- 权限边界：用户角色绑定到游戏或环境
- 配置边界：API Key、Tracking Plan、采样、PII、风控策略按游戏环境下发
- 风控闭环：规则/模型检测 → 风险事件表 → 告警 → 处置动作 → 审计回溯

## P0 立即修正

1. 移除目标架构中的 `tenant_id`、`Organization`、`org_id` 语义，统一为 `game_id + environment`。
2. 废弃 `Project` 概念，避免 `Project/Game/App/Tenant` 四套名词并存。
3. 客户端 SDK 仅持 public `api_key`，HMAC 只允许 Server SDK 使用。
4. 在 Gateway/Flink/ClickHouse 设计中加入风控链路，而不是只做离线报表。
5. 控制面改为：游戏、环境、API Key、Tracking Plan、风控策略、用户角色。

## 历史材料

以下文件保留为历史评估，不代表新的目标架构：

- [oddsmaker_completion_assessment.md](../../oddsmaker_completion_assessment.md)
- [oddsmaker_technical_recommendations.md](../../oddsmaker_technical_recommendations.md)
- [oddsmaker_quick_summary.md](../../oddsmaker_quick_summary.md)
- [ASSESSMENT_INDEX.md](../../ASSESSMENT_INDEX.md)
