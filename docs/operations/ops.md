# Production Deployment and Operations Recommendations (Oddsmaker)

Goal: high availability, low latency, replayable and recomputable, with comprehensive monitoring. The recommendations below provide capacity estimates, key parameters, monitoring points, and alert thresholds per component.

Overall capacity estimates (reference)
- Ingress QPS: 10k events/s (single replica); production recommends multiple replicas + LB; scale vertically based on CPU/network card/GC conditions
- Daily event volume: 10k/s ≈ 864M/day; compressed storage (CH columnar storage + TTL tiers)
- Kafka partition count: calculated from target throughput (50–100 MB/s/partition) and concurrent consumers (at least equal to consumer parallelism); example: `events_raw` 48–96 partitions

Component recommendations
- Gateway (Spring Boot WebFlux)
  - CPU: 4 cores can support 5–15k/s (depending on gzip, JSON, HMAC); memory 2–4 GB
  - Key parameters: connection pool, request size limits (built in); for access logs, append `--reactor.netty.http.server.accessLogEnabled=true` to the startup arguments (not configured by default in this repo)
  - 429s are triggered only by rpm/ipRpm rate limiting (RateLimitFilter, by API Key and client IP); the gateway has no internal queue backpressure; rate limits (per Key, per IP) follow control-plane policies dynamically
  - Monitoring: P95/P99 response latency, 2xx/4xx/5xx, 429 rate, inbound bytes, Kafka producer failure rate
- Kafka (KRaft or ZK)
  - Partitions: `oddsmaker.events_raw` ≥ 48; `oddsmaker.risk_events`/`oddsmaker.identity_events` low throughput; `oddsmaker.deadletter` low throughput (this repo actually creates the four topics above, see the root docker-compose.yml; `events_enriched` is not implemented and only appears in the SessionsJob comments)
  - Retention: `events_raw` 24–72h; DLQ 7–30 days; the goal is replayability and troubleshooting
  - Producer: acks=all, linger.ms=5–20, batch.size 64–256KB, compression `lz4`/`zstd`
  - Monitoring: ISR, UnderReplicated, request latency, Broker disk, Topic Lag (by consumer group)
- Flink
  - Checkpoint: interval 60–120s; timeout 10–15 minutes; externalized storage (S3/HDFS); `exactly-once` Source/Sink
  - State Backend: RocksDB (large state + fault tolerance); in-memory hash (small state)
  - Parallelism: aligned with the Kafka partition count; each parallel Subtask corresponds to 1–2 vCPU
  - Monitoring: Checkpoint failure rate/duration, BackPressure ratio, Busy time, consumer Lag; Job restart count
- ClickHouse
  - Table: MergeTree; partitioning (game_id, environment, month); OrderBy (game/environment, user/device, ts, name, id)
  - Resources: dedicated nodes, memory 64–128 GB; fast SSD; background merges and compaction must be monitored
  - Writes: batches of 1–10K rows; configure `max_insert_block_size` and `max_partitions_per_insert_block` reasonably; control job concurrency to CH
  - TTL: the raw events table currently has a TTL of 365 days; for shorter retention, change the schema or use the control-plane retention policy; aggregates are retained long-term; hot/cold tiering (S3/HDFS) is optional
  - Monitoring: Insert failures/retries, Parts count, Merge queue, query latency/errors, disk space/IOPS
- Superset/Metabase
  - Read-only connections; permissions isolated by game and environment; configure cache policies and slow-query timeouts
- Schema Registry (Apicurio)
  - For production, use an external DB (PostgreSQL) instead of in-memory storage; enable compatibility validation

Observability and alerts
- Metric sources: OpenTelemetry (gateway/jobs/control plane) → OTel Collector → Prometheus (or remote storage)
- Core alerts (the following are example thresholds; the repo's alert rules are in deploy/monitoring/alerts/oddsmaker-alerts.yml, currently covering five groups: control-service/database/cache/messaging/stream-processing)
  - Control plane `HTTP 5xx` ratio > 5% (5m window, lasting 2m) — this is the repo's `HighErrorRate` rule, covering only control-service (the gateway has no equivalent alert)
  - Gateway `429` > 5% (5m) (cross-check Key/IP; may be rate limiting/traffic spike) [example, not configured in the repo]
  - Kafka `Consumer Lag` continuously rising (> 10k messages/partition) [example; the repo uses `KafkaHighConsumerLag` (lag > 10000, lasting 5m)]
  - Flink `Checkpoint Failed` 3 consecutive times; or `Duration` > 2 x Interval [example, not configured in the repo]
  - ClickHouse `Insert Error` > 0; `Merge backlog` over threshold; `Disk Used` > 80% [example, not configured in the repo]

Replay and recomputation
- Replay: locate the time window → replay from specified offsets in `events_raw` to a dedicated consumer group → start the job from Checkpoint/Savepoint
- Recomputation: write the target partition/time window to a temp table → verify consistency → atomically switch views or insert into aggregate tables

Backup and disaster recovery
- Kafka: mirroring/cross-cluster replication (MirrorMaker2)
- ClickHouse: regular snapshots (S3/backup disk), `BACKUP TABLE ... TO S3`; test the recovery process
- Control plane and Registry: periodic backups of the persistent DB (RPO/RTO)

Deployment topology
- Minimum viable:
  - Gateway x 2 (in front of the LB), Kafka x 3 (Brokers), Flink JobManager x 1 + TaskManager x N, ClickHouse x 2 (replicas), Registry x 2 (+DB primary/standby)
- Production recommendations:
  - Put WAF/rate limiting in front of the gateway; Kafka/CH on dedicated hardware or dedicated nodes; use K8s/Nomad for Flink resource management and failover

Parameter examples (Flink)
- `execution.checkpointing.interval=30000`, `execution.checkpointing.mode=AT_LEAST_ONCE` (currently enabled in the events-enrich job code; 120s + EXACTLY_ONCE is a production TODO)
- `state.backend`: not configured (currently defaults to hashmap state backend); production use requires adding the dependency and enabling it with `-Dstate.backend=rocksdb`
- `restart-strategy=fixed-delay` (attempts=3, delay=10s)

Parameter examples (Kafka Producer)
- `acks=all`, `linger.ms=5`, `batch.size=65536` (currently set by the gateway's AvroPublisher); `enable.idempotence` and `compression.type` are not enabled — consider enabling them for production

Parameter examples (ClickHouse JDBC)
- Batch commits: 1000–2000; 3 retries (Flink JDBC Sink, no connection pool configuration);

Troubleshooting quick reference (Runbook)
- Many 429s at the ingress: check Key/IP traffic; dynamically lower policies or temporarily exempt; confirm whether downstream (Kafka/jobs) is congested
- Kafka Lag spike: check job parallelism/backpressure; check Broker disk/network; expand partitions and rebalance if necessary
- Flink Checkpoint timeout: State bloat or slow downstream writes; add resources/increase parallelism/raise the checkpoint timeout; check CH insert latency
- Slow CH queries: add index columns (OrderBy), reduce props parsing; consider materializing more aggregates; control concurrency
