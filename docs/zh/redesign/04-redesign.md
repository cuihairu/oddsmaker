# 04 - 重设计方案

## 1. 设计原则

1. **单公司部署**：公司是部署边界，不是业务租户；不再建多租户模型。
2. **多游戏管理**：`Game` 是核心业务对象，一个平台管理多个游戏。
3. **环境显式隔离**：所有事件、配置、API Key、风控策略都绑定 `game_id + environment`。
4. **游戏垂直化**：事件体系对齐游戏行业，覆盖会话、付费、资源、关卡、广告、错误和实验。
5. **风控内建**：风控策略、实时检测、告警、处置、审计是主链路能力。
6. **Schema 治理**：Tracking Plan + JSON Schema + Avro + Registry，防止事件名和属性失控。
7. **客户端安全**：客户端 SDK 只持 public `api_key`；HMAC 只用于 Server SDK。

## 2. 目标架构

```text
SDK 层
  Web / Android / iOS / Unity / Unreal / Server
  - public api_key
  - typed game events
  - offline queue + batch + gzip
  - device/session management
  - client integrity signals
        |
        v
Gateway Service
  - API Key 校验：key -> game_id + environment
  - Server SDK HMAC 校验
  - JSON Schema / Tracking Plan 校验
  - PII deny/mask/coarse
  - 限流：key + game + env + IP + device
  - 风控前置：黑名单、重放、时间窗、体积、字段异常
        |
        v
Kafka
  - oddsmaker.events_raw
  - oddsmaker.events_validated
  - oddsmaker.risk_events
  - oddsmaker.deadletter
        |
        v
Flink Jobs
  - enrich：去重、GeoIP、UA、归因、Identity Merge
  - sessions：会话切分
  - retention：N-Day + Rolling
  - funnels：N 步漏斗
  - revenue：IAP/Ad/LTV 聚合
  - progression：关卡和任务分析
  - risk：作弊、支付、账号、广告、经济风控
  - realtime：5 分钟运营大屏
        |
        v
Storage
  PostgreSQL
    Game / Environment / ApiKey / User / Role / TrackingPlan / RiskRule / AuditLog
  ClickHouse
    events / identities / sessions / retention / funnels / revenue / risk_events / risk_scores
  Redis
    rate limit / realtime counters / risk short-window state / blacklists
  S3
    raw archive / replay / crash symbols / export
        |
        v
Applications
  Control Service
    游戏、环境、密钥、策略、风控、权限、审计
  Query Service
    固化指标 API、自定义 SQL 安全网关
  Dashboard
    运营实时大屏、风控告警、核心指标
  BI
    Superset / Metabase
```

## 3. 控制面模型

```text
CompanyConfig
  - company_name
  - deployment_id
  - default_timezone
  - data_region

Game
  - id
  - name
  - genre
  - platforms
  - status

GameEnvironment
  - game_id
  - environment: dev | staging | prod
  - endpoint_policy
  - data_retention_days
  - sampling_policy

ApiKey
  - key_id
  - public_key
  - secret_key_hash only for Server SDK
  - game_id
  - environment
  - key_type: client | server | admin
  - enabled
  - rate_limits
  - pii_policy_id
  - risk_policy_id

User
  - id
  - email
  - display_name
  - global_role: admin | user

RoleBinding
  - user_id
  - scope: global | game | environment
  - game_id nullable
  - environment nullable
  - role: owner | operator | analyst | developer | risk_admin | viewer

TrackingPlan
  - game_id
  - environment
  - version
  - event definitions
  - property dictionary
  - cardinality limits

RiskRule
  - game_id
  - environment
  - rule_id
  - rule_type: threshold | sequence | velocity | blacklist | model
  - severity
  - action: mark | alert | block | review | webhook
```

## 4. 统一事件 Schema

目标字段使用 `game_id + environment`，不再使用 `tenant_id`。

