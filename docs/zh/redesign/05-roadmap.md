# 05 - 实施路线

路线目标：先把模型改正确，再补游戏分析和风控。每个阶段都必须能独立发布。

## 阶段总览

| 阶段 | 周期 | 目标 | 交付物 |
|---|---|---|---|
| P0 | 2 周 | 去多租户、统一命名、安全修复 | `game_id + environment` 目标契约、客户端移除 HMAC、迁移方案 |
| P1 | 3-4 周 | 单公司多游戏控制面 | Game/Environment/API Key/Role/Policy API |
| P2 | 4-6 周 | 游戏事件化和核心分析 | 事件 v1、SDK typed API、留存、漏斗、Identity |
| P3 | 4-6 周 | 风控闭环 | 风控规则、risk job、risk_events、告警和处置 |
| P4 | 6-8 周 | 商业化与智能分析 | LTV、广告、实验统计、Crash、预测 |

## P0 去多租户和安全修正

### P0.1 明确目标字段

任务：

- 目标事件契约改为 `game_id + environment`。
- 文档、schema、ClickHouse DDL 不再使用 `tenant_id`。
- 兼容层允许短期读取旧字段：
  - `project_id -> game_id`
  - `app_id -> game_id + environment` 解析
  - `tenant_id` 忽略或仅用于历史迁移映射

验收：

- 新文档没有把 `Organization/Tenant` 作为目标架构。
- 新 schema 和 DDL 以 `game_id + environment` 为主键边界。

### P0.2 移除客户端 HMAC

任务：

- Android/iOS/Unity/Web SDK 不允许配置 HMAC secret。
- Server SDK 保留 HMAC。
- Gateway 区分 client key 和 server key。

验收：

- 客户端请求只包含 public `x-api-key`。
- 任何客户端 SDK 不包含 `secret`、`HmacSHA256` 签名逻辑。

### P0.3 制定代码迁移兼容层

任务：

- Gateway 解析旧字段并输出新事件模型。
- Avro/JSON Schema 发布 v1。
- ClickHouse 新建规范化事件表，不原地破坏旧表。
- Flink 作业支持从新 topic 消费。

验收：

- 旧 SDK 能继续上报。
- 新 SDK 能直接上报 `game_id + environment`。
- 新旧数据可在查询层统一。

## P1 单公司多游戏控制面

### P1.1 游戏与环境管理

任务：

- 保留 `Game` 和 `GameEnvironment`。
- 删除目标 API 中的 `Organization`。
- 游戏字段聚焦：名称、类型、平台、时区、状态、默认货币、数据保留。
- 环境字段聚焦：`dev/staging/prod`、采样、限流、Schema 策略、风控策略。

验收：

- 可以创建游戏和环境。
- 每个环境可独立配置 API Key、Tracking Plan、PII、风控规则。

### P1.2 API Key 管理

任务：

- API Key 绑定 `(game_id, environment)`。
- Key 类型：`client`、`server`、`admin`。
- client key 只允许写事件。
- server key 可写服务端事件、支付校验、风控反馈。

验收：

- Gateway 根据 key 自动补齐或校验 `game_id + environment`。
- 跨游戏、跨环境写入被拒绝。

### P1.3 公司内 RBAC

任务：

- 全局角色：`admin`、`user`。
- 范围角色：`owner`、`operator`、`analyst`、`developer`、`risk_admin`、`viewer`。
- Scope：`global`、`game`、`environment`。

验收：

- 用户只能访问授权游戏和环境。
- 风控策略只有 `risk_admin` 或 `owner` 能改。

## P2 游戏事件化和核心分析

### P2.1 事件 v1

任务：

- 扩展 Avro/JSON Schema。
- SDK 提供类型化 API：
  - `session`
  - `user`
  - `business`
  - `resource`
  - `progression`
  - `design`
  - `error`
  - `ad`
  - `risk`
- Tracking Plan 校验事件名和属性。

验收：

- 事件名符合 `category:subject:action`。
- 未登记字段可按策略拒绝、丢弃或进入 DLQ。

### P2.2 Identity Merge

任务：

- 新增 `identities` 表。
- 支持 `device_id/user_id/player_id/character_id` 归并。
- SDK 提供 `identify` 和 `setPlayer`。

验收：

- 游客转账号后，留存和 LTV 不重复计算。

### P2.3 留存和漏斗

任务：

- 留存支持 N-Day 和 Rolling。
- 漏斗支持 N 步、时间窗、有序/无序。
- 所有聚合按 `game_id + environment`。

验收：

- 可配置新手 6 步漏斗。
- 可查询 D1/D3/D7/D14/D30/D60/D90 留存。

## P3 风控闭环

### P3.1 风控规则管理

任务：

- Control Service 新增 RiskRule API。
- 支持阈值、黑白名单、速度、序列、模型规则。
- 规则按游戏环境发布。

验收：

- `prod` 和 `staging` 可配置不同规则。
- 每次规则变更写审计日志。

### P3.2 实时风控作业

任务：

- Flink risk job 消费 `events_validated`。
- 检测：
  - 高频事件
  - 异常资源 source/sink
  - 重复收据
  - 多设备多 IP 异常登录
  - 广告 reward 异常
  - 关卡耗时异常
- 输出 `oddsmaker.risk_events` 和 ClickHouse `risk_events`。

验收：

- 策略命中延迟小于 10 秒。
- 每条 risk event 包含 rule、subject、score、evidence、action。

### P3.3 风控处置和告警

任务：

- Dashboard 展示风险趋势、严重等级、命中规则。
- Webhook 通知游戏服。
- 支持 block/review/mark/throttle。

验收：

- 支付收据复用可自动 block。
- 资源异常可 alert 并进入审核队列。

## P4 商业化与智能分析

### P4.1 LTV 和广告分析

任务：

- Cohort LTV。
- IAP + Ad 收入统一。
- 广告 impression/click/reward/eCPM。
- 支付和广告风控联动。

### P4.2 实验平台

任务：

- 实验配置、分桶、目标事件。
- 显著性、置信区间、SRM。
- 风控过滤作弊样本，避免污染实验结论。

### P4.3 Crash/Error

任务：

- Error 事件。
- stack hash 聚合。
- dSYM/Proguard mapping/source map 管理。
- 按版本、设备、国家统计影响用户数。

### P4.4 预测模型

任务：

- 流失预测。
- pLTV。
- 付费倾向。
- 风险评分模型。

## 成功指标

- 采集入口 P95 延迟 < 100ms。
- 事件进入 ClickHouse P50 < 5s。
- 风控命中输出 P95 < 10s。
- 查询常用指标 P95 < 2s。
- 支持单公司多游戏，单游戏千万级 DAU。
- 事件字段和 Tracking Plan 变更可审计、可回滚。

## 当前最优下一步

先做 P0：把目标字段、schema、DDL、SDK 参数和文档统一到 `game_id + environment`。这一步不完成，后续任何控制面、风控、分析能力都会继续被 `Project/Tenant/App` 混乱拖住。
