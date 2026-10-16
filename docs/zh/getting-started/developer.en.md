# Developer Guide (Local)

> 🌐 Language: **English** · 中文 → [developer.md](developer.md)

One-command demo
```bash
bash scripts/quickstart.sh
# 另开终端启动 Flink 作业
bash scripts/run_flink.sh
```

Starting components
- Dependencies (Kafka/ClickHouse/Apicurio/Superset): `cd infra && docker compose up -d`
- Control plane: `./gradle[w] :services:control-service:bootRun`
- Gateway: `./gradle[w] :services:gateway-service:bootRun`

Send sample events
```bash
curl -sS 'http://localhost:8080/v1/batch' \
  -H 'x-api-key: pk_test_example' -H 'content-type: application/x-ndjson' \
  --data-binary $'{"event_id":"01J..1","event_name":"level_start","game_id":"game_demo","environment":"production","device_id":"d1","ts_client":'$(date +%s000)'}\n'{"event_id":"01J..2","event_name":"level_complete","game_id":"game_demo","environment":"production","device_id":"d1","ts_client":'$(date +%s000)'}'
```

BI dashboards
- Build the import bundle: `cd bi/superset && ./build_bundle.sh`
- Import the zip bundle in the Superset UI (see bi/superset/IMPORT.zh.md)

FAQ
- gradlew is bundled with the repo; just use `./gradlew` (no need to install Gradle locally)
- Flink CLI is not present: install Apache Flink and add `flink` to PATH
- ClickHouse connection failure: on Linux, use the container name `clickhouse` instead of `host.docker.internal`
