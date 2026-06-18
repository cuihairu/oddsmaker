# Pit 平台重设计方案（索引）

本目录系统化记录 Pit 游戏分析平台的现状评估、设计问题、开源对标和重设计方案。

## 文档结构

| 文档 | 内容 |
|---|---|
| [01-assessment.zh.md](./01-assessment.zh.md) | 现状评估：项目结构、技术栈、模块完成度 |
| [02-design-issues.zh.md](./02-design-issues.zh.md) | 设计问题清单：14 个关键缺陷分级 |
| [03-open-source-reference.zh.md](./03-open-source-reference.zh.md) | 开源对标：PostHog/Plausible/Countly/GameAnalytics 等 |
| [04-redesign.zh.md](./04-redesign.zh.md) | 重设计方案：架构、数据模型、事件 Taxonomy |
| [05-roadmap.zh.md](./05-roadmap.zh.md) | 实施路线：P0 → P4 分阶段落地 |

## 一句话结论

Pit 的**基础设施选型正确**（Spring Boot 3 + Kafka + Flink + ClickHouse 是事实标准），但**"游戏分析平台"的定位未兑现**：事件模型通用化、缺失全部游戏核心能力（关卡、虚拟经济、广告、Identity Merge、N 步漏斗、双留存、LTV、A/B 显著性、Crash）。

## 重设计核心理念

**"GameAnalytics 事件 Taxonomy + PostHog 全栈架构 + Snowplow Schema 治理 + ClickHouse 多租户存储 + Countly 插件化扩展"**

- 事件统一化（7 类标准事件）
- 存储列存化（Postgres 元数据 + ClickHouse 事件 + S3 归档）
- 指标游戏化（13 个核心游戏指标）
- 租户隔离化（Org → Game → Env → App）
- Schema 治理化（Tracking Plan + CI 校验）

## P0 立即修复项

1. Android SDK 移除客户端 HMAC secret 嵌入（安全漏洞）
2. 废弃 `ProjectEntity`，统一 `tenant_id + app_id` 语义
3. 启用 `.disabled` 控制器，接通多租户 DB
4. PII 治理代码化（Gateway 落地 deny/mask/coarse）

## 历史评估文档

- [pit_completion_assessment.md](../../pit_completion_assessment.md) - v0.1.x 早期完成度评估
- [pit_technical_recommendations.md](../../pit_technical_recommendations.md) - 早期技术建议
- [pit_quick_summary.md](../../pit_quick_summary.md) - 早期快速摘要
- [ASSESSMENT_INDEX.md](../../ASSESSMENT_INDEX.md) - 评估索引
