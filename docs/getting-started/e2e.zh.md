# 端到端验证脚本（E2E）

一键执行
```bash
bash scripts/e2e.sh
```
流程
1) 启动依赖（Kafka/ClickHouse/Apicurio/OTel/Superset）
2) 启动 control-service 与 gateway-service
3) 通过控制面 API 创建项目 p1 与 API Key（admin token 默认 admin）
4) 使用新 Key 发送两条 NDJSON 事件到 /v1/batch
5) 若本机有 Flink CLI，则构建并启动 4 个 Flink 作业（enrich/sessions/retention/funnels），等待 10s 后到 ClickHouse 校验 `events` 行数

Superset 仪表盘导入（可选）
```bash
bash scripts/superset-import.sh
# 浏览器访问 http://localhost:8088 ，登录 admin/admin，看到 Pit Overview 仪表盘
```

依赖
- Docker 与 docker compose（用于依赖栈）
- Gradle（或项目内 gradlew）
- 可选：Flink CLI（在 PATH），用于校验写入 ClickHouse；否则跳过 CH 验证

常见问题
- 端口占用：检查 8080/8085/8123/9092 是否被其他进程占用
- Flink 不可用：脚本将只验证网关接受；如需 CH 校验，请安装 Flink 并加入 PATH
- 控制面令牌：默认 admin；可在 services/control-service/application.yaml 修改
