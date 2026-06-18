# 04 - 重设计方案

## 1. 设计原则

1. **游戏垂直化**：事件 Taxonomy 对齐 GameAnalytics 标准（7 类 + Ad）
2. **存储分层化**：Postgres（元数据）+ ClickHouse（事件，热）+ S3（归档，冷）
3. **租户三段式**：`Organization → Game → Environment`，事件 schema 用 `tenant_id + app_id`
4. **Schema 治理化**：Tracking Plan + CI 校验 + Schema Registry
5. **能力插件化**：核心 Analysis + 可选 Crash/A-B/Remote Config/Heatmap
6. **客户端安全**：客户端 SDK 仅 `api_key`；HMAC 仅用于 Server SDK

## 2. 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│  SDK 层（Web/Mobile/Unity/Unreal/Server）                    │
│  - 7 类标准事件 + 自定义                                      │
│  - 客户端只持 api_key（公开）；Server SDK 才用 HMAC            │
│  - 内置 session 自动管理、offline 队列、batching              │
└─────────────────────────────────────────────────────────────┘
                            ↓ HTTPS /v1/batch (NDJSON+gzip)
┌─────────────────────────────────────────────────────────────┐
│  采集层 Gateway Service (Spring WebFlux)                     │
│  - 双层鉴权：api_key + IP 白名单 + (Server SDK) HMAC          │
│  - Schema 校验（JSON Schema, Tracking Plan）                 │
│  - PII 治理：deny/mask/coarse，配置从 Control Service 拉取    │
│  - 多维度限流（org + game + env + key + IP）                 │
│  - TraceId/RequestID 透传                                    │
└─────────────────────────────────────────────────────────────┘
                            ↓ Kafka (pit.events_raw, Avro)
┌─────────────────────────────────────────────────────────────┐
│  实时计算层 Flink 1.19                                       │
│  - enrich:        去重 + GeoIP + UA + Identity Merge         │
│  - sessions:      智能切分 + 游戏内行为感知                  │
│  - retention:     N-Day + Rolling + 分层留存                  │
│  - funnels:       N 步可配置漏斗 + 路径分析                  │
│  - revenue:       IAP/Ad 收入聚合 + LTV 累积                 │
│  - realtime:      5min 滚动大屏表                            │
│  - progression:   关卡难度/通过率/失败分布                    │
│  - anomaly:       CEP 异常检测（作弊、刷榜、异常付费）        │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  存储层                                                       │
│  PostgreSQL  ← 元数据：Org/Game/Env/ApiKey/User/Role/Schema │
│  ClickHouse  ← 事件 + 聚合（按 tenant_id + toYYYYMM 分区）   │
│  Redis       ← 实时计数器 / 会话缓存 / 配额扣减               │
│  S3          ← 原始事件归档 / Session Replay                 │
│  Kafka       ← DLQ + 实时数据流出口                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  应用层                                                       │
│  - Control Service：多租户/权限/审计/计费/配额                │
│  - Query Service：SQL 查询网关（带租户行级策略）              │
│  - Dashboard Service：React + ECharts 大屏与看板            │
│  - A/B Service：实验配置、显著性检验、SRM 检查               │
│  - BI Connectors：Superset / Metabase / Tableau              │
│  - Export Service：S3/数仓导出（CDC/Reverse ETL）            │
└─────────────────────────────────────────────────────────────┘
```

## 3. 统一事件 Schema（Avro + JSON Schema 双写）

```json
{
  "type": "record",
  "name": "GameEvent",
  "namespace": "io.pit.v2",
  "fields": [
    {"name":"event_id","type":"string"},
    {"name":"tenant_id","type":"string"},
    {"name":"app_id","type":"string"},
    {"name":"event_type","type":{"type":"enum","name":"EventType","symbols":
       ["session","user","business","resource","progression","design","error","ad","experiment"]}},
    {"name":"event_name","type":"string"},

    {"name":"user_id","type":["null","string"]},
    {"name":"device_id","type":"string"},
    {"name":"session_id","type":["null","string"]},
    {"name":"ts_client","type":"long","logicalType":"timestamp-millis"},
    {"name":"ts_server","type":["null","long"],"logicalType":"timestamp-millis"},

    {"name":"platform","type":["null","string"]},
    {"name":"app_version","type":["null","string"]},
    {"name":"sdk_version","type":["null","string"]},
    {"name":"country","type":["null","string"]},
    {"name":"os_name","type":["null","string"]},
    {"name":"os_version","type":["null","string"]},
    {"name":"device_model","type":["null","string"]},
    {"name":"network_type","type":["null","string"]},

    {"name":"player_id","type":["null","string"]},
    {"name":"server_id","type":["null","string"]},
    {"name":"character_id","type":["null","string"]},
    {"name":"guild_id","type":["null","string"]},
    {"name":"match_id","type":["null","string"]},
    {"name":"level_id","type":["null","string"]},
    {"name":"progression","type":{"type":"array","items":"string"}},
    {"name":"difficulty","type":["null","string"]},
    {"name":"game_mode","type":["null","string"]},
    {"name":"item_id","type":["null","string"]},
    {"name":"item_quantity","type":["null","long"]},

    {"name":"revenue_amount","type":["null",{"type":"bytes","logicalType":"decimal","precision":18,"scale":4}]},
    {"name":"revenue_currency","type":["null","string"]},
    {"name":"iap_receipt","type":["null","string"]},
    {"name":"virtual_currency","type":["null","string"]},
    {"name":"virtual_amount","type":["null","long"]},
    {"name":"flow_type","type":{"type":"enum","name":"FlowType","symbols":["source","sink"]}},

    {"name":"ad_placement","type":["null","string"]},
    {"name":"ad_network","type":["null","string"]},
    {"name":"ad_format","type":["null","string"]},

    {"name":"experiments","type":{"type":"map","values":"string"}},
    {"name":"attribution","type":{"type":"map","values":"string"}},

    {"name":"props","type":{"type":"map","values":"string"}},

    {"name":"error_severity","type":{"type":"enum","name":"Severity","symbols":["debug","info","warning","error","fatal"]}},
    {"name":"error_stack","type":["null","string"]}
  ]
}
```

## 4. ClickHouse 重新设计（多租户 + 游戏字段）

```sql
CREATE TABLE events
(
    tenant_id       LowCardinality(String),
    app_id          LowCardinality(String),
    event_id        String,
    event_type      LowCardinality(String),
    event_name      LowCardinality(String),

    user_id         String DEFAULT '',
    device_id       String,
    session_id      String DEFAULT '',
    ts_server       DateTime64(3) DEFAULT now64(3),
    ts_client       DateTime64(3),
    event_date      Date DEFAULT toDate(ts_server),

    platform        LowCardinality(String),
    app_version     LowCardinality(String),
    sdk_version     LowCardinality(String),
    country         FixedString(2) DEFAULT '',
    os_name         LowCardinality(String),
    os_version      LowCardinality(String),
    device_model    LowCardinality(String),
    network_type    LowCardinality(String),

    player_id       String DEFAULT '',
    server_id       String DEFAULT '',
    guild_id        String DEFAULT '',
    match_id        String DEFAULT '',
    level_id        String DEFAULT '',
    game_mode       LowCardinality(String) DEFAULT '',
    difficulty      LowCardinality(String) DEFAULT '',
    item_id         String DEFAULT '',
    item_quantity   Int64 DEFAULT 0,

    revenue_amount  Decimal(18,4) DEFAULT 0,
    revenue_currency FixedString(3) DEFAULT 'USD',
    virtual_currency LowCardinality(String) DEFAULT '',
    virtual_amount  Int64 DEFAULT 0,
    flow_type       LowCardinality(String) DEFAULT '',

    ad_placement    String DEFAULT '',
    ad_network      LowCardinality(String) DEFAULT '',
    ad_format       LowCardinality(String) DEFAULT '',

    experiments     Map(String, String),
    attribution     Map(String, String),
    props           Map(String, String),

    error_severity  LowCardinality(String) DEFAULT '',
    error_stack     String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_type, event_date, user_id, device_id, ts_server, event_id)
