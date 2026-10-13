# 维度数据同步设计

> **实现状态（2026-10）**：已落地的是 Sync Agent（`agents/dimension-sync-agent/`，mysql/postgres/csv/excel/kafka 五类 source）、**HTTP Pull 链路**（Control 侧 scheduler + Bearer 凭证 AES-GCM 加密托管 + `next_cursor` 断点续传，配置见 `dimension-pulls` API）、**Pull 配置 YAML 面**（`dimension-pulls/export` + `/import`，凭证永不落盘）、**Agent 属性面 Control 托管**（`agent-configs` API + 控制台「Agent 托管配置」面 + `/api/dimensions/agent-configs/resolved` 下发口，密钥 AES-GCM 密文托管；Agent 端 `control.config-url` 开关启动拉取 + 本地文件兜底，`control.config-refresh-seconds` 轮询热更新，优先级 本地 < Control < System property）、`/v1/batch` 维度事件通道和 `dimension-sync-job`（消费 `oddsmaker.events_raw` 写 `item_dim`/`level_dim`）。仅剩 Debezium CDC（外部依赖最重，待游戏方 Kafka 基建）一条未落地——本文对未落地部分逐处标注。

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
| **Webhook Push** | 游戏方代码 | 游戏方调 API | 在物品变更时调 Gateway 上报 `dimension_define` 事件 | 实时 |
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
POST /v1/batch
X-Api-Key: pk_game_xxx
Content-Type: application/x-ndjson

{"event_id":"01JE...","event_type":"dimension","event_name":"dimension_define","game_id":"game_x","environment":"prod","dim_type":"item","resource_id":"sword_001","name":"铁剑","rarity":"common","op":"upsert","version_ts":1760000000000,"ts_client":1760000000000}
{"event_id":"01JE...","event_type":"dimension","event_name":"dimension_define","game_id":"game_x","environment":"prod","dim_type":"item","resource_id":"sword_002","name":"钢剑","rarity":"rare","op":"upsert","version_ts":1760000000001,"ts_client":1760000000001}
```

没有独立的 `/v1/dimension` 端点——维度事件与普通事件同走 `/v1/batch`，用 `event_type=dimension` 区分，复用现有鉴权、限流、PII 治理。

- `op`：`upsert`（新增/更新）或 `delete`（下架；当前下游忽略该语义，仍写入新行，见下文「版本处理」）。
- `version_ts`：源头变更时间，用于 SCD2 和幂等。
- Gateway 复用现有鉴权、限流、PII 治理能力，无需新组件。
- 适合**愿意改造、变更频率低、要实时**的场景。

### Sync Agent（`oddsmaker-agent`）

Oddsmaker 提供的开源同步 Agent，游戏方在自己网络内部署。Agent 读本地源头，通过 HTTPS 推到 Gateway。**已实现**：仓库 `agents/dimension-sync-agent/`——零仓库内依赖的独立 Gradle 模块（仅 Jackson + kafka-clients + JDK HttpClient + runtimeOnly JDBC 驱动），支持 `mysql` / `postgres` / `csv` / `excel` / `kafka` 五类 source（excel 为标准库 zip+xml 最小 xlsx 解析，无 POI；kafka 位点 checkpoint 自管、真实 broker 端到端已实测），可整体目录 git subtree 拆出为独立仓库。

```
游戏方内网:
  oddsmaker-agent
    ├─ source: mysql | postgres | csv | excel | kafka
    ├─ transform: 控制列映射（dim_type/resource_id/op/version_ts，其余列进 attributes）
    ├─ checkpoint: 本地 checkpoint.json（原子落盘；kafka 位点同为 checkpoint 自管）
    └─ sink: HTTPS POST /v1/batch（NDJSON，event_name=dimension_define）
```

配置示例（`agent.properties`；同名 System property 可逐键覆盖文件值，便于密钥不落盘 `-Dgateway.api-key=...`）。配 `control.config-url` 即启用 Control 托管（见下方「Agent 属性面托管」），下发键逐键覆盖本地值，配 `control.config-refresh-seconds` 再启轮询热更新：

```properties
# 推送目标（Gateway /v1/batch）
gateway.endpoint=https://ingest.oddsmaker.local
gateway.api-key=pk_game_xxx

