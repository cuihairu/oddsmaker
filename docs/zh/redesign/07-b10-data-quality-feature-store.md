# B10 设计定稿：数据质量指标与共享 Feature 双写

本文是 B10 批次的设计定稿，契约来自计划书 §5.3 与 B10 行（`06-od2-restructure-plan`）：Data Quality 五指标 + Game Data Health 页方向，共享 Feature 双写 schema，不做 Data Lineage 系统。本批只定口径与 schema，代码与迁移留给实施批。所有信号点均读码核对，文件与行号见各节，核对基于 2026-10-07 工作副本。

## 1 范围

- 做：五指标口径定义、落库表 schema 与写入路径、feature_store 双写表 schema、健康页取数方向。
- 不做：Data Lineage 系统（§6 只记方向，即计划书要求的全额）；enrich 迟到 side output（迟到口径落网关边缘，见 §3.5）；指标历史回填（从上线窗口起算）；feature_store 的 Analytics 侧真实消费（后续批次）。

## 2 现状信号点

| 信号 | 位置 | 现状 |
|---|---|---|
| 网关拒绝原因 9 种：`invalid_schema` / `api_key_scope_mismatch` / `unknown_event` / `invalid_timestamp` / `pii_blocked` / `payload_too_large` / `trust_escalation` / `blocked` / `kafka_error` | `BatchController.java:124-255` | 逐事件判定，随批量响应返回给接入方，不落库 |
| 网关幂等去重（`replayGuard.consumeEventId`） | `BatchController.java:188-193` | 同实例 SDK 重试静默吸收，计入 `resp.duplicates`，不进 Kafka |
| 采样剔除 | `BatchController.java:215` | `OUTCOME_SAMPLED_OUT`，仅进 Inspector 缓冲 |
| 服务端时间打点 | `BatchController.java:147-148, 420-422` | `ts_server` 缺省时网关补 `Instant.now()`，SDK 提供则采信 |
| enrich 二次去重 | `EventsEnrichJob.java:396, 417` | `DedupFunction` 判定 duplicate → deadletter（reason=`duplicate`） |
| enrich 水位 | `EventsEnrichJob.java:66-67` | `BoundedOutOfOrderness(5min)`，无迟到 side output |
| deadletter topic | `DlqPublisher`（`oddsmaker.deadletter`） | 全仓 grep 无任何消费者 |
| Live Inspector | `EventInspectorBuffer` | 内存环形队列，200 条/作用域、TTL 10 分钟、重启即空 |

指标缺失的根因不是没有信号，是信号全部走单次 HTTP 响应或进程内存，没有任何一处持久化。Inspector 是调试面不是指标源，本设计不复用它。

## 3 五指标口径

统计维度统一为 `(game_id, environment, 5 分钟窗口)`。分子分母默认来自网关边缘计数，仅 duplicate 的 enrich 层分子例外（§3.4）。

### 3.1 event_valid_rate

`accepted / received`。

- `received`：批量请求通过鉴权与批级校验后进入逐事件判定的条数。
- `accepted`：发布进 `events_validated` 的条数；网关幂等去重吸收的重试计入 accepted（与代码现状一致，`BatchController.java:190-191` 把 duplicate 同时计入 `resp.accepted`），同时单列 `duplicates_gateway` 供 §3.4 使用。

### 3.2 drop_rate

`rejected_total / received`，`rejected_total` 为 §2 表中 9 种拒绝原因之和，读时按原因展开展示。`kafka_error` 是平台故障而非接入方问题，健康页必须单列标记，不摊进接入质量。

### 3.3 unknown_event_rate

`rejected_unknown_event / received`。drop_rate 的子项单列：接入期事件名与 Tracking Plan 失配是最常见的拒绝来源，需要独立阈值告警，混在 drop_rate 里会稀释。

### 3.4 duplicate_rate

`(duplicates_gateway + duplicates_enrich) / received`。

- `duplicates_gateway`：replayGuard 吸收的同实例重试（`BatchController.java:191`）。
- `duplicates_enrich`：`DedupFunction` 拦下、经 deadletter 到达的跨实例/跨重放重复（`EventsEnrichJob.java:417`）；信号经 DLQ 消费者进表（§4.3）。
- 分母统一用网关边缘 `received`，两层分子折到同一分母。enrich 层分子相对网关窗口晚一个处理周期，健康页按窗口对齐展示，不追求跨层实时。

