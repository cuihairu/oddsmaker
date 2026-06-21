# Flink 作业说明

包含 5 个作业：
- events-enrich-job：校验/去重/富化（UA/GeoIP）→ ClickHouse events
- sessions-job：30 分钟会话窗口 → ClickHouse sessions
- retention-job：D0/D1/D7/D30 留存 → ClickHouse retention_daily
- funnels-job：两步漏斗（level_start→level_complete，超时 24h 可配）→ ClickHouse funnels_2step
- risk-job：实时风控检测 → Kafka `oddsmaker.risk_events` + ClickHouse risk_events

## risk-job（实时风控）

消费 `oddsmaker.events_raw`，对每条事件做两类规则检测，命中后输出 `risk_events` 到 Kafka（供 Control Service 实时告警/审核）和 ClickHouse（供风控看板和审计）。

当前内置规则（参数通过 `-D` 系统属性注入）：

| 规则 ID | 类型 | 触发条件 | 默认参数 |
|---|---|---|---|
| `risk-threshold-amount` | THRESHOLD | `resource_amount` 超阈值 | `risk.threshold.amount=100000` |
| `risk-frequency-burst` | FREQUENCY | 同 subject 滑动窗口内事件数超阈值 | `risk.frequency.window-minutes=10`<br>`risk.frequency.max-events=1000` |

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
  -Drisk.frequency.max-events=1000
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
- 规则参数后续接入 Control Service 的 `RiskRuleEntity`，按 `game_id + environment` 拉取活跃规则（HTTP 定时刷新）。
- 规则类型扩展：VELOCITY（窗口内资源变动总和）、RATIO（source/sink 比例）、PATTERN（多事件序列）。
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