# 维度归属
game.id=game_demo
game.environment=prod
dimension.type=item

# 数据源之一：mysql / postgres（增量查询——单 ? 占位，必须以 ORDER BY <水位列> 结尾）
source.type=mysql
source.jdbc.url=jdbc:mysql://replica.internal:3306/game?useSSL=true
source.jdbc.user=oddsmaker_ro
source.jdbc.password=***
source.jdbc.query=SELECT item_code AS resource_id, item_name AS name, category, rarity, updated_at, UNIX_TIMESTAMP(updated_at)*1000 AS version_ts FROM items WHERE updated_at > ? ORDER BY updated_at LIMIT 1000
source.jdbc.cursor-column=updated_at

# 数据源之二：csv 目录（按文件名序扫 *.csv，处理完整文件才记断点；增量 = 投递新文件）
# source.type=csv
# source.csv.dir=/data/dimension-exports

# 数据源之三：excel 目录（扫 *.xlsx 取首个工作表，首行为表头；断点语义同 CSV）
# source.type=excel
# source.excel.dir=/data/dimension-exports

# 数据源之四：kafka topic（消息体 = JSON 对象，控制列语义同上；位点 checkpoint 自管）
# source.type=kafka
# source.kafka.bootstrap-servers=broker1:9092,broker2:9092
# source.kafka.topic=game-dimensions
# source.kafka.group-id=oddsmaker-dimension-sync
# source.kafka.poll-timeout-ms=3000
# source.kafka.cursor-initial=0=42;1=57
# 鉴权（可选）：SASL_* 协议时 sasl-mechanism/username/password 必填（机制仅 SCRAM-SHA-256/512）
# source.kafka.security-protocol=SASL_PLAINTEXT
# source.kafka.sasl-mechanism=SCRAM-SHA-256
# source.kafka.username=oddsmaker_ro
# source.kafka.password=***
# TLS 证书模式（security-protocol=SSL / SASL_SSL）：自签或私有 CA 需配客户端信任库；
# 不配走 JVM 默认信任库（公有 CA）。明文协议下配置信任库启动即拒（配置漂移防护）
# source.kafka.ssl-truststore-path=/etc/oddsmaker/kafka-client.truststore.p12
# source.kafka.ssl-truststore-password=***
# source.kafka.ssl-truststore-type=PKCS12
# mTLS（broker ssl.client.auth=required）另配客户端证书库三键，单向 TLS 不配即可；
# 私钥口令复用证书库口令（PKCS12 单一口令语义），校验口径同信任库
# source.kafka.ssl-keystore-path=/etc/oddsmaker/kafka-client.keystore.p12
# source.kafka.ssl-keystore-password=***
# source.kafka.ssl-keystore-type=PKCS12

# Agent 行为
agent.batch-size=500
agent.poll-seconds=300
agent.checkpoint-path=/var/lib/oddsmaker-agent/checkpoint.json