```json
{
  "type": "record",
  "name": "GameEvent",
  "namespace": "io.oddsmaker.v1",
  "fields": [
    {"name": "event_id", "type": "string"},
    {"name": "game_id", "type": "string"},
    {"name": "environment", "type": "string"},
    {"name": "event_type", "type": "string"},
    {"name": "event_name", "type": "string"},

    {"name": "user_id", "type": ["null", "string"], "default": null},
    {"name": "device_id", "type": "string"},
    {"name": "player_id", "type": ["null", "string"], "default": null},
    {"name": "character_id", "type": ["null", "string"], "default": null},
    {"name": "session_id", "type": ["null", "string"], "default": null},

    {"name": "ts_client", "type": "long"},
    {"name": "ts_server", "type": ["null", "long"], "default": null},

    {"name": "platform", "type": ["null", "string"], "default": null},
    {"name": "app_version", "type": ["null", "string"], "default": null},
    {"name": "sdk_version", "type": ["null", "string"], "default": null},
    {"name": "country", "type": ["null", "string"], "default": null},
    {"name": "client_ip", "type": ["null", "string"], "default": null},
    {"name": "user_agent", "type": ["null", "string"], "default": null},

    {"name": "server_id", "type": ["null", "string"], "default": null},
    {"name": "guild_id", "type": ["null", "string"], "default": null},
    {"name": "match_id", "type": ["null", "string"], "default": null},
    {"name": "level_id", "type": ["null", "string"], "default": null},
    {"name": "game_mode", "type": ["null", "string"], "default": null},
    {"name": "difficulty", "type": ["null", "string"], "default": null},
    {"name": "progression_path", "type": {"type": "array", "items": "string"}, "default": []},

    {"name": "order_id", "type": ["null", "string"], "default": null},
    {"name": "product_id", "type": ["null", "string"], "default": null},
    {"name": "revenue_amount", "type": ["null", "double"], "default": null},
    {"name": "revenue_currency", "type": ["null", "string"], "default": null},
    {"name": "receipt_hash", "type": ["null", "string"], "default": null},

    {"name": "virtual_currency", "type": ["null", "string"], "default": null},
    {"name": "virtual_amount", "type": ["null", "long"], "default": null},
    {"name": "flow_type", "type": ["null", "string"], "default": null},
    {"name": "item_id", "type": ["null", "string"], "default": null},

    {"name": "ad_network", "type": ["null", "string"], "default": null},
    {"name": "ad_placement", "type": ["null", "string"], "default": null},
    {"name": "ad_format", "type": ["null", "string"], "default": null},
    {"name": "ad_impression_id", "type": ["null", "string"], "default": null},

    {"name": "experiments", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "attribution", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "risk_context", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "props", "type": {"type": "map", "values": "string"}, "default": {}}
  ]
}
```

## 5. ClickHouse 核心表

**数据库架构**：每个游戏独立数据库

```sql
-- game_demo_prod.events
CREATE TABLE events
(
    event_date Date DEFAULT toDate(ts_server),
    ts_server DateTime64(3) DEFAULT now64(3),
    ts_client DateTime64(3),

    event_id String,
    event_type LowCardinality(String),
    event_name LowCardinality(String),

    user_id String DEFAULT '',
    device_id String,
    player_id String DEFAULT '',
    character_id String DEFAULT '',
    session_id String DEFAULT '',

    platform LowCardinality(String) DEFAULT '',
    app_version LowCardinality(String) DEFAULT '',
    sdk_version LowCardinality(String) DEFAULT '',
    country FixedString(2) DEFAULT '',
    client_ip_hash String DEFAULT '',
    user_agent String DEFAULT '',

    server_id String DEFAULT '',
    guild_id String DEFAULT '',
    match_id String DEFAULT '',
    level_id String DEFAULT '',
    game_mode LowCardinality(String) DEFAULT '',
    difficulty LowCardinality(String) DEFAULT '',

    order_id String DEFAULT '',
    product_id String DEFAULT '',
    revenue_amount Decimal(18,4) DEFAULT 0,
    revenue_currency FixedString(3) DEFAULT '',
    receipt_hash String DEFAULT '',

    resource_id LowCardinality(String) DEFAULT '',
    resource_amount Int64 DEFAULT 0,
    flow_type LowCardinality(String) DEFAULT '',
    item_id String DEFAULT '',

    ad_network LowCardinality(String) DEFAULT '',
    ad_placement String DEFAULT '',
    ad_format LowCardinality(String) DEFAULT '',
    ad_impression_id String DEFAULT '',

    experiments Map(String, String),
    attribution Map(String, String),
    risk_context Map(String, String),
    props Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (toYYYYMM(event_date))
ORDER BY (event_type, event_date, server_id, player_id, user_id, device_id, ts_server, event_id)
TTL event_date + INTERVAL 365 DAY;

-- game_demo_prod.risk_events
CREATE TABLE risk_events
(
    ts DateTime64(3),
    risk_event_id String,
    source_event_id String DEFAULT '',
    rule_id String,
    risk_type LowCardinality(String),
    severity LowCardinality(String),
    subject_type LowCardinality(String),
    subject_id String,
    score Float32,
    action LowCardinality(String),
    reason String,
    evidence Map(String, String)
)
ENGINE = MergeTree
PARTITION BY (toYYYYMM(ts))
ORDER BY (risk_type, severity, ts, subject_id);

-- game_demo_prod.identities
CREATE TABLE identities
(
    identity_id String,
    user_id String DEFAULT '',
    player_id String DEFAULT '',
    character_ids Array(String),
    device_ids Array(String),
    first_seen DateTime64(3),
    last_seen DateTime64(3),
    risk_score Float32 DEFAULT 0
)
ENGINE = ReplacingMergeTree(last_seen)
PARTITION BY (toYYYYMM(last_seen))
ORDER BY (identity_id);
```