TTL event_date + INTERVAL 90 DAY TO VOLUME 'default',
    event_date + INTERVAL 365 DAY TO DISK 'cold';

CREATE ROW POLICY tenant_isolation ON events
FOR SELECT USING (tenant_id = currentRowPolicyTenant());
```

## 5. 关键聚合表（新增）

```sql
-- 实时大屏（5 分钟滚动，靠 Redis 加速）
CREATE TABLE realtime_5min (
    tenant_id LowCardinality(String),
    app_id    LowCardinality(String),
    ts        DateTime,
    dau       UInt64,
    sessions  UInt64,
    events    UInt64,
    revenue   Decimal(18,4)
) ENGINE = SummingMergeTree
PARTITION BY (tenant_id, toYYYYMMDD(ts))
ORDER BY (tenant_id, app_id, ts);

-- 留存（N-Day + Rolling 双口径）
CREATE TABLE retention (
    tenant_id      LowCardinality(String),
    app_id         LowCardinality(String),
    cohort_date    Date,
    cohort_size    UInt64,
    d_offset       UInt16,
    retention_type LowCardinality(String), -- nday|rolling|first_event|paid|level|channel
    users          UInt64
) ENGINE = SummingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(cohort_date))
ORDER BY (tenant_id, app_id, cohort_date, d_offset, retention_type);

-- 用户身份合并表
CREATE TABLE identities (
    tenant_id        LowCardinality(String),
    app_id           LowCardinality(String),
    user_id          String,
    device_ids       Array(String),
    first_seen       DateTime,
    last_seen        DateTime,
    ltv              Decimal(18,4),
    last_level       String,
    last_login       DateTime,
    churn_risk_score Float32
) ENGINE = ReplacingMergeTree(last_seen)
PARTITION BY (tenant_id, app_id, toYYYYMM(last_seen))
ORDER BY (tenant_id, app_id, user_id);

