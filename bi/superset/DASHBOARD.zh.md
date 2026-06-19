# Superset 仪表盘（Oddsmaker 快速搭建）

目标：基于 ClickHouse 的 `v_events_trend`、`v_dau_trend`、`retention_daily`、`funnels_2step` 快速创建 4 张图表并组合成仪表盘。

前置
- 已启动 `infra/docker-compose.yml` 中的 ClickHouse 与 Superset
- 已执行 `schema/sql/clickhouse/schema.sql`（容器首次自动执行或手动执行）

一、创建数据库连接（ClickHouse）
- Superset -> Data -> Databases -> + Database
- SQLAlchemy URI：
  - 本机直连：`clickhouse+http://default:@host.docker.internal:8123/default`
  - 容器互联：`clickhouse+http://default:@clickhouse:8123/default`
- Test connection -> Connect

二、注册数据集（Datasets）
- Data -> Datasets -> + Dataset
  1) 选择刚创建的 ClickHouse 数据库
  2) Physical dataset（物理表/视图）：依次选择
     - `v_events_trend`
     - `v_dau_trend`
     - `retention_daily`
     - `funnels_2step`
  3) 保存

三、创建图表（Charts）
1) 事件趋势（折线）
- Dataset: `v_events_trend`
- Viz: `Time-series Line`（echarts_timeseries_line）
- Time: `No filter`；Time column: `event_date`；Time grain: `P1D`
- Query: Metrics -> Adhoc Metric -> `SUM(events)`；Group by: `event_name`
- Run -> Save as `Events Trend`

2) DAU 趋势（折线）
- Dataset: `v_dau_trend`
- Viz: `Time-series Line`
- Time: `No filter`；Time column: `event_date`；Time grain: `P1D`
- Metrics: Adhoc Metric -> `SUM(dau)`
- Run -> Save as `DAU`

3) 留存（透视表）
- Dataset: `retention_daily`
- Viz: `Pivot Table v2`
- Rows: `cohort_date`；Columns: `d`；Metrics: Adhoc Metric -> `SUM(users)`
- Options: `sort by metric`（desc），`row totals` 勾选可选
- Run -> Save as `Retention`

4) 漏斗（分组柱状）
- Dataset: `funnels_2step`
- Viz: `Bar Chart`（echarts_timeseries_bar 或 bar）
- Query: Group by: `event_date`；Metrics: `SUM(started)`, `SUM(completed)`；Series: `Step`
- 或选择 `Pivot Table v2` 以 `event_date` 为行，`metric` 为列
- Save as `Funnels`

四、创建仪表盘（Dashboard）
- Dashboards -> + Dashboard -> 命名 `Oddsmaker Overview`
- Edit -> 从左侧添加上述四个图表，排版后保存

五、常见问题
- 无法连通 ClickHouse：检查容器网络与 URI；或改为 `http://127.0.0.1:8123`（Mac 需 host.docker.internal）
- 数据为空：确认 Flink 作业已启动并成功写入 `events/sessions`；视图 `v_events_trend/v_dau_trend` 是否可查询
- 时间维度：若 `event_date` 未被识别为时间列，可在 Dataset Columns 中编辑设为 `is temporal`

六、可选优化
- 在 `events` 上创建更多 MV（如 revenue_by_day、ua_family 分布），并在 Superset 中建立对应图表
- 按 `project_id` 建多个仪表盘，或在每个图表增加 `project_id` 过滤器