### 3.5 late_event_rate

`late / received`，`late := ts_server − ts_client > 5 分钟`。阈值与 enrich 水位 `BoundedOutOfOrderness(5min)` 对齐（`EventsEnrichJob.java:66-67`），`ts_server`/`ts_client` 均在网关入口可得（§2）。

- `invalid_timestamp`（时间戳非法或超合理偏移被拒）不计入 late——那是有效性问题，已在 §3.2。
- 不在 enrich 加迟到 side output：网关边缘已有双时间戳，第二处迟到定义只会造出两个对不上的率。水位以下的迟到事件当前照常写入 CH，B10 不改这条链路。

### 3.6 恒等式

`received = accepted + rejected_total + sampled_out`（accepted 含 `duplicates_gateway`）。control 侧落表时校验，破式记日志。计数路径漏分支时恒等式先于任何阈值告警发现。

## 4 落库方案

### 4.1 指标表（PG control 库，实施批新迁移）

```sql
CREATE TABLE data_quality_metrics (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_sec INT NOT NULL DEFAULT 300,
    received BIGINT NOT NULL DEFAULT 0,
    accepted BIGINT NOT NULL DEFAULT 0,
    sampled_out BIGINT NOT NULL DEFAULT 0,
    rejected_schema BIGINT NOT NULL DEFAULT 0,
    rejected_unknown_event BIGINT NOT NULL DEFAULT 0,
    rejected_invalid_timestamp BIGINT NOT NULL DEFAULT 0,
    rejected_pii_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_payload_too_large BIGINT NOT NULL DEFAULT 0,
    rejected_trust_escalation BIGINT NOT NULL DEFAULT 0,
    rejected_blocked BIGINT NOT NULL DEFAULT 0,
    rejected_scope_mismatch BIGINT NOT NULL DEFAULT 0,
    rejected_kafka_error BIGINT NOT NULL DEFAULT 0,
    duplicates_gateway BIGINT NOT NULL DEFAULT 0,
    duplicates_enrich BIGINT NOT NULL DEFAULT 0,
    late BIGINT NOT NULL DEFAULT 0,
    dlq_other BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_data_quality_window UNIQUE (game_id, environment, window_start, window_sec)
);
CREATE INDEX idx_data_quality_recent
    ON data_quality_metrics (game_id, environment, window_start DESC);
```

两个取舍：九种拒绝原因各占一列而不建 reason 维表——原因集合由代码枚举封闭（`BatchController` 一处定义），维表的 join 换不来开放性；率不落列，读时算——分子分母同行存储，落率必然产生与计数不一致的第二份真相。

### 4.2 网关侧写入路径

网关进程内维护 `(game_id, environment, window)` 键的计数器，`@Scheduled` 每 60 秒快照 POST 到 control `/internal/data-quality`（internal-token 鉴权，与 `InternalBlockListController` 同一通道模式；网关侧调用先例是 `BlockListClient`）。每份快照携带自窗口起点以来的累计值，control 按 `uq_data_quality_window` 直接覆盖该键，乱序与重复投递无害，不做增量合并。

网关重启丢失当前窗口内存计数：60 秒快照下最多损失一个窗口的部分计数。指标是观测面不是计费面，接受。

### 4.3 enrich 层 duplicate 写入路径

control-service 新增轻量 Kafka 消费者订阅 `oddsmaker.deadletter`：reason=`duplicate` 的记录按 `game_id`/`environment`/事件时间归窗累加 `duplicates_enrich`，其余 reason 累加 `dlq_other`。这同时闭合 deadletter 自上线无消费者的缺口。行数速率 = 每秒事件量级，单分区顺序消费即可，不引消费组并发。

### 4.4 保留策略

5 分钟粒度保留 30 天，实施批配定时清理。不做 rollup：验收口径是「指标表可查、健康页有数可看」，30 天 × 5 分钟 ×（游戏 × 环境）的行数在 PG 直接可查；出现跨季度回看需求时再加日汇总，现在加是猜需求。

