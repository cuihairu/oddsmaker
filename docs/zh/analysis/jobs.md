# Flink 作业说明

包含 7 个作业：
- events-enrich-job：校验/去重/富化（UA/GeoIP）→ ClickHouse events
- sessions-job：30 分钟会话窗口 → ClickHouse sessions
- retention-job：D0/D1/D7/D30 留存 → ClickHouse retention_daily
- funnels-job：两步漏斗（level_start→level_complete，超时 24h 可配）→ ClickHouse funnels_2step
- risk-job：实时风控检测 → Kafka `oddsmaker.risk_events` + ClickHouse risk_events
- identity-merge-job：消费 `$identify` 事件归并 device_id/player_id 到 identity_id → ClickHouse identities
- dimension-sync-job：消费维度事件（event_type=dimension）→ ClickHouse item_dim / level_dim

## identity-merge-job（身份归并）

消费 `oddsmaker.events_raw` 里的 `$identify` 事件（`event_type=identity`），维护 ClickHouse `identities` 表，把同一用户的多个 `device_id` / `player_id` 归并到同一个 `identity_id`，保证留存/LTV 不重复计算。

工作方式：
- 按 `game_id + environment + user_id` 分组，维护 `ValueState<IdentityState>`（identity_id、device_ids 集合、player_ids 集合、first_seen/last_seen）。
- 新 user_id → 生成新 identity_id；已存在 → 复用并追加 device_id/player_id。
- 状态 TTL 90 天（用户长期不活跃后释放）。
- 每次 identify 事件输出一行到 ClickHouse `identities`（ReplacingMergeTree by last_seen，自动合并）。

启动示例：

```bash
flink run -c io.oddsmaker.jobs.identity.IdentityMergeJob jobs/flink/identity-merge-job/build/libs/identity-merge-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default
```

验证：

```sql
SELECT identity_id, user_id, device_ids, player_id, first_seen, last_seen
FROM identities
WHERE user_id = 'u123';
```

**v1 限制**：当前不做 `previous_user_id` 的跨 user 合并（需跨 key 读状态，后续版本用 broadcast state 实现）。游客转账号后，旧 device 的 identity 仍保留，新 identity 关联同一 device，分析侧可通过 `device_ids` 交集做二次归并。

## dimension-sync-job（维度同步）

消费 `oddsmaker.events_raw` 里的维度事件（`event_type=dimension`），把物品/关卡定义从游戏侧同步到 ClickHouse 维度表。设计详见 [维度数据同步](../reference/dimension-sync)。

工作方式：
- 从事件的 `props_json` 解析维度属性（`dim_type`、`resource_id`/`level_id`、`name`、`rarity` 等）。
- 按 `dim_type` 分流：`item` → `item_dim`，`level` → `level_dim`。
- ClickHouse 维度表用 `ReplacingMergeTree(version_ts)`，重复写入保留最新版本。

前置：执行 `schema/sql/clickhouse/dimensions.sql` 创建 `item_dim` 和 `level_dim` 表。

启动示例：

```bash
flink run -c io.oddsmaker.jobs.dimension.DimensionSyncJob jobs/flink/dimension-sync-job/build/libs/dimension-sync-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default
```

验证：

```sql
SELECT resource_id, name, type, rarity FROM item_dim FINAL
WHERE game_id = 'game_demo' AND environment = 'prod';
```

## risk-job（实时风控）

消费 `oddsmaker.events_raw`，对每条事件做两类规则检测，命中后输出 `risk_events` 到 Kafka（供 Control Service 实时告警/审核）和 ClickHouse（供风控看板和审计）。

当前内置规则（参数通过 `-D` 系统属性注入）：

| 规则 ID | 类型 | 触发条件 | 默认参数 |
|---|---|---|---|
| `risk-threshold-amount` | THRESHOLD | `resource_amount` 超阈值 | `risk.threshold.amount=100000` |
| `risk-frequency-burst` | FREQUENCY | 同 subject 滑动窗口内事件数超阈值 | `risk.frequency.window-minutes=10`<br>`risk.frequency.max-events=1000` |
| `risk-velocity-amount` | VELOCITY | 同 subject 窗口内资源变动总和超阈值 | `risk.velocity.max-amount=1000000` |
| `risk-ratio-source-sink` | RATIO | 同 subject 窗口内 source/sink 比例超阈值 | `risk.ratio.max-source-sink=10` |

