# 维度数据同步设计

## 结论

Oddsmaker 不强制游戏方用某种接入方式。本文档定义四类同步方案 + 一个开箱即用的同步 Agent，覆盖游戏从 POC 到成熟期、从独立工作室到大厂的各种场景。

核心取舍：

- **维度数据**（物品定义、关卡配置、活动配置等）和**事实数据**（玩家消耗、行为事件）分离。
- 事实数据走 SDK → Gateway → ClickHouse 主表（详见资源事件设计文档）。
- 维度数据从游戏侧业务库同步到 ClickHouse 维度表（本文档）。
- 查询时**不回查游戏业务库**，所有 join 在 ClickHouse 内完成。

## 为什么需要同步而不是直连

| 方案 | 问题 |
|---|---|
| 分析查询实时回查游戏库 | 把分析负载打到业务库，影响线上；跨网络延迟；权限耦合 |
| 只让 SDK 上报维度 | 维度变更（物品改名、调稀有度）游戏方未必发事件；历史事实会"穿越"成新属性 |
| 统一同步到一个库 | OLTP 和 OLAP 职责不同，混库会同时毁掉两边性能 |

正确做法：**游戏业务库是维度的源头，ClickHouse 维度表是分析侧的副本，中间有一层同步链路**。

## 同步方案总览

| 方案 | 谁读源头 | 谁推 oddsmaker | 游戏方要做的事 | 延迟 |
|---|---|---|---|---|
| **Webhook Push** | 游戏方代码 | 游戏方调 API | 在物品变更时调 Gateway 上报 `item_define` 事件 | 实时 |
| **Sync Agent** | Agent（游戏方内网） | Agent 推 Gateway | 部署一个 binary + 填连接串 | 分钟级 |
| **HTTP Pull** | Oddsmaker | Oddsmaker 拉 | 暴露 `/items?since=...` 增量查询接口 | 分钟级 |
| **Debezium CDC** | Debezium 读 binlog | Debezium 写 Kafka | 部署 Kafka Connect + 开 binlog | 秒级 |

无论哪种方案，落到 ClickHouse 的都是统一的 `item_dim` 表，后续分析、风控、BI 的查询逻辑完全一致。

## 按游戏时期的推荐方案

不同阶段的游戏团队技术能力、接入意愿、合规约束都不同。按时期对号入座。

### 接入期 / POC

**目标**：最快验证 Oddsmaker 能不能用，不改造游戏代码。

**推荐**：**Webhook Push**（手动少量上报）或 **Sync Agent + CSV source**（策划表导出）。

- 物品数量少（几十到几百），人工或脚本一次性导出 CSV，丢给 Agent 推上去。
- 不开 binlog、不写 API、不部署 Kafka。
- POC 跑通后，再选长期方案。

### 独立游戏 / 早期

**特征**：小团队（1-5 人）、技术栈简单、不愿为接入改造太多、配置表可能是 Excel。

**推荐**：**Sync Agent**（MySQL/PostgreSQL source）或 **CSV/Excel source**。

- 部署 Agent，配本地库连接串或定期拖 CSV 到目录。
- Agent 是 Oddsmaker 提供的现成 binary，游戏方不用写同步代码。
- 分钟级延迟够用（独立游戏的物品变更频率低）。

### 成长期 / 中型游戏

**特征**：有后端开发能力、有 API 网关、开始关注实时性和数据质量。

**推荐**：**HTTP Pull**（游戏方做查询 API）或 **Webhook Push**（在物品变更代码里加一行调用）。

- 不依赖 Oddsmaker 部署 Agent 到游戏内网。
- 游戏方对 API 有完全控制权（鉴权、限流、字段裁剪）。
- 配合 Tracking Plan 做字段校验和白名单。

### 成熟期 / 大厂游戏

**特征**：完整技术栈、有 DBA 和数据团队、已经有 Kafka、要求秒级延迟、合规严格。

**推荐**：**Debezium CDC**。

- 复用游戏方已有的 Kafka 集群，不引入新组件到游戏内网。
- 秒级延迟支持实时风控（新物品大量产出、稀有度异常变动）。
- DBA 只需开 binlog + 给只读账号，不用做应用层改造。

### 存量 / 老游戏

**特征**：技术栈老旧（可能还在用 Excel/CSV 管理配置）、没有 API、数据库版本旧、没有 binlog。

**推荐**：**Sync Agent + 文件 source**（CSV/Excel/Parquet）。

- 策划定期导出物品表到共享目录或对象存储。
- Agent watch 目录变更，自动推送到 Gateway。
- 这类游戏通常物品变更不频繁，天级延迟可接受。

### 决策树

