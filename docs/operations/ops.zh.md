# 生产部署与运维建议（Oddsmaker）

目标：高可用、低延迟、可回放可重算、监控完善。以下建议按组件给出容量估算、关键参数、监控点与告警阈值。

总体容量估算（参考）
- 入口 QPS：10k events/s（单副本），生产建议多副本 + LB；垂直扩容按 CPU/网卡/GC 情况评估
- 日事件量：10k/s ≈ 864M/天；压缩存储（CH 列存 + TTL 分层）
- Kafka 分区数：按目标吞吐（50–100 MB/s/partition）与并发消费者计算（至少等于消费者并行度），示例：`events_raw` 48–96 分区

组件建议
- 网关（Spring Boot WebFlux）
  - CPU：每 4 核可支撑 5–15k/s（取决于 gzip、JSON、HMAC）；内存 2–4 GB
  - 关键参数：`reactor.netty.http.server.accessLogEnabled`、连接池、请求大小限制（已内置）
  - 反压策略：队列深度超过阈值返回 429；限速（每 Key、每 IP）按控制面策略动态
  - 监控：P95/P99 响应时延、2xx/4xx/5xx、429 率、入站字节、Kafka 生产失败率
- Kafka（KRaft 或 ZK）
  - 分区：`events_raw` ≥ 48；`events_enriched` 同步或更少；`deadletter` 低吞吐
  - 保留：`events_raw` 24–72h；DLQ 7–30 天；目标是可回放与排障
  - 生产者：acks=all、linger.ms=5–20、batch.size 64–256KB、压缩 `lz4`/`zstd`
  - 监控：ISR、UnderReplicated、请求延迟、Broker 磁盘、Topic Lag（按消费者组）
- Flink
  - Checkpoint：间隔 60–120s；超时 10–15 分钟；外部化保存（S3/HDFS）；`exactly-once` Source/Sink
  - State Backend：RocksDB（大状态 + 容错）；内存哈希（小状态）
  - 并行度：按 Kafka 分区数对齐；每并行 Subtask 对应 1–2 vCPU
  - 监控：Checkpoint 失败率/时长、BackPressure 比例、Busy 时间、消费 Lag；Job 重启次数
- ClickHouse
  - 表：MergeTree；分区（game_id, environment, 月）；OrderBy（game/environment, user/device, ts, name, id）
  - 资源：专用节点，内存 64–128 GB；快速 SSD；后台合并与压缩要监控
  - 写入：批量 1–10K 行；`max_insert_block_size`、`max_partitions_per_insert_block` 合理配置；控制作业对 CH 的并发
  - TTL：原始 30–90 天；聚合长保留；冷热分层（S3/HDFS）可选
  - 监控：Insert 失败/重试、Parts 数量、Merge 队列、查询延迟/错误、磁盘空间/IOPS
- Superset/Metabase
  - 只读连接；权限按游戏与环境隔离；缓存策略与慢查询超时配置
- Schema Registry（Apicurio）
  - 生产建议使用外部 DB 存储（PostgreSQL）而非内存；开启兼容性校验

可观测性与告警
- 指标来源：OpenTelemetry（网关/作业/控制面）→ OTel Collector → Prometheus（或远程存储）
- 核心告警（示例阈值）
  - 网关 `HTTP 5xx` > 1%（5m）
  - 网关 `429` > 5%（5m）（配合看 Key/IP；可能是限速/流量峰值）
  - Kafka `Consumer Lag` 持续上升（> 10k 消息/分区）
  - Flink `Checkpoint Failed` 连续 3 次；或 `Duration` > 2 x Interval
  - ClickHouse `Insert Error` > 0；`Merge backlog` 超阈值；`Disk Used` > 80%

回放与重算
- 回放：定位时间窗 → 从 `events_raw` 指定 offsets 重放到专用消费组 → 作业从 Checkpoint/Savepoint 启动
- 重算：将目标分区/时间窗写到临时表 → 验证一致性 → 原子切换视图或插入聚合表

备份与灾备
- Kafka：镜像/跨集群复制（MirrorMaker2）
- ClickHouse：定期快照（S3/备份磁盘），`BACKUP TABLE ... TO S3`；测试恢复流程
- 控制面与 Registry：持久化 DB 定期备份（RPO/RTO）

部署拓扑
- 最小可用：
  - 网关 x 2（LB 前），Kafka x 3（Brokers），Flink JobManager x 1 + TaskManager x N，ClickHouse x 2（副本），Registry x 2（+DB 主备）
- 生产建议：
  - 网关前置 WAF/限速；Kafka/CH 独立硬件或专用节点；Flink 使用 K8s/Nomad 管理资源与故障转移

参数示例（Flink）
- `execution.checkpointing.interval=120000`
- `execution.checkpointing.mode=EXACTLY_ONCE`
- `state.backend=rocksdb`
- `restart-strategy=fixed-delay`（attempts=3, delay=10s）

参数示例（Kafka Producer）
- `acks=all`、`enable.idempotence=true`、`linger.ms=5`、`batch.size=131072`、`compression.type=zstd`

参数示例（ClickHouse JDBC）
- 批量提交：1000–5000；重试 3；连接池 10–50；

故障排查速查（Runbook）
- 入口大量 429：看 Key/IP 流量；动态下调策略或临时豁免；确认下游（Kafka/作业）是否拥塞
- Kafka Lag 飙升：查看作业并行度/反压；检查 Broker 磁盘/网络；必要时扩分区并重分配
- Flink Checkpoint 超时：State 胀大或下游写入慢；扩资源/加并行/调大 checkpoint 超时；检查 CH 插入延迟
- CH 查询慢：补索引列（OrderBy），减少 props 解析；考虑物化更多聚合；控制 concurrency
