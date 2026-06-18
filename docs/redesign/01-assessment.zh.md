# 01 - 现状评估

## 1. 项目定位

Pit 自定位为"企业级多租户游戏数据分析平台"，采用纯 Java 技术栈 Monorepo，目标对标 Unity Analytics / deltaDNA / GameAnalytics。

## 2. 技术栈盘点

| 层 | 选型 | 评价 |
|---|---|---|
| 采集 | Spring Boot 3 WebFlux + Netty（响应式） | ✅ 高吞吐正确选型 |
| 消息 | Kafka 3.7 + Apicurio Schema Registry（Avro） | ✅ 自管场景正确 |
| 流计算 | Flink 1.19 | ✅ 4 个作业骨架已就位 |
| 存储（事件） | ClickHouse 24 | ✅ 列存事实标准 |
| 存储（元数据） | JPA/Hibernate（疑似 H2/PostgreSQL） | 🟡 实际驱动未明确 |
| 缓存 | Redis 7 | ✅ 限流/计数 |
| BI | Apache Superset 3.0.2 | 🟡 默认 90 天，非实时 |
| 监控 | OpenTelemetry + Prometheus + Grafana | ✅ 完整 |
| SDK | Web(TS) / Android(Kotlin) / iOS(Swift) / Unity / Server | 🟡 4 套实现，字段不完全统一 |

## 3. 模块盘点（按实际代码统计）

### 3.1 控制面 `services/control-service/`

```
jpa/                         JPA 实体（多租户模型，约 1300 行）
  OrganizationEntity.java    组织（含 tier、配额、合规字段）
  GameEntity.java            游戏（含 genre、platforms、虚拟货币等）
  GameEnvironmentEntity.java 环境（dev/staging/prod）
  UserEntity.java            用户（含 RBAC、2FA、登录锁定）
  UserRoleEntity.java        用户角色（多对多）
  UserInvitationEntity.java  邀请
  ApiKeyEntity.java          API Key（含 PII 策略、配额、自动轮换）
  ProjectEntity.java         ⚠️ 仅 13 行裸实体（id+name），与多租户模型并行

api/                         旧版控制器（启用）
  ApiController.java         Project/Key CRUD（基于 ProjectEntity）
  ControlService.java        Service 层（依赖 ProjectRepo）
  MemoryStore.java           内存实现，未接 DB

controller/                  ❌ 8 个 .disabled 文件，新版控制器未启用
  AuthController.java.disabled
  UserController.java.disabled
  OrganizationController.java.disabled
  GameController.java.disabled
  ApiKeyController.java.disabled
experiment/                  ❌ ExperimentController.disabled + JsonSchemaService.disabled
```

### 3.2 网关 `services/gateway-service/`

```
security/
  HmacFilter.java          HMAC-SHA256 签名校验（300s 时间窗）
  RateLimitFilter.java     双维度限流（apiKey + IP）
  RequestIdFilter.java     x-request-id 透传
api/
  BatchController.java.disabled   ❌ 真正的 batch 处理控制器未启用
```

### 3.3 Flink 作业 `jobs/flink/`

| 作业 | 行数 | 完成度 | 备注 |
|---|---|---|---|
| events-enrich-job | ~315 | 90% | 去重 + GeoIP + UA + DLQ → ClickHouse |
| sessions-job | ~156 | 85% | 30 min gap 会话切分 |
| retention-job | ~155 | 70% | 仅固定 0/1/7/30 天 |
| funnels-job | - | 60% | 仅 2 步 |

### 3.4 公共库 `libs/`

| 模块 | 内容 |
|---|---|
| common-model | `Event.java`（20 行 POJO，无游戏字段） |
| common-auth | `HmacSigner` |
| common-kafka | `KafkaProducers` |
| common-otel | `OtelInit` |

### 3.5 Schema `schema/`