# 同步状态上报（Control /api/dimensions/sync-status；url 缺省则跳过）
status.url=https://control.oddsmaker.local/api/dimensions/sync-status
status.token=<admin-token>
status.source-key=agent-mysql-main
```

启动：`./gradlew :agents:dimension-sync-agent:installDist` 后运行 `build/install/dimension-sync-agent/bin/dimension-sync-agent --config=/etc/oddsmaker/agent.properties`。

关键约束（均为已实现行为）：

- **只读账号**：Agent 持有的 DB 账号只授 SELECT 权限。
- **读从库**：连接串指向从库，避免影响线上。
- **增量查询**：必须基于 `updated_at` 或自增 ID，禁止全表扫。水位以类型标签存 checkpoint（`n:` 整型 / `t:` 时间 / `s:` 字符串），恢复时按原类型绑定 PreparedStatement（PG 的 timestamp 列拒绝 bigint 字面量）。
- **限频 + 批量**：默认 5 分钟一轮、每批 500 条，可按需配置。
- **断点续传**：checkpoint 为本地 JSON 文件，原子落盘（tmp + move）；**推送全部成功才前进**，失败下一轮以旧水位重放同窗口——下游 item_dim/level_dim 是 `ReplacingMergeTree(version_ts)`，重放幂等。
- **凭证不出游戏网络**：默认模式下 DB 凭证只在 Agent 本地；上报 Control 的只有位点与计数，拿不到任何源数据。**启用 Control 托管后该原则不再成立**——源端凭证（DB/Kafka/证书库口令与网关 key）经 `resolved` 下发时进入 Control 的 AES-GCM 托管（与 Pull 凭证同一密钥面），取舍是集中运维换托管边界后移；托管面不托管 `agent.checkpoint-path`（本地路径）。
- **心跳可观测**：每轮上报 `POST /api/dimensions/sync-status`（camelCase JSON：`gameId`/`environment`/`sourceKey`/`sourceType`/`cursor`/`lastEventTs`/`pushedCount`/`errorCount`/`lastError`，`x-admin-token` 鉴权）；Control 侧 `GET /api/dimensions/sync-status?gameId=` 返回两口径延迟——`sinceLastPushSeconds`（Agent 存活）与 `sinceLastEventSeconds`（源头新鲜度）。

这也是 **FileImport 方案的归宿**——文件导入本质是 Agent 的一个 source 类型，不用单独立项。

### HTTP Pull（已实现）

Oddsmaker 主动拉取游戏方暴露的查询接口。Control 侧按 (game, environment, sourceKey) 配置定期拉取，**已实现**：仓库 `services/control-service/` 的 `DimensionPullService`（scheduler + 凭证 AES-GCM 加密托管 + 断点续传），翻译成 `RawDimensionChange(source_type=pull)` 走既有 `/v1/batch` 事件入口。

```
GET https://api.game-x.com/v1/items?updated_after=1760000000000&limit=1000
Authorization: Bearer ***
→ 200 OK
  {"items": [...], "next_cursor": "..."}
