# End-to-End Verification Scripts (E2E)

Run with one command
```bash
bash scripts/e2e.sh
```
Process
1) Start dependencies (Kafka/ClickHouse/Apicurio/OTel/Superset)
2) Start control-service and gateway-service
3) Create an API Key with gameId=`p1`, environment=`dev` via the control-plane API (the admin token defaults to admin; there is no "project" resource — gameId is just a string identifier)
4) Send two NDJSON events to /v1/batch using the new Key
5) If the Flink CLI is available locally, build and start the 4 Flink jobs (enrich/sessions/retention/funnels), wait 10s, then verify the `events` row count in ClickHouse

Superset dashboard import (optional)
```bash
bash scripts/superset-import.sh
# 浏览器访问 http://localhost:18088 （e2e 走 infra 栈，宿主端口 18088），登录 admin/admin，看到 Oddsmaker Overview 仪表盘
```

Dependencies
- Docker and docker compose (for the dependency stack)
- Gradle (or the bundled gradlew)
- Optional: Flink CLI (in PATH), used to verify writes to ClickHouse; otherwise CH verification is skipped

FAQ
- Port conflicts: the e2e path binds host ports 8080 (gateway) / 8085 (control) / 29192 (Kafka) / 29123 (ClickHouse) / 29081 (Apicurio) / 18088 (Superset) / 4317 + 4318 + 9464 (OTel) — 8123 and 9092 are container-internal ports and are not published, so check the host ports above
- Flink unavailable: the script will only verify gateway acceptance; for CH verification, install Flink and add it to PATH
- Control-plane token: defaults to admin; can be changed in services/control-service/application.yaml
