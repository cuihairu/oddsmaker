#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

echo "[1/5] Starting infra (Kafka, ClickHouse, Apicurio, Superset)"
(cd infra && docker compose up -d zookeeper kafka clickhouse apicurio superset)

echo "[2/5] Starting gateway-service"
GRADLE_BIN="./gradlew"
if [ ! -x "$GRADLE_BIN" ]; then
  if command -v gradle >/dev/null 2>&1; then GRADLE_BIN="gradle"; else echo "Gradle not found. Install Gradle or add gradlew wrapper."; exit 1; fi
fi
$GRADLE_BIN :services:gateway-service:bootRun &
GW_PID=$!
sleep 5

echo "[3/5] Sending sample events"
TS=$(date +%s000)
TS2=$(date +%s000)
curl -sS 'http://localhost:8080/v1/batch' \
  -H 'x-api-key: pk_test_example' \
  -H 'content-type: application/x-ndjson' \
  --data-binary @- <<EOF
{"event_id":"01JDEMO001","event_type":"progression","event_name":"level_start","game_id":"p1","environment":"dev","device_id":"d1","ts_client":$TS}
{"event_id":"01JDEMO002","event_type":"progression","event_name":"level_complete","game_id":"p1","environment":"dev","device_id":"d1","ts_client":$TS2}
EOF

sleep 2

echo "[4/5] Build Flink jobs (enrich, sessions)"
$GRADLE_BIN :jobs:flink:events-enrich-job:build :jobs:flink:sessions-job:build || true

echo "[5/5] Reminder: Run Flink jobs in another terminal:\n"
echo "flink run -c io.oddsmaker.jobs.enrich.EventsEnrichJob jobs/flink/events-enrich-job/build/libs/events-enrich-job.jar -Dkafka.bootstrap=localhost:9092 -Dregistry.url=http://localhost:8081/apis/registry/v2 -Dkafka.topic=oddsmaker.events_raw -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default"
echo "flink run -c io.oddsmaker.jobs.sessions.SessionsJob jobs/flink/sessions-job/build/libs/sessions-job.jar -Dkafka.bootstrap=localhost:9092 -Dregistry.url=http://localhost:8081/apis/registry/v2 -Dkafka.topic=oddsmaker.events_raw -Dclickhouse.url=jdbc:clickhouse://localhost:8123/default\n"
echo "Gateway PID: $GW_PID (CTRL-C to stop)"
wait $GW_PID || true