```

游戏方接口契约：

- 支持 `updated_after` 增量参数（首次拉取不携带；后续传服务端上次下发的 `next_cursor`，或本页最大 `updated_at` 的毫秒值）。
- 响应为 JSON 对象，条目数组键按 `items` / `resources` / `levels` / `data` 顺序探测，可选 `next_cursor` 字符串（空串视为窗口结束）。
- 行内控制列对齐 Agent 语义：`resource_id`/`item_code`/`level_id`/`id`/`code` 之一为资源标识，`op`（缺省 upsert），`updated_at`/`version_ts`/`updated_ts` 之一为版本时间（epoch millis 数字或 ISO-8601 文本），其余列进 `attributes`。

Control 侧行为（均已实现）：

- **凭证加密托管**：`credential` 建档时 AES-GCM 加密落库（密钥 `ODDSMAKER_CREDENTIAL_KEY`，缺省走内置 dev 密钥并 WARN，生产必须更换），任何 API 响应不回显密文。
- **调度**：15s tick，按配置 `intervalSeconds`（缺省 300s）判到期；单页有界延迟，`next_cursor` 续页下一 tick 继续。
- **断点续传**：cursor = 服务端 `next_cursor`，缺省回落本页最大 `version_ts`；推送全部成功才前进，失败下一轮以旧断点重放同窗口——下游 `ReplacingMergeTree(version_ts)` 重放幂等。
- **同表可观测**：拉取进度直接写 `dimension_sync_status`（`source_type=pull`），经既有 `GET /api/dimensions/sync-status` 与 Agent 上报源同口径展示延迟。

配置 API（`dimension:read` 读 / `dimension:manage` 写）：

```http
GET    /api/games/{gameId}/dimension-pulls           列出该游戏全部 Pull 配置
POST   /api/games/{gameId}/dimension-pulls           建档（environment/sourceKey/endpoint/credential 必填）
PUT    /api/games/{gameId}/dimension-pulls/{id}      更新（credential 缺省=保留既有凭证）
DELETE /api/games/{gameId}/dimension-pulls/{id}      删除
POST   /api/games/{gameId}/dimension-pulls/{id}/run  手动触发一轮拉取
```

控制台入口：`/dimensions` 页下半区「HTTP Pull 源配置」——建档/编辑（凭证留空=保留既有）/删除/立即拉取（成功与失败口径都出结果文案），以及 YAML 批量导入/导出（导出凭证不落盘，可编辑后整份粘回导入批量更新）。API 亦可直用，两者同权门控。

建档请求体示例：

```json
{
  "environment": "prod",
  "sourceKey": "items",
  "dimType": "item",
  "endpoint": "https://api.game-x.com/v1/items",
  "credential": "bearer-token-plaintext",
  "intervalSeconds": 300,
  "pageLimit": 1000,
  "enabled": true
}
```

- 适合**游戏方有 API 能力、不愿部署 Agent、不愿开 binlog**的场景（与 Agent 相比，游戏方零部署、只暴露一个只读查询接口）。

### Debezium CDC（未实现）

游戏方部署 Kafka Connect + Debezium Source Connector，把 `items` 表的 binlog 变更写到 Kafka topic，Oddsmaker 订阅这个 topic 做转换。本方案当前没有对应实现，属规划；落地时消费端可复用 `dimension-sync-job` 的事件入口。

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

> **实现现状**：`source_type` 目前仅 Pull 链路写入（`DimensionPullService` 翻译成 `RawDimensionChange(source_type=pull)`）；Agent / Webhook 通道不带该字段，四值枚举是目标抽象。

### 输出：`item_dim`（ClickHouse）

```sql
CREATE TABLE item_dim (
  game_id LowCardinality(String),
  environment LowCardinality(String),
  resource_id String,
  name String DEFAULT '',
  type String DEFAULT '',
  rarity String DEFAULT '',
  category String DEFAULT '',
  description String DEFAULT '',
  version_ts DateTime64(3),
  is_current UInt8 DEFAULT 1
)
ENGINE = ReplacingMergeTree(version_ts)
PARTITION BY (game_id, environment, toYYYYMM(version_ts))
ORDER BY (game_id, environment, resource_id);
```

> **目标形态（未落地）**：SCD2 的 `valid_from` / `valid_to` 列 + `ReplacingMergeTree(is_current)` + `ORDER BY` 含 `valid_from` 的形状当前未落地——`dimensions.sql` 无 `valid_to` 列，作业恒写 `is_current = 1`。

### 版本处理（公共逻辑）

物品属性变更（如改名、调稀有度）时，转换层写入新行：`version_ts` 取源头变更时间，`is_current` 恒为 1；`ReplacingMergeTree(version_ts)` 保证同一 `resource_id` 最新版本胜出，旧版本自然被替换。`op=delete` 当前被下游忽略——不区分下架语义，仍按新行写入（无 delete 分支）。

> **目标形态（未落地）**：SCD2 语义（旧版本 `valid_to` 置变更时间 + `is_current = 0`，新版本 `valid_from` / `valid_to = null`）当前未落地——`dimensions.sql` 无 `valid_to` 列，作业恒写 `is_current = 1`。历史事实按事件时间 join 对应版本维度的"穿越"问题随之未解。

版本处理逻辑放在转换层，**所有 Provider 共享**，不要散落到各 Provider 实现。

## 配置驱动（Control 面已实现，Agent 属性迁移后置）

每个游戏的维度同步在 Control Service 配置，接入新游戏只填表，不改代码。

- **已实现（2026-10，HTTP Pull）**：pull 源的游戏侧配置进 Control 面——`dimension_pull_config` 表按 (game, environment, sourceKey) 存 endpoint/凭证密文/间隔/分页/断点，经 `dimension-pulls` REST API 填表即接入，scheduler 无需改代码。
- **已实现（2026-10，Control YAML 配置面）**：`dimension-pulls` 增两个端点做声明式配置——`GET /export` 导出全部配置为 YAML（凭证永不落盘），`POST /import` 按 (environment, sourceKey) 定位逐条建档/更新，`credential` 缺省=保留既有凭证，方法级事务保证任一条失败整体回滚。接入新游戏可先起一份 YAML 一次性导入，之后导出即得可版本化清单。
- **已实现（2026-10，Agent 属性面 Control 托管）**：Agent 部署属性进 Control 面——`dimension_agent_config` 表按 (game, environment, agentKey) 存非密钥 props 文本 + 密钥键整体 AES-GCM 密文，经 `agent-configs` REST API 填表；`/api/dimensions/agent-configs/resolved` 是 Agent 取配置口（注入 game.id/game.environment/status.source-key，解密合并为一份 Properties 文本）。
- **已实现（2026-10，Agent 端取配置）**：Agent 本地 `agent.properties` 配 `control.config-url` 即启用托管——启动拉一次 `resolved`，下发键逐键覆盖本地值；拉取失败整段回落本地文件（Control 不可达不阻断启动）；优先级 本地 < Control 下发 < System property。托管开关/本地路径类键（`control.config-url`、`agent.checkpoint-path`）永在本地。
- **已实现（2026-10，托管配置热更新）**：本地加配 `control.config-refresh-seconds`（>0）即启轮询热更新——每 N 秒重拉 `resolved`，下发原文与当前应用一致零动作；有变以新配置重建 source/sink/status（全部新件构造成功才释放旧件，checkpoint 不动断点跨换源续传），变更即时生效不等当前轮询间隔走完；拉取失败或新配置校验不过沿用当前配置（Control 不可达不阻断轮询）。边界：source.type 跨类切换（如 mysql→kafka）时游标语义随新源解释（水位与位点不互通），需清断点或确认起点。
- **已实现（Agent / Flink 作业）**：Agent 本地 `agent.properties`（五类 source）与 Flink 作业 System properties（`kafka.topic`、`clickhouse.*` 等）仍是未启用托管时的运行配置面。
- **未实现**：下述目标形态里的 `provider:` 多态与 `auth.token_ref` 密钥引用表——当前 Pull/Agent 凭证为 inline 明文入参 + AES-GCM 加密托管，不做密钥名间接引用。

### Pull 配置 YAML（已实现形态）

```yaml
# oddsmaker 维度同步 Pull 配置导出（凭证不回显；导入时 credential 缺省=保留既有）
dimensions:
  items:
    environment: prod                          # 必填，须属于该游戏
    dimType: item                              # 可缺省，缺省 item
    endpoint: https://api.game-c.com/v1/items  # 必填，须 http/https
    credential: bearer-token                   # 仅首建必填；更新缺省=保留既有
    intervalSeconds: 300                       # 可缺省，缺省 300（10..86400 秒）
    pageLimit: 1000                            # 可缺省，缺省 1000（1..5000）
    enabled: true                              # 可缺省，缺省 true