### 4.5 Game Data Health 页方向

web 新页，control 提供两个只读端点：窗口序列（五率 + 恒等式余项）与拒绝原因 Top。数据面只有 §4.1 一张表，无第二数据源；页面布局留实施批。

## 5 feature_store 摘要表 schema 定稿

契约原文（`06-od2-restructure-plan` §5.3）：「共享在 risk-job 特征作业产出双写：一份进 `risk_features`，一份进共享 `feature_store` 摘要表」。以下为 B10 承诺的 schema：

```sql
-- B10 共享 Feature 摘要表（计划书 §5.3）：risk-job 特征作业与 risk_features 同源双写。
-- features 为该 (scope, 窗口) 全部特征值的扁平映射，键编码窗口避免同名特征互踩；
-- Analytics 侧取数不再对 risk_features 做 pivot。scope_key 编码沿用 risk_features 约定
-- （PLAYER:<user_id> / DEVICE:<device_id> / IP:<client_ip>）。

CREATE TABLE feature_store (
    id BIGSERIAL PRIMARY KEY,
    game_id VARCHAR(32) NOT NULL,
    environment VARCHAR(100) NOT NULL,
    scope_key VARCHAR(256) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    features JSONB NOT NULL,
    as_of TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (game_id) REFERENCES games(id),
    CONSTRAINT uq_feature_store_window UNIQUE (game_id, environment, scope_key, window_start, window_end)
);

CREATE INDEX idx_feature_store_scope
    ON feature_store (game_id, environment, scope_key, window_end DESC);
```

`features` JSONB 形如 `{"events_5m": 42.0, "fail_ratio_5m": 0.17}`——与 `risk_features` 中该 scope 同窗口的行一一对应的扁平映射，窗口编码进键名。行数与 risk_features 同量级（每 scope 每窗口一行，pivot 收敛），不超过原表。

双写机制：risk-job 落库点是 `RiskJob.featureJdbcSink`（`RiskJob.java:312`，`SinkFunction<FeatureRow>` JDBC upsert）。双写在同一 sink、同一连接、同一批内追加第二条 upsert——同库无需分布式事务；`feature_store` 写失败与 `risk_features` 写失败同等对待（sink 抛错、作业重放），不做主表成功/摘要表失败的拆分语义。

消费方：实施批在 control 增加只读端点（按 scope 取最近窗口行）。Analytics 真实消费属后续批次，本批交付表与写入即满足「不占用 B5/B6 关键路径」。保留策略与 risk_features 保持一致（当前两者均无 TTL，不单方面加）。

## 6 Data Lineage：只记方向

计划书要求不建 Lineage 系统，本节即全额交付——指标来源链与 `04-redesign §5` 一致：

| 数据 | 产出 | 存储 | 消费 |
|---|---|---|---|
| 五项质量指标计数 | 网关边缘判定 + enrich 去重/DLQ | PG `data_quality_metrics` | Game Data Health 页 |
| 风控特征 | risk-job 特征作业 | PG `risk_features` | FEATURE 规则评估 |
| 共享特征摘要 | risk-job 同源双写 | PG `feature_store` | control 只读端点（后续 Analytics） |
| 重复事件 | 网关 replayGuard / enrich DedupFunction | 计数 + deadletter | §4.3 消费者 |

## 7 验收对照与实施批清单

todo B10 验收三条的对应关系：

| 验收 | 设计落点 | 实施批待写代码 |
|---|---|---|
| 指标表落库可查 | §4.1 表 + §4.2/§4.3 写入路径 | 迁移文件；网关计数器与快照上报；control `/internal/data-quality` 端点；DLQ 消费者 |
| 健康页有数可看 | §4.5 | web 新页 + control 两个只读查询端点 |
| feature_store 建表迁移与双写合成器测试 | §5 | 迁移文件；`featureJdbcSink` 扩展双写；合成器测试；无 Lineage 代码 |

---

信号点行号核对于 2026-10-07 工作副本（`BatchController.java`、`EventInspectorBuffer.java`、`EventsEnrichJob.java`、`RiskJob.java`、`DlqPublisher.java`）；schema 草案风格对齐 `V0.9.18__risk_features.sql`。本批未改动任何代码与迁移。