## 6. 风控模块

### 6.1 风控输入

- 请求元信息：IP、UA、API Key、签名、时间窗、body size。
- 设备信号：device_id、fingerprint、root/jailbreak、emulator、debugger。
- 账号信号：user_id、player_id、登录方式、多设备关系。
- 行为信号：事件频率、关卡耗时、资源流、对局结果、会话节奏。
- 支付信号：order_id、receipt_hash、商品、金额、退款、沙盒环境。
- 广告信号：impression_id、placement、network、reward 领取频率。

### 6.2 风控检测层

| 层 | 位置 | 作用 |
|---|---|---|
| 硬拦截 | Gateway | 黑名单、签名错误、重放、超限、非法环境 |
| 短窗规则 | Redis + Gateway/Flink | 每分钟事件暴增、IP/设备聚集、频率异常 |
| 状态规则 | Flink | 多步行为序列、资源异常、付费异常、广告异常 |
| 离线回溯 | ClickHouse | 批量刷号、长期经济异常、渠道质量分析 |
| 模型评分 | Risk Service | 流失、作弊、付费欺诈、账号风险 |

### 6.3 风控动作

- `mark`：只打标，不影响链路。
- `alert`：告警到 Dashboard/Webhook。
- `block`：Gateway 或业务服拒绝。
- `review`：进入人工审核队列。
- `throttle`：降低采样或限速。
- `shadow_ban`：输出给游戏服执行影子封禁。

### 6.4 风控策略示例

**注意**：`game_id` 和 `environment` 已在数据库/表层级体现，风控策略配置时不需要指定。

```yaml
rule_id: payment_receipt_reuse
# game_id 和 environment 在数据库层级：game_demo_prod
risk_type: payment
severity: high
window: 24h
condition:
  receipt_hash_distinct_users_gt: 1
action: block
```

```yaml
rule_id: resource_inflation_spike
# game_id 和 environment 在数据库层级：game_demo_prod
risk_type: economy
severity: medium
window: 10m
condition:
  resource_id: gold
  source_amount_gt_p99_multiplier: 5
action: alert
```

## 7. 模块演进对照

| 模块 | 当前 | 重设计后 |
|---|---|---|
| 控制面 | Project 与 Organization 并存 | Game + Environment + ApiKey + Policy |
| 事件字段 | project_id / tenant_id / app_id 混杂 | game_id + environment |
| 网关 | HMAC、限流、PII 部分能力 | API Key 绑定游戏环境 + 风控前置 |
| Flink | 富化、会话、留存、两步漏斗 | 增加 N 步漏斗、LTV、关卡、风控 |
| ClickHouse | tenant_id/app_id 分区 | game_id/environment 分区 |
| SDK | 参数不统一 | typed game events + public api_key |
| 权限 | 多租户 RBAC 过重 | 公司内按游戏/环境授权 |
| 风控 | 概念化 | risk_events + risk_rules + actions 闭环 |

## 8. 迁移策略

1. 新增 v1 schema，字段为 `game_id + environment`。
2. Gateway 支持兼容期映射：旧 `project_id` 或 `tenant_id/app_id` 映射到新字段。
3. ClickHouse 新建规范化事件表，双写一段时间。
4. Flink 作业按新 key 重写并灰度。
5. SDK 发布新 major 版本，旧参数标记 deprecated。
6. 删除 `Organization` 控制面 API 和迁移脚本，保留历史备份。