```
avro/
  pit-event.avsc                ❌ 13 个通用字段，零游戏字段
json/
  pit-event-schema.json         JSON Schema
  experiment-config.schema.json 实验 Targeting/Variant
sql/clickhouse/
  schema.sql                    events + sessions + 5 物化视图
  queries.sql                   示例查询
  queries_experiment*.sql       实验 SQL
```

### 3.6 SDK `sdks/`

| SDK | 行数 | 核心能力 | 已知问题 |
|---|---|---|---|
| Web (TS) | ~380 | batching + gzip + offline + A/B + targeting | 客户端无 HMAC（正确） |
| Android (Kotlin) | ~400+ | 同 Web | ❌ **客户端嵌入 HMAC secret（漏洞）** |
| iOS (Swift) | 未审计 | 类似 | 待评估 |
| Unity | 待补 | - | 仅目录占位 |

### 3.7 BI `bi/superset/`

```
bundle/
  dashboards/         pit_overview + pit_experiments
  charts/             DAU、事件趋势、收入、平台/版本/国家分布、实验转化
  databases/datasets/ ClickHouse 连接配置
```

## 4. 完成度热力图

| 能力域 | 完成度 | 备注 |
|---|---|---|
| 基础采集链路 | ✅ 90% | Gateway → Kafka → Flink → ClickHouse 通了 |
| 多租户模型（DB 层） | 🟡 80% | JPA 实体完整，但控制器 disabled，未接通 |
| 多租户模型（事件层） | 🔴 30% | 事件仍用 project_id，未对齐 org+game+env |
| Schema 治理 | 🟡 60% | Avro + JSON Schema 有，但无 Tracking Plan |
| 实时计算 | 🟡 60% | 4 个 Flink 作业，无 5min 大屏、无异常检测 |
| ClickHouse 聚合 | 🟡 70% | DAU/收入/事件，无 LTV、留存仅有 0/1/7/30 |
| 游戏专业指标 | 🔴 20% | 无关卡、虚拟经济、广告、匹配、社交 |
| 漏斗 | 🔴 60% | 仅 2 步，硬编码 |
| 留存 | 🟡 50% | 单口径，无 N-Day/Rolling/分层 |
| 实验平台 | 🔴 30% | 仅曝光事件，无配置/显著性/SRM |
| Identity Merge | 🔴 0% | user_id 与 device_id 平行，无合并 |
| Crash 报告 | 🔴 0% | 仅有字段开关 |
| PII 治理 | 🟡 40% | DB 字段齐备，Gateway 代码未落地 |
| 限流 | ✅ 80% | 双维度可配 |
| HMAC 鉴权 | 🟡 70% | Gateway 校验完整，Android 实现错误 |
| BI 集成 | 🟡 70% | Superset 看板有，无实时大屏 |
| 审计日志 | 🔴 10% | 仅 `ApiKeyEntity.lastUsed*` 字段 |
| 计费与配额 | 🟡 30% | DB 字段有，扣减逻辑未实现 |

## 5. 关键设计正确之处

| 项 | 评价 |
|---|---|
| 事件 ID 选 UUIDv7 | 时间有序 + 唯一，完美适配 Flink 去重 |
| Flink 去重方案 | KeyedProcess + ValueState + 7d TTL，标准做法 |
| Schema Registry 选 Apicurio | 自管场景优于 Confluent |
| DLQ 旁路 | 死信主题 + JSON 输出，可重放 |
| ClickHouse 物化视图 | `*State`/`*Merge` 聚合正确 |
| 多租户实体设计 | Organization/Game/Environment/User/Role 字段齐备 |
| HMAC 签名规范 | `t=TIMESTAMP,s=hmac(body)` 符合 Stripe 规范 |
| 限流双维度 | apiKey + IP，可配置 RPM |

## 6. 下一步

详见 [02-design-issues.zh.md](./02-design-issues.zh.md) - 关键设计问题分级清单。
