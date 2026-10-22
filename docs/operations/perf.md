# Performance and Load Testing Guide

> 🌐 Language: **English** · 中文 → [perf.md](perf.md)

Goal: verify the throughput, latency, and error rate of the gateway /v1/batch under different batch sizes and rates; evaluate the stability of downstream (Kafka/CH/Flink) under load testing.

Preparation
- Locally: use `infra/docker-compose.yml` to start Kafka/Apicurio/ClickHouse; `scripts/quickstart.sh` starts the gateway and injects a sample Key
- k6:
  - Can be run via Docker:
    ```bash
    docker run --rm -i grafana/k6 run - < scripts/loadtest/k6-oddsmaker.js \
      -e ENDPOINT=http://host.docker.internal:8080 \
      -e API_KEY=pk_test_example -e GAME_ID=p1 -e ENVIRONMENT=dev \
      -e RATE=200 -e DURATION=60s -e BATCH=50
    ```

Recommended scenarios
- Fixed arrival rate: `RATE=200`, `BATCH=50` (10k evts/s) → verify gateway P95/P99 and 429 rate. Prerequisite: first raise this Key's rpm/ipRpm in the control plane (e.g., 60000); otherwise, at the default rpm=600 (~10 rps), the arrival rate will trigger 429 for the entire run and real P95/P99 cannot be measured
- Stepped load: increase RATE in 30s steps (200→400→800) and observe the inflection point (CPU/GC/memory/429)
- gzip impact: k6 does not compress request bodies by default; test gzip bandwidth savings via a reverse proxy or a custom script (gzip is recommended for actual production)

Metric observation (Prometheus/Grafana)
- Gateway: HTTP latency/errors, 429 rate, JVM/heap, GC
- Kafka: Producer send rate and errors; Consumer Lag (when load testing jobs)
- Flink: Checkpoint success rate and duration, BackPressure, Busy time
- ClickHouse: Insert rate/errors, Parts/Merge backlog, query latency

Parameter recommendations (starting point)
- Batch: client-side 50–100; server-side batches into Kafka with linger.ms=5–20ms, batch.size 64–256KB
- Concurrency: a single replica can typically reach 10–20k evts/s; tune according to machine and network
- Rate limiting policy: limit by API Key and client IP dimensions (RateLimitFilter, default rpm=600/ipRpm=300); policies are pushed by the control plane to prevent malicious or abnormal traffic surges

Result recording
- During load tests, record RATE/BATCH, response P95/P99, 2xx/4xx/429 ratios, and CPU/memory usage
- When 429/5xx increases, lower RATE or add replicas; also check downstream Lag/Backpressure
