# 入门

欢迎使用 Oddsmaker——面向单公司运营多款游戏的专业游戏分析平台。

## 概览

Oddsmaker 提供：

- **多游戏架构**：以隔离环境管理多款游戏
- **实时分析**：基于 Kafka + Flink + ClickHouse 的事件管道
- **风控**：检测并防范作弊与欺诈
- **A/B 测试**：运行带统计分析的实验
- **机器学习**：训练并部署用于预测的 ML 模型
- **企业级安全**：MFA、RBAC 与审计日志

## 前置条件

开始之前，请确认已具备：

- Java 21
- Gradle 9.8.1（仓库自带 Gradle Wrapper 9.8.1 —— 优先使用 `./gradlew`）
- Docker 与 Docker Compose
- PostgreSQL 16+
- Redis 7+
- Kafka（可选，用于实时处理）

## 快速开始

### 方式一：Docker Compose 全栈（推荐）

根目录的 `docker-compose.quickstart.yml` 会拉起 control + gateway（单一镜像、两个服务）以及全部依赖（PostgreSQL / Redis / Kafka / Apicurio / ClickHouse）。control 监听 38085，gateway 监听 38080：

```bash
# Clone the repository
git clone https://github.com/cuihairu/oddsmaker.git
cd oddsmaker

# Optional: adjust ports / passwords / tokens (see .env.example)
cp .env.example .env

# Start control + gateway + all dependencies
docker compose -f docker-compose.quickstart.yml up -d

# Verify services are running
docker compose -f docker-compose.quickstart.yml ps

# Check health
curl http://localhost:38085/actuator/health   # control (management plane)
curl http://localhost:38080/actuator/health   # gateway (ingest)
```

`infra/docker-compose.yml` 仅包含依赖（Kafka / ClickHouse / Apicurio / Superset / 可观测性组件）——不含 control 与 gateway。

### 方式二：本地开发

```bash
# Start dependencies
docker compose -f infra/docker-compose.yml up -d kafka clickhouse apicurio

# Build the project
gradle :services:control-service:bootJar

# Run the application
gradle :services:control-service:bootRun
```

## 第一步上手

### 1. 创建游戏

`genre` 可取值：ACTION, RPG, STRATEGY, PUZZLE, CASUAL, SIMULATION, SPORTS, RACING, SHOOTER, MMORPG, MOBA, BATTLE_ROYALE, OTHER。`platforms` 可取值：WEB, MOBILE, PC, CONSOLE, VR, AR。创建游戏时会自动初始化 `dev` / `staging` / `prod` 三个环境。

```bash
curl -X POST http://localhost:38085/api/games \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "name": "My Game",
    "genre": "RPG",
    "platforms": ["MOBILE", "PC"],
    "defaultTimezone": "UTC",
    "defaultCurrency": "USD"
  }'
```

### 2. 创建环境

```bash
curl -X POST http://localhost:38085/api/games/GAME_ID/environments \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "name": "production",
    "type": "PRODUCTION",
    "displayName": "Production Environment"
  }'
```

### 3. 创建 API Key

```bash
curl -X POST http://localhost:38085/api/api-keys \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "gameId": "GAME_ID",
    "environmentId": "ENV_ID",
    "name": "android-client",
    "keyRole": "client"
  }'
```

### 4. 发送事件

数据摄入走 **gateway**，而非 control：`/v1/batch`，端口 38080（NDJSON 或 JSON 数组）。`environment` 是游戏所配置的环境*名称*（默认为 `dev` / `staging` / `prod`），不是环境类型：

```bash
curl -X POST http://localhost:38080/v1/batch \
  -H "Content-Type: application/x-ndjson" \
  -H "x-api-key: YOUR_API_KEY" \
  -d '{"event_id": "evt_001", "game_id": "GAME_ID", "environment": "prod", "event_type": "session", "event_name": "session_start", "device_id": "device_123", "user_id": "user_456", "ts_client": 1700000000000}'
```

## 下一步

- [API Reference](/reference/) - 探索完整的 API
- [Operations](/operations/) - 部署与运维指南

## 获取帮助

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues) - 报告 bug
- [Discussions](https://github.com/cuihairu/oddsmaker/discussions) - 提问与交流
- [Documentation](/reference/) - 浏览文档
