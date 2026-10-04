# Oddsmaker（游戏实时分析与风控平台）

Oddsmaker 面向一个游戏公司内部使用：一套平台管理多个游戏、多个环境，提供实时采集、游戏分析、A/B 实验和风控处置链路。核心边界是 `game_id + environment`。

## 主链路

底座是开源组件（Spring Boot、Kafka、Flink、ClickHouse、PostgreSQL、Redis、Superset），本仓自写接线、业务层与 SDK。

- SDK：Web / Android / iOS / Unity / Server
- 采集：Spring Boot Gateway，负责 API Key、Server HMAC、Schema、PII、限流和风控前置
- 消息：Kafka + Avro + Schema Registry
- 计算：Flink 富化、去重、会话、留存、漏斗、收入、风控
- 存储：ClickHouse 事件与聚合，PostgreSQL 元数据，Redis 实时计数和风控短窗状态
- 展示：Superset / Metabase / 自行开发实时与风控大屏

## 快速体验

```bash
bash scripts/e2e.sh
bash scripts/superset-import.sh
```

## 快速启动 Docker

使用 Docker Compose 快速启动所有服务（服务端单镜像 + PostgreSQL / Redis / Kafka / Apicurio / ClickHouse 依赖，端口 / 卷 / 健康检查齐全）：

```bash
# 复制环境配置并按需修改（端口 / 密码 / Token 全部有中文注释）
cp .env.example .env

# 启动所有服务
docker compose -f docker-compose.quickstart.yml up -d

# 检查服务状态（全部 healthy 即就绪）
docker compose -f docker-compose.quickstart.yml ps

# 验证 API
curl http://localhost:38085/actuator/health   # control（管理面）
curl http://localhost:38080/actuator/health   # gateway（采集入口）
```

或者使用单服务 Docker 启动（单镜像双服务，`SERVICE` 环境变量选择 `control` / `gateway`）：

```bash
# 启动控制服务
docker run -d --name oddsmaker-control \
  -p 38085:8085 \
  -e SERVICE=control \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://<pg>:5432/oddsmaker \
  -e ODDSMAKER_ADMIN_TOKEN=dev-admin-token \
  ghcr.io/cuihairu/oddsmaker:nightly

# 启动网关服务
docker run -d --name oddsmaker-gateway \
  -p 38080:8080 \
  -e SERVICE=gateway \
  -e ODDSMAKER_KAFKA_BOOTSTRAP=<kafka>:9092 \
  -e ODDSMAKER_CONTROL_URL=http://<control>:8085 \
  -e ODDSMAKER_CONTROL_INTERNAL_TOKEN=dev-internal-token \
  ghcr.io/cuihairu/oddsmaker:nightly
```

## Docker 镜像与 Nightly 构建

- 镜像 `ghcr.io/cuihairu/oddsmaker`：根目录 `Dockerfile` 多阶段构建（Gradle 编译 → `eclipse-temurin:21-jre-alpine` 运行）、非 root 运行、内置 healthcheck；本地构建：`docker build -t ghcr.io/cuihairu/oddsmaker:local .`（构建上下文必须是仓库根）。
- 镜像 tag 口径：CI 每日（UTC 00:00）及每次 main 推送推 `:nightly`，发版时推 `:<version>` 与 `:latest`。
- Nightly 分发包：全量测试通过后在 [Releases](https://github.com/cuihairu/oddsmaker/releases) 页滚动发布（tag 固定 `nightly`），覆盖 Linux x64/arm64、macOS x64/arm64、Windows x64；包内含 `BUILD_INFO`，Release 附 `VERIFY.md`（全资产 SHA256）。包内容：control + gateway（bootJar）、6 个 Flink 作业 fatJar、维度同步 agent、Web 控制台 / SDK 静态资源、启动脚本与配置模板。

## 文档

- 架构：`docs/zh/reference/architecture.md`
- 重设计：`docs/zh/redesign/index.md`
- 采集 API：`docs/zh/reference/api.md`
- 控制面：`docs/zh/reference/control.md`
- 路线图：`docs/zh/analysis/roadmap.md`
- 运维：`docs/zh/operations/ops.md`
