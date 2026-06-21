# 后续推进规划

本文档记录 Oddsmaker 当前未完成的功能项，按优先级排列，包含设计、实现方案和验收标准。每项推进前先在此登记，避免散落讨论。

已完成的功能见各阶段文档：
- [资源事件设计](../analysis/jobs)（事实数据）
- [维度数据同步](./dimension-sync)（维度数据）
- [SDK 设计规范](./sdk-design)（客户端接入）

## 优先级总览

| # | 项目 | 阶段 | 类型 | 解锁价值 |
|---|---|---|---|---|
| 1 | identity-merge Flink job | P2.2 | 后端作业 | SDK identify 真正生效 |
| 2 | risk-job 规则动态化 | P3.2 | 作业增强 | 加规则不重启 |
| 3 | risk-job 规则类型扩展 | P3.2 | 作业增强 | 覆盖更多风控场景 |
| 4 | 维度同步 Agent | 横向 | 新模块 | 维度同步落地 |
| 5 | 符号化服务 | P4.3 | 新服务 | Crash 可读 |
| 6 | 预测模型训练管线 | P4.4 | 管线 | ML 模型实际可用 |

---

## 1. identity-merge Flink job

### 目标

消费 SDK 上报的 `$identify` 事件（event_type=identity），维护 ClickHouse `identities` 表，把同一用户的多个 `device_id` / `player_id` 归并到同一个 `identity_id`，保证留存/LTV 不重复计算。

### 背景

- SDK 已提供 `identify(userId)` / `setPlayer(playerId)`（见 [sdk-design.md](./sdk-design)），上报 `$identify` 事件。
- ClickHouse `identities` 表已建好（schema.sql line 202）：`identity_id, user_id, player_id, character_ids, device_ids, first_seen, last_seen, risk_score`。
- 缺一个作业把这些事件落到 `identities` 表。

### 设计

```
events_raw (Kafka)
    │ 过滤 event_type='identity'
    ▼
IdentityMergeJob (Flink)
    │ keyBy(user_id)
    │ 状态：identity_id 映射 + device_id/player_id 集合
    │ 归并逻辑：
    │   - user_id 已存在 → 复用 identity_id，追加 device_id/player_id
    │   - user_id 新增 → 生成新 identity_id
    │   - previous_user_id 出现 → 合并两个 identity 到新 user_id
    ▼
ClickHouse identities (ReplacingMergeTree by last_seen)
```

### 实现要点

- `keyBy(user_id)` 保证同一用户的归并状态在同一并行度。
- 状态：`ValueState<IdentityState>`，包含 `identity_id`、`Set<device_id>`、`Set<player_id>`、`first_seen`。
- 输出：每次状态变更写一行到 `identities`，用 `ReplacingMergeTree(last_seen)` 自动合并。
- TTL：状态保留 90 天（用户长期不活跃后释放）。
- 幂等：基于 `identity_id` + `last_seen` 去重。

### 涉及文件

- 新增 `jobs/flink/identity-merge-job/`（build.gradle.kts + IdentityMergeJob.java）
- 注册到 `settings.gradle.kts`
- 更新 `docs/zh/analysis/jobs.md`

### 验收

- 上报 `$identify` 事件后，`identities` 表出现对应行，`device_ids` 包含当前 device_id。
- 同一 device 先后 identify 两个 user_id，两个 identity 被合并（`previous_user_id` 触发）。
- sessions/retention 查询能按 `identity_id` 聚合（不重复计 device 和 user）。

---

## 2. risk-job 规则动态化

### 目标

risk-job 当前用 `-D` 系统属性配规则（`risk.threshold.amount` 等），加规则要重启。改为从 Control Service 按 `game_id + environment` 拉取活跃 `RiskRuleEntity`，定时刷新。

### 设计

```
Control Service (RiskRuleRepo)
    │ GET /api/risk/rules?gameId=X&env=prod&status=ACTIVE
    │ （定时拉取，默认 60s）
    ▼
RuleSupplier (Flink 算子状态 / 广播流)
    │ 广播到所有 risk 检测算子
    ▼
risk-job 检测逻辑（按规则配置执行）
```

### 实现要点

- 用 Flink **Broadcast State Pattern**：规则流广播到事件流，事件处理时读取当前规则。
- `RuleFetcher`：定时 HTTP 拉取，解析 JSON 到 `List<RiskRule>`。
- 规则映射：`RiskRuleEntity.ruleType` → 对应的检测函数（THRESHOLD/FREQUENCY/...）。
- 规则失效：拉取时删除不再 ACTIVE 的规则，广播清除。
- 兼容：保留 `-D` 系统属性作为 fallback（Control Service 不可用时用静态规则）。