```

`dimensions` 映射键即 `sourceKey`，与表唯一约束 (game, environment, sourceKey) 同口径——改 `environment` 即另一环境的新档。字段白名单校验：未知字段精确报错（防 `endpont` 类手误静默失效）。导出物不含凭证密文；导出→导入可原样写回非凭证字段，但导入到从未配置过该 (env, sourceKey) 的新档时必须补 `credential`（凭证永不离开 Control 明文托管边界）。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/games/{gameId}/dimension-pulls/export` | 导出该游戏全部 Pull 配置为 YAML（`application/x-yaml`，凭证永不落盘） | `dimension:read` |
| `POST /api/games/{gameId}/dimension-pulls/import` | 请求体为 YAML 文本，按 (environment, sourceKey) 定位逐条建档/更新，返回 `{created, updated, total}`，任一条失败整体回滚 | `dimension:manage` |

### Agent 属性面托管（已实现；启动拉取 + 可选轮询热更新）

每个 Agent 部署一档，唯一约束 (game, environment, agentKey)。键名沿用 `agent.properties` 的 dot-key 命名，按可见性拆两半：

- **props（非密钥，明文可见可改）**：`gateway.endpoint`、`source.*`（URL/查询/目录/协议等）、`agent.batch-size`、`agent.poll-seconds`、`status.url`、`dimension.type`；键白名单校验（未知键/值形状非法精确报错），`source.type` 五选一。
- **secrets（密钥，AES-GCM 密文托管，管理面只回显键名）**：`gateway.api-key`、`status.token`、`source.jdbc.password`、`source.kafka.password`、`source.kafka.ssl-truststore-password`、`source.kafka.ssl-keystore-password`；建档必含 `gateway.api-key`，更新缺省=保留既有密钥（显式给 map 即整体轮换）。
- **不在托管面内的键**：`game.id` / `game.environment` / `status.source-key` 由行 (gameId, environment, agentKey) 下发时自动注入；`agent.checkpoint-path` 是游戏方本地路径，永远本地保留。

