# Superset 连接 ClickHouse（本地）

1) 启动依赖
```bash
cd infra
docker compose up -d clickhouse superset
```

2) 登录 Superset
- 浏览器打开 http://localhost:8088 （首次需要初始化账号，参考官方文档）

3) 添加 ClickHouse 数据库连接
- Database -> + Database -> Connect this database
- SQLAlchemy URI 示例：
```
clickhouse+http://default:@host.docker.internal:8123/oddsmaker
```
或容器内网络：`clickhouse+http://default:@clickhouse:8123/oddsmaker`

4) 导入表
- 执行 `schema/sql/clickhouse/schema.sql`（已在容器 entrypoint 初始化）；若没自动创建可在 SQL Lab 中粘贴执行。

5) 创建数据集与图表
- 数据集：选择 `events`, `sessions` 表
- 示例图表：
  - 每日事件量：从 `mv_events_by_day` 拉取（countMerge(evts)）
  - 留存/漏斗：后续物化视图或 Flink 作业产生的聚合表

6) 权限与分享
- 根据项目划分数据库/schema，按 `project_id` 控制数据集过滤。