```
游戏方愿意写代码吗？
├─ 是 → 愿意开 binlog + 有 Kafka 吗？
│       ├─ 是 → Debezium CDC（成熟期）
│       └─ 否 → Webhook Push（成长期）
└─ 否 → 愿意暴露查询 API 吗？
        ├─ 是 → HTTP Pull（成长期）
        └─ 否 → 愿意部署 Agent 吗？
                ├─ 是 → Sync Agent（独立游戏/存量）
                │        └─ 数据源是 DB？→ MySQL/PG source
                │        └─ 数据源是文件？→ CSV/Excel source
                └─ 否 → Webhook Push 手动上报（仅 POC 可接受）
```

## 各方案详解

### Webhook Push

游戏方在物品变更时（新增、修改、下架），主动调 Oddsmaker Gateway 上报维度事件。

```
POST /v1/dimension
X-Api-Key: pk_game_xxx
Content-Type: application/x-ndjson

{"event_type":"dimension_define","dim_type":"item","resource_id":"sword_001","name":"铁剑","rarity":"common","op":"upsert","version_ts":1760000000000}
{"event_type":"dimension_define","dim_type":"item","resource_id":"sword_002","name":"钢剑","rarity":"rare","op":"upsert","version_ts":1760000000001}
```

- `op`：`upsert`（新增/更新）或 `delete`（下架）。
- `version_ts`：源头变更时间，用于 SCD2 和幂等。
- Gateway 复用现有鉴权、限流、PII 治理能力，无需新组件。
- 适合**愿意改造、变更频率低、要实时**的场景。

### Sync Agent（`oddsmaker-agent`）

Oddsmaker 提供的开源同步 Agent，游戏方在自己网络内部署。Agent 读本地源头，通过 HTTPS 推到 Gateway。

```
游戏方内网:
  oddsmaker-agent
    ├─ source: mysql | postgres | csv-file | excel-file | kafka
    ├─ transform: 按 mapping 配置转换字段
    ├─ checkpoint: 本地断点续传
    └─ sink: HTTPS POST /v1/dimension → Oddsmaker Gateway
```

配置示例（`agent.yaml`）：

```yaml
source:
  type: mysql
  dsn: "readonly:***@tcp(127.0.0.1:3306)/game?parseTime=true"
  query: "SELECT item_code AS resource_id, item_name AS name, category, rarity, updated_at FROM items WHERE updated_at > ? ORDER BY updated_at LIMIT 1000"
  schedule: "*/5 * * * *"

transform:
  mapping:
    resource_id: "${resource_id}"
    name: "${name}"
    type: "${category}"
    rarity: "${rarity}"
    version_ts: "${updated_at_unix_millis}"

sink:
  type: oddsmaker-gateway
  endpoint: https://ingest.oddsmaker.local
  api_key: pk_game_xxx
  batch_size: 100
```

关键约束：

- **只读账号**：Agent 持有的 DB 账号只授 SELECT 权限。
- **强制读从库**：配置项禁止指向主库，避免影响线上。
- **增量查询**：必须基于 `updated_at` 或自增 ID，禁止全表扫。
- **限频 + 批量**：默认 5 分钟一次、每批 1000 条，可按游戏配置。
- **断点续传**：checkpoint 存本地 SQLite，并定期上报到 Control Service 做容灾。
- **凭证不出游戏网络**：DB 凭证只在 Agent 本地，Oddsmaker 侧永远拿不到。

Agent 是一个独立的 `oddsmaker-agent` 项目，支持多种 source 适配器（DB / 文件 / Kafka）。这也是 **FileImport 方案的归宿**——文件导入本质是 Agent 的一个 source 类型，不用单独立项。

### HTTP Pull

Oddsmaker 主动拉取游戏方暴露的查询接口。

```
GET https://api.game-x.com/v1/items?updated_after=1760000000000&limit=1000
Authorization: Bearer ***
→ 200 OK
  {"items": [...], "next_cursor": "..."}
```

- 奇数 maker 侧跑一个 scheduler，按游戏配置定期拉取。
- 游戏方接口必须支持 `updated_after` 增量参数，否则每次全量拉太重。
- 适合**游戏方有 API 能力、不愿部署 Agent、不愿开 binlog**的场景。
- Oddsmaker 侧需要维护游戏方的 API 凭证（在 Control Service 加密存储）。

### Debezium CDC

游戏方部署 Kafka Connect + Debezium Source Connector，把 `items` 表的 binlog 变更写到 Kafka topic，Oddsmaker 订阅这个 topic 做转换。

```
游戏 MySQL → Debezium → Kafka topic: game_x.db.items
                                    ↓
                  Oddsmaker dimension-consumer (Flink job 或独立服务)
                                    ↓
                           ClickHouse item_dim
```

