#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

if ! command -v flink >/dev/null 2>&1; then
  echo "Apache Flink CLI 'flink' not found. Install Flink or add it to PATH."
  exit 1
fi

GRADLE_BIN="./gradlew"; if [ ! -x "$GRADLE_BIN" ]; then GRADLE_BIN="gradle"; fi

BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:9092}
REGISTRY=${REGISTRY_URL:-http://localhost:8081/apis/registry/v2}
TOPIC=${KAFKA_TOPIC:-oddsmaker.events_raw}
CH_URL=${CLICKHOUSE_URL:-jdbc:clickhouse://localhost:8123/default}
DLQ=${KAFKA_DLQ:-oddsmaker.deadletter}
GEOIP=${GEOIP_MMDB:-}

echo "Building Flink jobs..."
$GRADLE_BIN :jobs:flink:events-enrich-job:build :jobs:flink:sessions-job:build :jobs:flink:retention-job:build :jobs:flink:funnels-job:build

JAR_ENR=jobs/flink/events-enrich-job/build/libs/events-enrich-job.jar
JAR_SES=jobs/flink/sessions-job/build/libs/sessions-job.jar
JAR_RET=jobs/flink/retention-job/build/libs/retention-job.jar
JAR_FUN=jobs/flink/funnels-job/build/libs/funnels-job.jar

run_job() {
  local MAIN=$1; shift
  local JAR=$1; shift
  echo "Starting $MAIN"
  flink run -c "$MAIN" "$JAR" \
    -Dkafka.bootstrap="$BOOTSTRAP" -Dregistry.url="$REGISTRY" -Dkafka.topic="$TOPIC" \
    -Dclickhouse.url="$CH_URL" -Dkafka.dlq="$DLQ" -Dgeoip.mmdb="$GEOIP" "$@" &
}

run_job io.oddsmaker.jobs.enrich.EventsEnrichJob "$JAR_ENR"
run_job io.oddsmaker.jobs.sessions.SessionsJob "$JAR_SES"
run_job io.oddsmaker.jobs.retention.RetentionJob "$JAR_RET"
run_job io.oddsmaker.jobs.funnels.FunnelsJob "$JAR_FUN"

echo "All jobs started in background. Use 'jobs' or 'ps' to view, 'kill' to stop."
