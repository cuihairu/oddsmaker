# Oddsmaker（游戏实时分析与风控平台）

Oddsmaker 面向一个游戏公司内部使用：一套平台管理多个游戏、多个环境，提供实时采集、游戏分析、A/B 实验和风控闭环。核心边界是 `game_id + environment`。

## 主链路

- SDK：Web / Android / iOS / Unity / Server
- 采集：Spring Boot Gateway，负责 API Key、Server HMAC、Schema、PII、限流和风控前置
- 消息：Kafka + Avro + Schema Registry
- 计算：Flink 富化、去重、会话、留存、漏斗、收入、风控
- 存储：ClickHouse 事件与聚合，PostgreSQL 元数据，Redis 实时计数和风控短窗状态
- 展示：Superset / Metabase / 自研实时与风控大屏

## 快速体验

```bash
bash scripts/e2e.sh
bash scripts/superset-import.sh
```

## 文档

- 架构：`docs/zh/reference/architecture.md`
- 重设计：`docs/zh/redesign/index.md`
- 采集 API：`docs/zh/reference/api.md`
- 控制面：`docs/zh/reference/control.md`
- 路线图：`docs/zh/analysis/roadmap.md`
- 运维：`docs/zh/operations/ops.md`