| 端点 | 说明 | 权限 |
| --- | --- | --- |
| `GET /api/games/{gameId}/agent-configs` | 列出该游戏全部 Agent 配置档（props 可见，secrets 只回显键名清单） | `dimension:read` |
| `POST /api/games/{gameId}/agent-configs` | 建档：`{environment, agentKey, props, secrets, enabled}`，environment 须属于该游戏，agentKey 同游戏同环境唯一 | `dimension:manage` |
| `PUT /api/games/{gameId}/agent-configs/{id}` | 更新：props 全量替换；secrets 缺省=保留既有密钥 | `dimension:manage` |
| `DELETE /api/games/{gameId}/agent-configs/{id}` | 删除 | `dimension:manage` |
| `GET /api/dimensions/agent-configs/resolved?gameId=&environment=&agentKey=` | Agent 取配置：响应体即可 `Properties.load` 的完整文本（注入三键 + props + 解密后的 secrets），停用档拒绝下发（任何非 200 端回落本地文件） | `dimension:read` |

跨字段组合校验（如 SASL_* 协议必带机制与凭证）仍由 Agent 启动 fail-fast 兜底，Control 侧只管键白名单与值形状。Agent 端本地配 `control.config-refresh-seconds`（>0，须配 `control.config-url`）即启热更新：每 N 秒重拉 `resolved`，原文有变即换源/换通道（checkpoint 不动），失败沿用当前配置。

控制台入口：`/dimensions` 页「Agent 托管配置」区——建档/编辑/删除，props 与 secrets 分两个文本框（secrets 每行 `key=value`，服务端只回显键名清单）；编辑留空 secrets = 保留既有密钥，填写即整体轮换；停用档 Agent 拉取即回落本地文件。API 亦可直用，两者同权门控。

### 目标形态（未实现：provider 多态 + 密钥引用）

下述「一份 YAML 描述该游戏全部维度源」的形态当前未实现，仅 Pull 落地：

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
- Checkpoint 位点（Agent 模式本地 checkpoint；HTTP Pull 模式 `next_cursor` 断点，同表同口径）
- 维度记录总数变化（异常波动告警，可能是源头出问题）

接入出问题时，游戏方和 Oddsmaker 运营都能快速定位是哪个环节。

## 落地优先级

不需要一上来全做。建议按价值/成本排序：

1. **Webhook Push**：已可用（`/v1/batch` + `event_type=dimension`，零新增组件）。
2. **Sync Agent（MySQL/PostgreSQL source）**：已实现。
3. **HTTP Pull**：已实现（2026-10，Control scheduler + 凭证加密托管 + 断点续传，mock 游戏 API 端到端实测）。
4. **Sync Agent（CSV/Excel source）**：已实现。
5. **Debezium CDC**：未实现。

## 与资源事件设计的关系

本文档是资源事件设计（事实数据）的补充：

| | 资源事件（事实） | 维度同步（维度） |
|---|---|---|
| 数据来源 | SDK 玩家行为上报 | 游戏业务库 / 配置 |
| 写入表 | `events` | `item_dim` / `level_dim` / ... |
| 频率 | 高频（每玩家每秒多条） | 低频（每天少量变更） |
| 查询角色 | 被聚合的事实 | 被 join 的维度 |
| 同步方式 | SDK → Gateway → Kafka → ClickHouse | 本文四种方案之一 |

两边在 ClickHouse 层汇合，分析查询通过 `resource_id` 等键 join。
