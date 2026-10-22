# Gateway Performance Tuning Recommendations (Oddsmaker Gateway)

> 🌐 Language: **English** · 中文 → [perf-tuning.md](perf-tuning.md)

Goal: maintain stable low latency and low error rates under high throughput (10k–50k evts/s). The recommendations below cover the JVM, Reactor Netty, Kafka Producer, OS, and deployment layers.

JVM/GC
- JDK 21 recommended, default G1GC (or try ZGC on high-memory machines to reduce pauses)
- In containers use: `-XX:MaxRAMPercentage=75.0` (already set in the Dockerfile)
- Observe GC: import a JVM/GC Dashboard in Prometheus/Grafana; P95 GC Pause < 50ms is desirable

Reactor Netty (server side)
- EventLoop thread count: default ≈ CPU cores x 2; generally no modification needed
- Response compression: `server.compression.enabled=true` is already enabled (beneficial for large error/diagnostic responses)
- Large requests: limit `oddsmaker.request.maxBytes` (default 1MB) to avoid latency spikes from oversized single requests
- JSON parsing: prefer NDJSON; client-side batches of 50–100 reduce system calls and serialization overhead

Kafka Producer (Avro)
- Tuning:
  - `oddsmaker.kafka.producer.lingerMs`: 5–20ms (larger batches, higher throughput, but higher latency)
  - `oddsmaker.kafka.producer.batchSize`: 64–256KB (depending on event size)
  - Other: `acks=all` (already set), `compression=zstd` can be considered when the Broker allows it (must be enabled on the Producer as well)
- Partitions: ensure the `events_raw` partition count aligns with consumer parallelism, otherwise hotspots will form downstream

OS and deployment
- Network card: gigabit or above; set container cgroup limits reasonably
- HPA: scale by CPU or custom metrics (QPS/429 rate); values.yaml has examples
- Affinity: spread across availability zones/nodes; topology spread constraints (values.yaml)

Load testing practice
- The fixed arrival rate model (k6 constant-arrival-rate) is closer to real traffic
- Combination dimensions: RATE (200/400/800 it/s) x BATCH (20/50/100)
- Observe P95/P99, 429 rate, Kafka send rate/errors, Flink Lag (if downstream is linked)
- Collect and generate CSV reports via `scripts/perf-matrix.sh` and `scripts/perf-report.py` (k6 runs in a container, localhost points to the container itself: `ENDPOINT=http://host.docker.internal:8080 bash scripts/perf-matrix.sh`)

ClickHouse query side (recommended)
- Import the base schema: `schema/sql/clickhouse/schema.sql`
- Import experiment MVs: `schema/sql/clickhouse/schema_experiments_mv.sql`
- Import views: `schema/sql/clickhouse/queries_experiment.sql`
- Use `clickhouse-benchmark` to evaluate `v_exp_conversion_24h(_dim)`/`v_exp_conversion_7d(_dim)` and the corresponding MV-based Join queries; depending on data volume, decide whether to switch to the Join based on `exp_exposure_users` and `exp_first_level_complete`

Daily pre-aggregation (recommended)
- Import `schema/sql/clickhouse/schema_experiments_daily_mv.sql` to pre-aggregate exposures and 24h/7d conversions by day/experiment/variant, significantly reducing large-range Join costs.
- Prefer fast views: in Superset, use the `*_fast` or daily-table-backed datasets/charts (MV-backed) to support high-volume dashboards.

Query selection guide (raw vs fast vs daily vs daily-dim)
- Raw views (e.g., `v_exp_exposures_by_day` and other raw Joins)
  - Use cases: temporary debugging, small time windows (< 3 days), quickly validating fields and definitions when data volume is modest.
  - Shortcomings: high Join cost over long time windows or high-cardinality dimensions, prone to multi-second latency.
- Fast views (`*_fast` based on pre-aggregated MVs)
  - Use cases: default data source for dashboards; overall/dimensionless trend and conversion rate queries over medium-to-long time windows (7–90 days).
  - Advantages: only scans pre-aggregated tables, more stable queries; can handle large data volumes.
- Daily (daily MV tables, e.g., `exp_daily_exposures`/`exp_daily_conv_24h`/`exp_daily_conv_7d`)
  - Use cases: analyses that need day-granularity trends and period-over-period comparison (without dimension drill-down).
  - Usage example:
    ```sql
    -- 近 30 天曝光与 24h 转化率（无维度）
    SELECT exposure_date, variant, exposed_users, converted_users, cr_24h
    FROM exp_daily_conv_24h
    WHERE exposure_date >= today()-30 AND game_id='game_demo' AND environment='production' AND exp='exp_foo'
    ORDER BY exposure_date;
    ```
- Daily-dim (daily dimension MV tables, e.g., `exp_daily_exposures_dim`/`exp_daily_conv_24h_dim`/`exp_daily_conv_7d_dim`)
  - Use cases: trends and comparisons that need grouping and Top-N drill-down by platform/country/version.
  - Usage example:
    ```sql
    -- 近 14 天按国家 Top 10 的 24h 转化率
    SELECT exposure_date, country, exposed_users, converted_users, cr_24h
    FROM exp_daily_conv_24h_dim
    WHERE exposure_date >= today()-14 AND game_id='game_demo' AND environment='production' AND exp='exp_foo'
      AND country IN (
        SELECT country FROM (
          SELECT country, sum(exposures) AS exps
          FROM exp_daily_exposures_dim
          WHERE event_date >= today()-14 AND game_id='game_demo' AND environment='production' AND exp='exp_foo'
          GROUP BY country ORDER BY exps DESC LIMIT 10
        )
      )
    ORDER BY country, exposure_date;
    ```
Practical recommendations
- For dashboards, prefer `*_fast` or daily/daily-dim; fall back to raw only when necessary.
- For high-concurrency interactive filtering, consider limiting the time window (e.g., last 30/90 days) and dimension Top-N to avoid scanning too many partitions.

Partitioning and TTL recommendations (ClickHouse)
- Partitioning:
  - The raw events table is partitioned by `(game_id, environment, toYYYYMM(event_date))`, and the exposure users table by `(game_id, environment, toYYYYMM(expose_ts))`;
  - Daily tables are partitioned by `(game_id, environment, toYYYYMM(day))` (e.g., `event_date` / `exposure_date`).
- TTL: can be set to 400 days or as business needs dictate, e.g.:
  - `TTL event_date + INTERVAL 400 DAY DELETE` (exposure daily table)
  - `TTL exposure_date + INTERVAL 400 DAY DELETE` (conversion daily table)
- Engine selection:
  - Exposure daily tables use `SummingMergeTree` (for easy summation);
  - Conversion daily tables use `ReplacingMergeTree` or `SummingMergeTree` (prefer Summing for idempotent writes; otherwise use Replacing).
