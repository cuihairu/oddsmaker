# Release 指南（30 分钟拉起演示 + 生产清单）

快速演示（≈10–30 分钟）
1) 依赖
- Docker + docker compose
- Java 21 + Gradle（或使用项目内 gradlew）
- 可选：Flink CLI（用于校验写入 ClickHouse）
2) 一键脚本
- 端到端：
```bash
bash scripts/e2e.sh
```
- 导入 Superset 仪表盘（可选）：
```bash
bash scripts/superset-import.sh
```
3) 查看
- 网关健康：http://localhost:8080/actuator/health
- 控制面 UI：http://localhost:8085/（x-admin-token: admin）
- ClickHouse：http://localhost:8123（直接执行 SQL）
- Superset：http://localhost:8088（admin/admin）
- Grafana：http://localhost:3000（admin/admin）

压测（可选）
```bash
bash scripts/perf-matrix.sh   # 运行多组 RATE/BATCH
python3 scripts/perf-report.py var/perf > var/perf/report.csv
```

生产部署（K8s + Helm）
1) 构建并推送镜像
```bash
# Gateway
cd services/gateway-service && docker build -t <registry>/pit-gateway:<tag> . && docker push <...>
# Control
cd ../control-service && docker build -t <registry>/pit-control:<tag> . && docker push <...>
```
2) 使用 Helm 安装
```bash
helm upgrade --install pit ./infra/helm/pit -n pit --create-namespace \
  --set image.gateway=<registry>/pit-gateway:<tag> \
  --set image.control=<registry>/pit-control:<tag> \
  --set env.kafkaBootstrap=<kafka:9092> \
  --set env.registryUrl=<apicurio-url> \
  --set env.clickhouseUrl=<jdbc:clickhouse://...> \
  --set env.adminToken=<STRONG_TOKEN>
```
3) 可选：开启 Ingress、HPA、探针与调度等（见 values.yaml 注释）

生产参数清单（Checklist）
- 安全
  - 控制面管理令牌（env.adminToken）改为强随机或对接 OIDC；开启 TLS/Ingress WAF
  - 网关 HMAC 仅用于服务端；客户端 SDK 不应持有 Secret
- 数据治理
  - PII 策略（email/phone/ip、denyKeys/maskKeys）按业务设定并在控制面配置
  - Props allowlist 按事件设计约束（避免 schema 爆炸）
- 容量
  - Kafka：`events_raw` 分区数与 Flink 并行度对齐（示例 ≥ 48）；保留期 24–72h
  - Flink：Checkpoint 外部化（S3/HDFS），EXACTLY_ONCE；StateBackend RocksDB（大状态）
  - ClickHouse：MergeTree 分区（月）；TTL（原始 30–90 天）；聚合长保留；S3 冷存可选
- 监控与告警
  - OTel → Prom/Grafana；
  - 告警：网关 5xx/429、Kafka Lag、Flink CK 失败、CH Merge 积压/磁盘
- 调参
  - Producer：linger.ms 5–20ms、batch.size 64–256KB
  - 客户端批量：50–100；网关限流：rpm/ipRpm 按项目配置

参考文档
- [ops.zh.md](ops.zh.md)（运维与容量）
- [deploy.k8s.zh.md](deploy.k8s.zh.md)（K8s 部署）
- [perf-tuning.zh.md](perf-tuning.zh.md)（性能调优）
- [../getting-started/e2e.zh.md](../getting-started/e2e.zh.md)（端到端脚本）

GitHub Release
- 打 tag（自动触发发布）：
```bash
bash scripts/release_tag.sh 0.1.0
```
- GitHub Actions 会自动创建 Release，正文取 `docs/release-notes/<tag>.md`（若存在）或 `CHANGELOG.md`，并附带 Superset 仪表盘包 zip 为附件。