VELOCITY 检测窗口内资源总量异常（如 10 分钟内产出金币超 100 万），RATIO 检测产出/消耗比异常（如 source/sink > 10 说明只产不耗，疑似刷资源）。PATTERN（多事件序列，如登录→购买→退款）后续用 Flink CEP 实现。

**阈值动态化**：所有阈值默认从 `-D` 系统属性读取，但若配置了 Control Service 地址，risk-job 会定时（默认 60s）拉取活跃 `RiskRuleEntity`，按 `ruleType` + `triggerThreshold` 覆盖默认阈值。运营在 Control Service 改了规则阈值，60s 内生效，无需重启 job。Control Service 不可用时自动回退到 `-D` 默认值。

subject 优先级：`user_id`（PLAYER）> `device_id`（DEVICE）。

输出字段对齐 ClickHouse `risk_events` 表：`game_id, environment, ts, risk_event_id, source_event_id, rule_id, risk_type, severity, subject_type, subject_id, score, action, reason, evidence`。

启动示例：

```bash
flink run -c io.oddsmaker.jobs.risk.RiskJob jobs/flink/risk-job/build/libs/risk-job.jar \
  -Dkafka.bootstrap=localhost:9092 \
  -Dregistry.url=http://localhost:8081/apis/registry/v2 \
  -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default \
  -Drisk.threshold.amount=100000 \
  -Drisk.frequency.window-minutes=10 \
  -Drisk.frequency.max-events=1000 \
  -Dcontrol.url=http://localhost:8085 \
  -Dcontrol.gameId=game_demo \
  -Dcontrol.token=admin \
  -Drule.refresh-ms=60000
```

验证：

```sql
-- ClickHouse
SELECT risk_type, severity, count() FROM risk_events
WHERE ts >= now() - INTERVAL 1 HOUR
GROUP BY risk_type, severity;

-- Kafka（实时告警消费）
kafka-console-consumer --bootstrap-server localhost:9092 --topic oddsmaker.risk_events
```

扩展规划：
- 规则参数后续接入 Control Service 的 `RiskRuleEntity`，按 `game_id + environment` 拉取活跃规则（HTTP 定时刷新）。**✅ 已实现：RuleFetcher 定时拉取 `/api/risk-dashboard/rules/{gameId}`，按 ruleType 覆盖阈值。**
- 规则类型扩展：VELOCITY（窗口内资源变动总和）、RATIO（source/sink 比例）、PATTERN（多事件序列）。**✅ VELOCITY/RATIO 已实现；PATTERN 待 Flink CEP。**
- 命中后动作接入 Control Service 的 ReviewQueue 和 Webhook 通知。

启动方式
- 依赖：Kafka、Apicurio Registry、ClickHouse 已运行（infra/docker-compose.yml）
- 构建并运行（需要本机安装 Flink 命令行）
```bash
scripts/run_flink.sh
```
环境变量（可选覆盖）
- KAFKA_BOOTSTRAP（默认 `localhost:9092`）
- REGISTRY_URL（默认 `http://localhost:8081/apis/registry/v2`）
- KAFKA_TOPIC（默认 oddsmaker.events_raw）
- CLICKHOUSE_URL（默认 `jdbc:clickhouse://localhost:8123/default`）
- KAFKA_DLQ（默认 oddsmaker.deadletter）
- GEOIP_MMDB（GeoLite mmdb 路径，可空）

验证
- ClickHouse：`SELECT count() FROM events;`、`SELECT count() FROM sessions;`、`SELECT count() FROM risk_events;`
- DLQ：消费 `oddsmaker.deadletter` 查看无效/重复事件
- 风控：消费 `oddsmaker.risk_events` 查看实时命中

说明
- JDBC Sink 已做批量与重试；如需端到端事务语义，后续可替换为两阶段提交 sink
- GeoIP 文件可选；未提供时不影响运行
