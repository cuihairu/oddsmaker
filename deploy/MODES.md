# 运行模式矩阵（Lite / Standard / Production）

对齐仓库现有编排的三档声明（B9）。**本文是读码核对的现状矩阵**：每格都对应编排文件里
实际存在的 service 定义（核对时点见文末），没有的组件就是没有——不是规划。

## 总览

| 模式 | 编排文件 | 定位 | 资源画像 |
|---|---|---|---|
| **Lite** | `deploy/demo/docker-compose.yml` | 演示站点：控制面 + Web 控制台，无采集/无分析 | 实测 2 vCPU / 1.6 GB / 40 GB（JVM 堆收窄 256–512m，`vm.swappiness=60` 必调） |
| **Standard** | `docker-compose.quickstart.yml` | 单机默认档：采集 + 控制面 + 分析库 + 看板底座 | 单机可跑（无 Flink 作业；分析产出需按 Production 形态补作业） |
| **Production** | 仓库根 `docker-compose.yml` + `infra/docker-compose.yml` 两件套 | 全组件：业务栈 + Flink 全作业 + 观测面 | 1.6 GB 不适用（Flink 作业与观测面均需独立内存预算） |

**Production 为什么是两件套**：仓库没有一个包含全部组件的编排文件。根 `docker-compose.yml`
是「本机单机全栈」（业务服务 + 7 个 Flink job + Superset，端口 2xxxx 业务段），
`infra/docker-compose.yml` 是「中间件 + 可观测性」互补子集（Kafka/CH/Apicurio/Superset +
otel-collector/Prometheus/Grafana，端口 29xxx/18088/13000 段），两者注释均声明可同机并存、
端口互不冲突。两件合用 = Production 全组件。

## 组件矩阵

| 组件 | Lite（demo） | Standard（quickstart） | Production（根 + infra） |
|---|---|---|---|
| PostgreSQL | ✅（300m 上限） | ✅（宿主 35432） | ✅ 根（25432）；infra 不含 |
| Redis | ✅（100m 上限） | ✅（36379） | ✅ 根（26379）；infra 不含 |
| Kafka + topic-init | ❌ | ✅ KRaft 单节点 3.7.0（39092）+ 四 topic 一次性建齐 | ✅ 根（29092）+ topic-init；infra 亦可独立起（29192） |
| ClickHouse 24.8 | ❌ | ✅（38123，initdb 六份 schema SQL） | ✅ 根（28123）；infra（29123，三件套） |
| Apicurio Schema Registry | ❌ | ✅ 2.6.5（38081） | ✅ 根（28081）；infra（29081） |
| Gateway（采集入口） | ❌ | ✅（38080） | ✅ 根（28080） |
| Control Service | ✅（38085，唯一业务服务） | ✅（38085） | ✅ 根（28085） |
| Flink 作业 ×7（enrich / sessions / retention / funnels / configurable-funnels / identity-merge / risk） | ❌ | ❌ | ✅ 根（eclipse-temurin:21-jre 直跑 fatJar 的 local executor 形态） |
| Superset | ❌ | ✅ 3.1.0（38088） | ✅ 根 3.1.0（8088）；infra 3.0.2（18088） |
| otel-collector + Prometheus + Grafana | ❌ | ❌ | ✅ infra（4317/4318/9464、9090、13000；配置在 `infra/prometheus.yml`、`infra/grafana/`、`deploy/monitoring/`） |
| 对象存储归档 | ❌ | ❌ | 不在任何 compose：`deploy/backup/postgres-backup.sh`（`aws s3 cp` → S3）+ `deploy/k8s/backup-cronjob.yaml` |
| K8s 形态 | — | — | `deploy/k8s/`（namespace/postgres/redis/control/backup-cronjob）+ `infra/helm/` |

## 各档说明

### Lite（deploy/demo）

演示站（https://oddsmaker.cuihairu.site/ ）实际运行档。只起 postgres + redis + control-service，
**故意不是 quickstart 的服务子集**：quickstart 的 control `depends_on kafka(healthy) +
topic-init(completed)`，按服务名挑着 up 也会连带拉起整套 Kafka/Apicurio，1.6 GB 跑不动。

缺组件的降级行为（编排注释与 README 已实证）：

- 无 Kafka：Kafka bootstrap 指向 `127.0.0.1:9092` 可解析假地址，消费者进入常规连接重试
  （ERROR 日志限流）；`KafkaHealthIndicator` 显式禁用，`/actuator/health` 保持 UP。
- 无 ClickHouse / 无 Gateway：分析类接口按 designed 降级返回 `available=false`；
  无采集入口（`POST /v1/batch` 不可用），对外仅 nginx 反代的控制面 REST + 控制台登录。

### Standard（docker-compose.quickstart.yml）

单机默认档 = quickstart。Kafka + ClickHouse + Apicurio + 双业务服务 + Superset 全部就位，
采集链路（gateway → Kafka `events_raw`）可用。

**边界**：本档没有 Flink 作业——`events_raw` 无下游消费者，ClickHouse 只有 initdb 的
schema 没有事件数据。控制面分析类接口此时 `available=true` 但返回空集；要出会话/漏斗/
留存/风控等分析产出，按根 compose 的 Flink 作业形态补起（先 `./gradlew build` 出 fatJar
放 `deploy-rt/jars/`）。Superset 需手动初始化管理员后使用。

### Production（根 docker-compose.yml + infra/docker-compose.yml）

全组件档。事件链路完整：gateway → Kafka → Flink ×7 → ClickHouse（events/enrich 产出到
session/funnel/retention/risk_scores 等分析表），控制面快照与 Superset/Grafana 看板有数可看。

- Flink 作业依赖宿主先构建 jar（`./gradlew build` → `cp jobs/flink/*/build/libs/*-all.jar
  deploy-rt/jars/`）；官方 flink 镜像无 Java 21 变体，故用 temurin:21-jre 直跑（根 compose
  头注已说明）。
- 观测面由 infra 件提供（otel-collector → Prometheus → Grafana），业务服务本身未配置
  otel exporter 接线时，观测面只收 infra 自身暴露的指标——接线属生产化改造，不在本矩阵声明内。
- PostgreSQL 备份归档走 `deploy/backup/`（脚本 + S3）或 K8s `backup-cronjob`。

## 不入档的编排

- `deploy-rt/docker-compose.e2e.yml`：e2e 测试栈（`e2e-verify.sh` 40 步实证），端口
  15/16/18/19 段，只用于验收，不是部署模式。
- `deploy/k8s/` + `infra/helm/`：Production 的 K8s 形态备选，与 compose 两件套二选一。

## 核对记录

B9（2026-10）逐文件读码核对：`deploy/demo/docker-compose.yml`（postgres/redis/control 三服务）、
`docker-compose.quickstart.yml`（postgres/redis/kafka/topic-init/clickhouse/apicurio/control/
gateway/superset 九服务）、仓库根 `docker-compose.yml`（上述九服务 + Flink 七作业，端口 2xxxx）、
`infra/docker-compose.yml`（kafka/clickhouse/apicurio/superset/otel-collector/prometheus/grafana
七服务，无 postgres/redis/业务服务）。矩阵格与 service 定义一一对应；编排文件头注的「本档 = ×」
标记即来自本文。