- 秒级延迟，适合实时风控。
- Oddsmaker 侧只订阅 topic，不接触游戏数据库。
- 需要游戏方：开 binlog、给只读账号、部署 Kafka Connect（或复用已有 Kafka）。
- 配 Debezium 心跳表保活，避免低频更新导致延迟堆积。
- 注意 DDL 变更：游戏方给 `items` 表加列时，Debezium 的 schema history 会感知，转换层要能处理新字段。

## 统一抽象

无论哪种方案，落地的中间格式和输出 schema 完全一致。

### 中间格式：`RawDimensionChange`

```json
{
  "source_type": "webhook | agent | pull | debezium",
  "dim_type": "item",
  "op": "upsert | delete",
  "resource_id": "sword_001",
  "attributes": { "name": "铁剑", "rarity": "common", "type": "weapon" },
  "version_ts": 1760000000000,
  "game_id": "game_x",
  "environment": "prod"
}
```

各 Provider 的职责是把源头数据翻译成这个格式，**后续逻辑只写一份**。

### 输出：`item_dim`（ClickHouse）

```sql
CREATE TABLE item_dim (
  resource_id String,
  name String,
  type String,
  rarity String,
  valid_from DateTime,
  valid_to DateTime,           -- SCD2，null 表示当前版本
  is_current UInt8,
  game_id String,              -- 多游戏共享维度表时用
  environment String
) ENGINE = ReplacingMergeTree(is_current)
ORDER BY (game_id, environment, resource_id, valid_from);
```

### SCD2 处理（公共逻辑）

物品属性变更（如改名、调稀有度）时：

- 旧版本的 `valid_to` 设为变更时间，`is_current = 0`。
- 新版本作为新行写入，`valid_from = 变更时间`，`valid_to = null`，`is_current = 1`。
- 历史事实记录查询时按事件时间 join 对应版本的维度，避免"穿越"。

SCD2 逻辑放在转换层，**所有 Provider 共享**，不要散落到各 Provider 实现。

## 配置驱动

每个游戏的维度同步在 Control Service 配置，接入新游戏只填表，不改代码。

```yaml
# 游戏 A：大厂、走 CDC
dimensions:
  item_dim:
    provider: debezium
    topic: game_a.db.items
    consumer_group: oddsmaker-dim-sync
    mapping:
      resource_id: "${after.item_code}"
      name: "${after.item_name}"
      rarity: "${after.rarity}"

# 游戏 B：独立工作室、走 Agent
dimensions:
  item_dim:
    provider: agent
    # Agent 在游戏方部署，Oddsmaker 只收事件
    auth_token_ref: game_b_dim_token

# 游戏 C：成长期、走 Pull
dimensions:
  item_dim:
    provider: pull
    url: https://api.game-c.com/v1/items
    schedule: "*/10 * * * *"
    auth:
      type: bearer
      token_ref: game_c_api_token
    mapping:
      resource_id: "${code}"
      name: "${display_name}"
```

## 监控与运维

Control Service 暴露每个游戏的维度同步状态：

- 最后成功同步时间
- 同步延迟（源头变更 → 落 ClickHouse 的端到端时间）
- 错误计数和最近错误信息
- Checkpoint 位点（Agent 模式）
- 维度记录总数变化（异常波动告警，可能是源头出问题）

接入出问题时，游戏方和 Oddsmaker 运营都能快速定位是哪个环节。

## 落地优先级

不需要一上来全做。建议按价值/成本排序：

1. **Webhook Push**：成本最低（复用 Gateway，加一个事件类型），覆盖愿意改造的成长期客户。
2. **Sync Agent（MySQL/PostgreSQL source）**：覆盖不愿写代码的独立游戏和存量游戏，最大客户群。
3. **HTTP Pull**：覆盖不愿部署 Agent 但有 API 能力的客户。
4. **Sync Agent（CSV/Excel source）**：覆盖传统游戏公司，source 适配器加一个就能用。
5. **Debezium CDC**：大厂场景，价值高但接入复杂，放最后。

## 与资源事件设计的关系

本文档是资源事件设计（事实数据）的补充：

| | 资源事件（事实） | 维度同步（维度） |
|---|---|---|
| 数据来源 | SDK 玩家行为上报 | 游戏业务库 / 配置 |
| 写入表 | `events` / `resource_changes` | `item_dim` / `level_dim` / ... |
| 频率 | 高频（每玩家每秒多条） | 低频（每天少量变更） |
| 查询角色 | 被聚合的事实 | 被 join 的维度 |
| 同步方式 | SDK → Gateway → Kafka → ClickHouse | 本文四种方案之一 |

两边在 ClickHouse 层汇合，分析查询通过 `resource_id` 等键 join。