### 涉及文件

- `jobs/flink/risk-job/.../RuleFetcher.java`（新）
- `jobs/flink/risk-job/.../RuleBroadcaster.java`（新）
- `jobs/flink/risk-job/.../RiskJob.java`（改：接入广播流）
- Control Service 暴露只读规则查询 API（如已有则复用）

### 验收

- 在 Control Service 新增一条 RiskRule，60s 内 risk-job 生效，无需重启。
- 规则改为 PAUSED，60s 内停止触发。
- Control Service 不可用时，risk-job 退回 `-D` 静态规则。

---

## 3. risk-job 规则类型扩展

### 目标

当前只支持 THRESHOLD + FREQUENCY。补充 VELOCITY / RATIO / PATTERN 三种规则类型，覆盖路线图 P3.2 列举的全部检测场景。

### 设计

| 规则类型 | 检测逻辑 | Flink 实现 |
|---|---|---|
| THRESHOLD | `resource_amount > N` | 逐事件 filter（已实现） |
| FREQUENCY | 窗口内事件数 > N | sliding window count（已实现） |
| **VELOCITY** | 窗口内资源变动总和 > N | sliding window sum(resource_amount) |
| **RATIO** | 窗口内 source/sink 比例超阈值 | 双流 join 或同窗口聚合 |
| **PATTERN** | 多事件序列（如 login→purchase→refund 短时间内） | CEP（Flink Complex Event Processing） |

### 实现要点

- VELOCITY：在现有 FREQUENCY 的 window 里把 `count()` 换成 `sum(resource_amount)`。
- RATIO：同一窗口内分 `flow_type='source'` 和 `'sink'` 两个聚合，比值判断。
- PATTERN：用 `org.apache.flink.cep` 库，定义 `Pattern<RiskInput, ?>`，超时和匹配都输出 RiskHit。
- 每种规则类型实现一个 `RuleDetector` 接口，`RuleBroadcaster` 根据规则类型分发。

### 涉及文件

- `jobs/flink/risk-job/.../detector/ThresholdDetector.java`
- `jobs/flink/risk-job/.../detector/FrequencyDetector.java`
- `jobs/flink/risk-job/.../detector/VelocityDetector.java`（新）
- `jobs/flink/risk-job/.../detector/RatioDetector.java`（新）
- `jobs/flink/risk-job/.../detector/PatternDetector.java`（新，依赖 flink-cep）

### 验收

- VELOCITY：10 分钟内某资源 source 总和超 1,000,000 → 触发。
- RATIO：10 分钟内 source/sink > 10 → 触发（异常产出比例）。
- PATTERN：同一 subject 在 5 分钟内出现 `level_complete` → `revenue` → `refund` → 触发。

---

## 4. 维度同步 Agent

### 目标

落地 [dimension-sync.md](./dimension-sync) 设计的 `oddsmaker-agent`，提供 DB / 文件 / Push 三类 source 适配器，让游戏方零代码接入维度同步。

### 设计

见 [dimension-sync.md](./dimension-sync) 的完整设计。Agent 是独立仓库 `oddsmaker-agent`，本仓库只负责：
- Gateway 侧的 `/v1/dimension` 事件接收（**复用现有 Gateway，加 event_type='dimension' 路由**）
- Flink 侧消费 `dimension_define` 事件写 `item_dim` / `level_dim`
- Control Service 侧的同步状态监控

### 实现要点（本仓库部分）

- Gateway：`POST /v1/batch` 已支持任意事件，dimension 事件只是 `event_type='dimension'` 的特殊事件。
- 新增 Flink job `dimension-sync-job`：消费 events_raw 里的 dimension 事件，按 SCD2 逻辑写维度表。
- Control Service：加 `dimension_sync_status` 表记录每个游戏的最后同步时间/位点。
- ClickHouse：新增 `item_dim` / `level_dim` 表（SCD2，见 dimension-sync.md）。

Agent 本身（独立仓库）提供：
- `mysql` / `postgres` source（增量查询）
- `csv-file` / `excel-file` source（watch 目录）
- 推送到 Gateway 的 sink

### 涉及文件（本仓库）

- 新增 `jobs/flink/dimension-sync-job/`（消费 dimension 事件，写维度表）
- 新增 `schema/sql/clickhouse/dimensions.sql`（item_dim / level_dim SCD2 表）
- Control Service 加 `DimensionSyncStatus` 实体和 API

