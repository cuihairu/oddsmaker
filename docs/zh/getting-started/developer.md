# 开发者指南（本地）

> 🌐 Language: **中文** · English → [developer.en.md](../../getting-started/developer)

一键演示
```bash
bash scripts/quickstart.sh
# 另开终端启动 Flink 作业
bash scripts/run_flink.sh
```

组件启动
- 依赖（Kafka/ClickHouse/Apicurio/Superset）：`cd infra && docker compose up -d`
- 控制面：`./gradle[w] :services:control-service:bootRun`
- 网关：`./gradle[w] :services:gateway-service:bootRun`

发送样例事件
```bash
curl -sS 'http://localhost:8080/v1/batch' \
  -H 'x-api-key: pk_test_example' -H 'content-type: application/x-ndjson' \
  --data-binary $'{"event_id":"01J..1","event_name":"level_start","game_id":"game_demo","environment":"production","device_id":"d1","ts_client":'$(date +%s000)'}\n'{"event_id":"01J..2","event_name":"level_complete","game_id":"game_demo","environment":"production","device_id":"d1","ts_client":'$(date +%s000)'}'
```

BI 仪表盘
- 构建导入包：`cd bi/superset && ./build_bundle.sh`
- Superset UI 导入 zip 包（见 bi/superset/IMPORT.zh.md）

常见问题
- gradlew 已随仓库提供，直接 `./gradlew` 即可（无需本机安装 Gradle）
- Flink CLI 不存在：请安装 Apache Flink 并将 `flink` 加入 PATH
- ClickHouse 连接失败：在 Linux 环境改用容器名 `clickhouse` 而不是 `host.docker.internal`
