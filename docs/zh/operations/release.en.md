# Release Guide (30-Minute Demo Spin-Up + Production Checklist)

> 🌐 Language: **English** · 中文 → [release.md](release.md)

Quick demo (≈10–30 minutes)
1) Dependencies
- Docker + docker compose
- Java 21 + Gradle (or use the bundled gradlew)
- Optional: Flink CLI (used to verify writes to ClickHouse)
2) One-command scripts
- End-to-end:
```bash
bash scripts/e2e.sh
```
- Monitoring dashboards (optional; `e2e.sh` only starts otel-collector, not these two):
```bash
cd infra && docker compose up -d prometheus grafana
```
- Import Superset dashboards (optional):
```bash
bash scripts/superset-import.sh
```
3) View
- Gateway health: `http://localhost:8080/actuator/health`
- Control plane UI: `http://localhost:8085/` (`x-admin-token: admin`)
- ClickHouse: `http://localhost:29123` (execute SQL directly; the infra stack maps host port 29123:8123 — the root compose uses 28123, the deploy-rt e2e stack uses 18123; distinguish by the stack in use)
- Superset: `http://localhost:18088` (`admin/admin`; infra stack 18088:8088)
- Grafana: `http://localhost:13000` (`admin/admin`; infra stack 13000:3000)

Load testing (optional)
```bash
bash scripts/perf-matrix.sh   # 运行多组 RATE/BATCH
python3 scripts/perf-report.py var/perf > var/perf/report.csv
```

Production deployment (K8s + Helm)
1) Build and push images
```bash
# 单镜像双服务（CI 路径，SERVICE=control|gateway 选服务）
docker build -t <registry>/oddsmaker:<tag> . && docker push <registry>/oddsmaker:<tag>
# 或按服务自建（Helm 路径需逐服务镜像，deploy/k8s/ 清单可走单镜像）
cd services/gateway-service && docker build -t <registry>/oddsmaker-gateway:<tag> . && docker push <...>
cd ../control-service && docker build -t <registry>/oddsmaker-control:<tag> . && docker push <...>
```
2) Install with Helm
```bash
helm upgrade --install oddsmaker ./infra/helm/oddsmaker -n oddsmaker --create-namespace \
  --set image.gateway=<registry>/oddsmaker-gateway:<tag> \
  --set image.control=<registry>/oddsmaker-control:<tag> \
  --set env.kafkaBootstrap=<kafka:9092> \
  --set env.registryUrl=<apicurio-url> \
  --set env.clickhouseUrl=<jdbc:clickhouse://...> \
  --set env.adminToken=<STRONG_TOKEN>
```
3) Optional: enable Ingress, HPA, probes, and scheduling, etc. (see the comments in values.yaml)

Production parameter checklist
- Security
  - Change the control-plane admin token (env.adminToken) to a strong random value or integrate OIDC; enable TLS/Ingress WAF
  - Gateway HMAC is for server-side use only; client SDKs must not hold the Secret
- Data governance
  - Configure the PII policy (email/phone/ip, denyKeys/maskKeys) per business needs in the control plane
  - Constrain the props allowlist per the event design (to avoid schema explosion)
- Capacity
  - Kafka: align the `events_raw` partition count with Flink parallelism (example ≥ 48); retention 24–72h
  - Flink: externalized checkpoints (S3/HDFS), EXACTLY_ONCE; StateBackend RocksDB (for large state)
  - ClickHouse: MergeTree partitioning (monthly); TTL (raw 30–90 days); long retention for aggregates; S3 cold storage optional
- Monitoring and alerts
  - OTel → Prom/Grafana;
  - Alerts: gateway 5xx/429, Kafka Lag, Flink CK failures, CH Merge backlog/disk
- Tuning
  - Producer: linger.ms 5–20ms, batch.size 64–256KB
  - Client-side batches: 50–100; gateway rate limiting: rpm/ipRpm configured per project

Reference docs
- [ops.md](./ops) (operations and capacity)
- [deploy.k8s.md](./deploy.k8s) (K8s deployment)
- [perf-tuning.md](./perf-tuning) (performance tuning)
- [../getting-started/e2e.md](../getting-started/e2e) (end-to-end scripts)

GitHub Release
- Tag a release (triggers the release automatically):
```bash
bash scripts/release_tag.sh 0.1.0
```
- GitHub Actions automatically creates the Release, taking the body from `docs/release-notes/<tag>.md` (if it exists) or `CHANGELOG.md`, and attaches the Superset dashboard bundle zip as an attachment.
