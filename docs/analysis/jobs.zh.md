# Flink 作业说明

包含 4 个作业：
- events-enrich-job：校验/去重/富化（UA/GeoIP）→ ClickHouse events
- sessions-job：30 分钟会话窗口 → ClickHouse sessions
- retention-job：D0/D1/D7/D30 留存 → ClickHouse retention_daily
- funnels-job：两步漏斗（level_start→level_complete，超时 24h 可配）→ ClickHouse funnels_2step

启动方式
- 依赖：Kafka、Apicurio Registry、ClickHouse 已运行（infra/docker-compose.yml）
- 构建并运行（需要本机安装 Flink 命令行）
```bash
scripts/run_flink.sh
```
环境变量（可选覆盖）
- KAFKA_BOOTSTRAP（默认 localhost:9092）
- REGISTRY_URL（默认 http://localhost:8081/apis/registry/v2）
- KAFKA_TOPIC（默认 pit.events_raw）
- CLICKHOUSE_URL（默认 jdbc:clickhouse://localhost:8123/default）
- KAFKA_DLQ（默认 pit.deadletter）
- GEOIP_MMDB（GeoLite mmdb 路径，可空）

验证
- ClickHouse：`SELECT count() FROM events;`、`SELECT count() FROM sessions;`
- DLQ：消费 `pit.deadletter` 查看无效/重复事件

说明
- JDBC Sink 已做批量与重试；如需端到端事务语义，后续可替换为两阶段提交 sink
- GeoIP 文件可选；未提供时不影响运行
