# 开发者指南（本地）

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

`pk_test_example` 是只存在于网关 *测试* 资源里的静态 key（`services/gateway-service/src/test/resources/application.yaml`），主配置不携带静态 key。因此由 `scripts/quickstart.sh` 起的源码运行态网关本地并无任何 key，上面这条 curl 只是尽力而为的冒烟样例（返回 401 属预期）。Docker Compose 栈则通过 `ODDSMAKER_AUTH_KEYS_DEVKEY` 定义了一个 dev key（key 为 `devkey`，默认密钥 `dev-secret`）；超出本地冒烟的场景请在控制面创建 key 后使用。

BI 仪表盘
- 构建导入包：`cd bi/superset && ./build_bundle.sh`
- Superset UI 导入 zip 包（见 bi/superset/IMPORT.zh.md）

常见问题
- gradlew 已随仓库提供，直接 `./gradlew` 即可（无需本机安装 Gradle）
- Flink CLI 不存在：请安装 Apache Flink 并将 `flink` 加入 PATH
- ClickHouse 连接失败：在 Linux 环境改用容器名 `clickhouse` 而不是 `host.docker.internal`
