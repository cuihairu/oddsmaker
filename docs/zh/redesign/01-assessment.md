# 01 - 现状评估

## 1. 项目定位偏差

当前文档把旧项目定位成“企业级多租户游戏数据分析平台”，但这个方向和真实私有化游戏公司场景不匹配。

真实场景通常是：

- 一家公司有多个游戏。
- 每个游戏有多个环境：`dev`、`staging`、`prod`。
- 同一家公司内部有运营、策划、数据、技术、客服、风控团队。
- 数据不需要和其他公司共用同一套逻辑租户，只需要公司内部按游戏和环境隔离。

因此，新的目标定位应为：**单公司多游戏实时分析与风控平台**。

## 2. 当前命名混乱

代码和文档里同时出现了多套边界概念：

| 概念 | 当前状态 | 问题 |
|---|---|---|
| `Project` | 老控制面、部分 SDK、网关测试仍在使用 | 含义不清，可能指游戏，也可能指应用 |
| `tenant_id` | 新 schema 和 ClickHouse DDL 使用 | 暗示多公司共享，不符合目标 |
| `Organization/org_id` | Control Service 里有大量 JPA 实体和 migration | 把公司做成租户对象，增加无意义复杂度 |
| `app_id` | 被定义为 `game_id + environment` | 可以保留思想，但字段名不如显式 `game_id + environment` 清楚 |
| `Game` | Control Service 已有实体 | 应成为核心实体 |
| `Environment` | Control Service 已有实体 | 应成为游戏下的核心配置边界 |

新的模型必须只保留：

- `game_id`
- `environment`
- `api_key`
- `user_id/player_id/device_id`
- `role_scope = global | game | environment`

## 3. 技术栈盘点

| 层 | 选型 | 评价 |
|---|---|---|
| 采集 | Spring Boot 3 WebFlux + Netty | 高吞吐入口选型正确 |
| 消息 | Kafka + Apicurio Schema Registry | 适合自建事件管道 |
| 实时计算 | Flink 1.19 | 适合会话、留存、漏斗、风控状态计算 |
| 存储 | ClickHouse | 游戏事件和聚合查询的正确选择 |
| 元数据 | JPA/Hibernate | 可承载游戏、环境、API Key、策略配置 |
| 缓存 | Redis | 适合限流、实时计数、风控短窗状态 |
| BI | Superset | 可作为初期分析看板 |
| SDK | Web/Android/iOS/Unity | 基础形态有了，但字段和安全策略需要统一 |

## 4. 当前能力缺口

| 能力域 | 当前状态 | 新目标 |
|---|---|---|
| 基础采集 | 已具备 Gateway → Kafka → Flink → ClickHouse 骨架 | 保留并统一字段 |
| 多游戏管理 | `GameEntity` 存在但未成为唯一核心 | 控制面以 Game 为中心 |
| 环境隔离 | 有 `GameEnvironmentEntity` | 事件、配置、密钥、风控策略都按环境隔离 |
| 多租户 | 设计过重且方向错误 | 从目标架构移除 |
| 游戏事件模型 | 通用事件字段为主 | 引入 Session/User/Business/Resource/Progression/Design/Error/Ad |
| 风控 | 只有限流、PII、异常检测概念 | 建立实时风控链路和处置闭环 |
| 留存/漏斗 | 留存固定口径，漏斗仅 2 步 | N-Day/Rolling 留存和 N 步漏斗 |
| Identity Merge | 缺失 | 支持游客、设备、账号、角色合并 |
| A/B 实验 | 曝光事件为主 | 配置、分桶、SRM、显著性、风控联动 |
| Crash/Error | 只有字段或开关 | 错误事件、堆栈聚合、影响用户数 |

## 5. 必须保留的正确设计

- Kafka + Flink + ClickHouse 的主链路。
- `event_id` 去重和 DLQ 旁路。
- JSON Schema/Avro 双契约。
- API Key、限流、PII 治理。
- Superset 作为初期 BI 输出。

## 6. 必须调整的方向

- 不再把 `Organization` 当成业务实体。
- 不再把 `tenant_id` 放入事件 schema、ClickHouse 分区和查询条件。
- 不再做跨公司 Row Policy、租户配额、租户计费。
- 把“公司内部多游戏”建模为一等能力。
- 把风控作为采集和实时计算主链路的一部分。