### 验收

- 通过 Agent 推送一条物品变更，60s 内 ClickHouse `item_dim` 出现对应行。
- 物品改名后，item_dim 保留旧版本（valid_to 非空）+ 新版本（is_current=1）。
- Control Service 的 `/api/dimensions/sync-status` 返回每个游戏的同步延迟。

---

## 5. 符号化服务

### 目标

Crash 报告的 stack_trace 是原始地址（偏移），需要符号化为可读的函数名/文件名/行号。提供 dSYM (iOS) / Proguard mapping (Android) / source map (Web) 管理 + 符号化 API。

### 设计

```
SDK 上报 crash (props.stack_trace = 原始地址)
    │
    ▼
Symbolicator Service (独立微服务)
    │ 查询 mapping 文件（按 app_version + platform）
    │ 调用对应符号化器：
    │   - iOS: dSYM (dwarfdump / atos)
    │   - Android: proguard mapping (retrace)
    │   - Web: source map (source-map)
    ▼
回填 symbolicated_stack 到 events.props 或单独表
```

### 实现要点

- 独立微服务（Python/Go），避免 Java 栈污染。
- mapping 文件按 `game_id + platform + app_version` 存储（对象存储或本地）。
- 符号化是异步的：crash 入库后发消息到 `oddsmaker.crash_symbolicate` topic，符号化服务消费、回填。
- 提供 API 让游戏方上传 mapping 文件（`POST /api/symbols/upload`）。

### 涉及文件

- 新仓库 `oddsmaker-symbolicator`（Python/Go）
- Control Service 加 `SymbolMapping` 实体（mapping 文件元数据）
- Control Service 加 `POST /api/symbols/upload`、`GET /api/symbols` API
- Flink `crash-enrich-job`（可选）：消费 crash 事件发到符号化 topic

### 验收

- 游戏方上传 iOS dSYM 后，新上报的 crash 在 60s 内显示符号化堆栈。
- 同一 crash 多次上报只符号化一次（按 crash_hash 去重）。
- 缺 mapping 文件时标记 `unsymbolicated`，不阻塞。

---

## 6. 预测模型训练管线

### 目标

`MLModelService` 已有模型管理骨架（注册/部署/评估），但没有实际训练调度。补流失预测 / pLTV / 付费倾向的训练管线。

### 设计

```
Control Service: MLModelService
    │ 配置模型（类型、特征、目标）
    ▼
Training Job（定时调度，每日/每周）
    │ 1. 从 ClickHouse 抽特征（用户行为 + 资源 + 会话）
    │ 2. 训练（XGBoost / LightGBM / 简单 LR）
    │ 3. 评估（AUC / LogLoss / 校准度）
    │ 4. 注册到 MLModelService（版本化）
    ▼
Serving（Control Service 或独立服务）
    │ 按需打分：流式（Flink）或批量（ClickHouse UDF）
    ▼
predictions 表（user_id, model_id, score, predicted_at）
```

### 实现要点

- 训练管线用 Python（scikit-learn / XGBoost），作为独立 job（类似 Flink job 但走 Python）。
- 特征工程：基于 events 表的 SQL 抽特征（7/30 天行为聚合）。
- 模型存储：MLflow 或简单的文件 + 版本号。
- 打分：优先批量（每日跑一次，写回 ClickHouse），实时打分后续再加。
- 模型类型：
  - **churn**（流失预测）：目标 = 14 天无事件
  - **pltv**（pLTV）：目标 = 30 天累计收入
  - **propensity**（付费倾向）：目标 = 首次付费

### 涉及文件

- 新仓库 `oddsmaker-ml-pipeline`（Python）
- Control Service 完善 `MLModelService` 的训练调度和模型 serving API
- ClickHouse 加 `predictions` 表
- Flink job（可选）：实时打分，消费事件 + 查模型 + 输出预测

### 验收

- 配置一个 churn 模型，每日训练，AUC > 0.7。
- 模型注册到 Control Service 后，可通过 API 查询某用户的流失概率。
- predictions 表按日更新，覆盖所有活跃用户。

---

## 推进节奏建议

1. **先做 1（identity-merge）**：让刚做的 SDK identify 形成闭环，工作量小（一个 Flink job）。
2. **再做 2（risk 规则动态化）**：让 risk-job 进入生产可用状态。
3. **接着 3（规则类型扩展）**：补全风控能力。
4. **4（维度同步 Agent）**：横向能力，解锁维度数据接入。
5. **5（符号化）和 6（ML 管线）**：独立大件，按业务需要再排。