-- 关卡尝试
CREATE TABLE progression_attempts (
    tenant_id    LowCardinality(String),
    app_id       LowCardinality(String),
    user_id      String,
    level_id     String,
    result       Enum8('start'=1,'complete'=2,'fail'=3,'restart'=4),
    attempt_num  UInt32,
    score        Int64,
    duration_ms  UInt32,
    ts           DateTime
) ENGINE = MergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(ts))
ORDER BY (tenant_id, app_id, level_id, user_id, ts);

-- 虚拟经济净流
CREATE TABLE resource_flows (
    tenant_id   LowCardinality(String),
    app_id      LowCardinality(String),
    event_date  Date,
    currency    LowCardinality(String),
    flow_type   LowCardinality(String),
    item_type   LowCardinality(String),
    amount      Int64
) ENGINE = SummingMergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(event_date))
ORDER BY (tenant_id, app_id, event_date, currency, flow_type, item_type);

-- 漏斗（任意 N 步，配置驱动）
CREATE TABLE funnels (
    tenant_id        LowCardinality(String),
    app_id           LowCardinality(String),
    funnel_id        String,
    funnel_version   UInt32,
    ts               DateTime,
    user_id          String,
    step_completed   UInt16,
    completed_all    Bool
) ENGINE = MergeTree
PARTITION BY (tenant_id, app_id, toYYYYMM(ts))
ORDER BY (tenant_id, app_id, funnel_id, user_id, ts);
```

## 6. 模块演进对照表

| 模块 | 当前状态 | 重设计后 |
|---|---|---|
| **采集网关** | HMAC + 限流 + RequestId | + PII 治理落地 + Schema 校验 + Tracking Plan + 多维度配额 |
| **事件富化** | 去重 + GeoIP + UA | + Identity Merge + Attribution 解析 + 实验分组绑定 |
| **会话切分** | 30 min gap 固定 | + 游戏行为感知（战斗结束触发）+ 跨设备会话归并 |
| **留存** | 仅 0/1/7/30 固定 | N-Day（任意 N）+ Rolling + 分层（付费/等级/渠道） |
| **漏斗** | 2 步 | N 步 + 有序/无序 + 时间窗 + 路径分析 |
| **LTV** | ❌ | Cohort LTV + pLTV（留存+付费曲线回归预测） |
| **关卡分析** | ❌ | 难度系数、首次通过率、失败次数分布 |
| **虚拟经济** | ❌ | source/sink 净流、通胀监控、玩家分布 |
| **广告分析** | ❌ | impression/click/reward/eCPM/ARPU(Ad) |
| **A/B 实验** | 仅客户端曝光 | 配置化 + CUPED + 显著性 + SRM 检查 |
| **Crash 报告** | ❌ | 采集 + 符号化 + 聚合 + 趋势 |
| **大屏** | 仅 Superset | ClickHouse 物化视图 + Redis 计数 + 自研大屏 |
| **多租户** | 概念混乱 | Org → Game → Env → App 三级，行级策略 + 配额 |
| **Identity** | 平行字段 | identities 表 + device→user 合并 |

## 7. SDK 重设计要点

| 修复点 | 现状 | 重设计 |
|---|---|---|
| **Android HMAC** | 把 secret 嵌入客户端（漏洞） | 移除客户端 HMAC，仅保留 `api_key`；HMAC 仅 Server SDK 使用 |
| **会话** | 30 min 固定 | 可配置，支持"游戏内行为感知"（如暂停/恢复、关卡切换） |
| **API Key 类型** | 单字段 | 区分 `public_key`（客户端，仅可写）+ `secret_key`（服务端，全权限） |
| **7 类事件 API** | 通用 `track()` | 提供 `pit.session()/business()/resource()/progression()/design()/error()/ad()` 类型化方法 |
| **离线队列** | localStorage / SharedPreferences | 增加 IndexedDB（Web 大容量）/ Room（Android）/ CoreData（iOS） |

## 8. 治理与合规

### 8.1 Tracking Plan
- YAML 定义事件命名规范、字段词典、Cardinality 上限
- CI 校验 SDK 代码与文档一致
- 强制 `category:sub_category:outcome` 命名

### 8.2 Schema Registry
- 保留 Apicurio
- 新增 JSON Schema 版本兼容性检查（backward/forward）

### 8.3 PII 治理（落地到 Gateway 代码）
- `denyKeys` → 直接拒绝事件入 DLQ
- `maskKeys` → 邮箱/电话 MD5 + 截断
- `coarse` → IP 转 /24（v4）或 /48（v6）

### 8.4 审计
- 所有 Control Service 操作写入 `audit_logs` 表
- 字段：actor / action / target / before / after / ts / ip

### 8.5 GDPR/CCPA/COPPA
- 用户删除请求（`/v1/users/{id}/forget`）级联清理 events/sessions/identities
- COPPA：游戏字段标记未成年，特殊处理

详细实施路线见 [05-roadmap.zh.md](./05-roadmap.zh.md)。
