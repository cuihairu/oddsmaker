# Oddsmaker Backup and Disaster Recovery Strategy

## Overview

This document outlines the backup and disaster recovery (DR) strategy for the Oddsmaker Gaming Analytics Platform.

## Recovery Objectives

| Component | RPO (Recovery Point Objective) | RTO (Recovery Time Objective) |
|-----------|-------------------------------|-------------------------------|
| PostgreSQL | 5 minutes | 15 minutes |
| Redis | 1 minute | 5 minutes |
| ClickHouse | 1 hour | 30 minutes |
| Kafka | 0 (replicated) | 10 minutes |
| Application | N/A | 10 minutes |

## Backup Strategy

### PostgreSQL

**Backup Type**: Continuous Archiving + Point-in-Time Recovery (PITR)

**Schedule**:
- Full backup: Daily at 02:00 UTC
- WAL archiving: Continuous
- Retention: 30 days

> k8s CronJob（`deploy/k8s/backup-cronjob.yaml`）每日 02:00 与每周日 03:00 各跑一次
> `pg_dump --format=custom` 全量逻辑备份（30 天就地留存清理）。两个作业同一脚本、同一镜像
> （`postgres:16-alpine`），差异仅在时段错开。**该镜像不含 aws CLI**，故脚本先判
> `command -v aws` 再传：`S3_BUCKET` 已配置但缺 CLI（或上传失败）时不让 `set -e` 中止
> ——本地备份与留存清理照常完成，但结尾以 **exit 2** 显式失败（审计 DR-001：本地成功 ≠
> 离站成功，离站缺口不得静默成功；exit 1=本地校验失败）。k8s 侧 Job 随之进入 Failed，
> 可被 `kubectl get jobs` / 告警面看到。离站副本需在基础镜像装 aws-cli，或改由外部备份
> 控制器接管。

**Configuration** (in-repo, both compose and k8s):
```sql
-- postgresql.conf（等价于 docker-compose.yml 的 postgres command 与 deploy/k8s/postgres.yaml 的 args）
archive_mode = on
wal_level = replica
max_wal_senders = 3
archive_timeout = 30s          -- 保证 RPO ≤ 5 分钟
archive_command = 'mkdir -p /var/lib/postgresql/archive \
  && cp %p /var/lib/postgresql/archive/%f \
  && find /var/lib/postgresql/archive -type f -mtime +30 -delete'
```

归档目录与数据卷分离（compose 命名卷 `postgres_archive` / k8s PVC `oddsmaker-postgres-archive-pvc`），
留存清理在同一次 `archive_command` shell 调用内完成，不依赖第二个 pod 挂载 RWO 卷。
归档卷属单主机存储，跨主机 DR 需平台侧快照或挂载到具备对象存储的 PVC 后再由备份作业上传。

**Backup Command**:
```bash
# Full backup
pg_basebackup -h localhost -U oddsmaker -D /backup/postgres/full -Ft -z -P

# Point-in-time recovery
pg_basebackup -h localhost -U oddsmaker -D /backup/postgres/pitr -Xs -P
```

### Redis

**Backup Type**: RDB Snapshots + AOF

**Schedule**:
- RDB snapshot: Every 15 minutes
- AOF: Every second
- Retention: 7 days

**Configuration**:
```
# redis.conf
save 900 1
save 300 10
save 60 10000
appendonly yes
appendfsync everysec
```

**Backup Command**:
```bash
# Manual backup
redis-cli BGSAVE
cp /var/lib/redis/dump.rdb /backup/redis/dump_$(date +%Y%m%d%H%M%S).rdb
```

k8s 编排（`deploy/k8s/backup-cronjob.yaml`）经 `redis-cli --rdb <file>` 网络流式导出，
不挂载数据 PVC（RWO 卷只归 Redis 本体），7 天留存就地清理。

### ClickHouse

**Backup Type**: Incremental Backups

**Schedule**:
- Full backup: Weekly on Sunday at 03:00 UTC
- Incremental backup: Daily at 03:00 UTC
- Retention: 90 days

**Backup Command** (in-repo, `deploy/backup/clickhouse-backup.sh`):
```bash
# Full backup（周日 03:00 UTC）
clickhouse-backup.sh full

# Incremental backup（每日 03:00 UTC；增量在远程上传面构建：upload --diff-from=最近一次 full，
# 无 full 或未配 S3_BUCKET 时退化为全量并显式打日志）
clickhouse-backup.sh incremental

# 配置项：CLICKHOUSE_HOST / CLICKHOUSE_PORT / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD
#         BACKUP_DIR（默认 /var/lib/clickhouse/backup，须与 CH 数据卷对齐——工具把本地备份
#         固定落 CH 数据目录的 backup/ 下，不读任何备份目录配置，此变量只服务于留存清理）
#         RETENTION_DAYS（默认 90）/ S3_BUCKET（可选上传）
#         S3_ENDPOINT / S3_ACCESS_KEY / S3_SECRET_KEY / S3_FORCE_PATH_STYLE /
#         S3_REGION / S3_STORAGE_CLASS 直透工具 config
```

> **clickhouse-backup 2.8.x 契约**（2026-10-19 恢复演练首次真实执行实证，此前脚本从未跑过）：
> 连接信息没有 CLI 参数（`--host/--port/--user/--password` 一律 flag provided but not defined），
> 只认环境变量 `clickhouse.host/port/username/password`——键名是 **username** 非 user，脚本已做
> `CLICKHOUSE_USER` → `CLICKHOUSE_USERNAME` 桥接；`create` 没有 `--diff-from`，增量只存在于
> 远程上传面；`upload` 没有 `--storage-class`。部署形态：工具经硬链接 CH 数据 parts 生成备份，
> 必须与 CH 数据卷**同路径挂载**运行（CH 容器内或共享数据卷的 sidecar），不是纯网络客户端——
> 只共享网络命名空间会 FATAL「disk default path /var/lib/clickhouse/ is not exists locally」。
> 排程现状：`deploy/k8s/backup-cronjob.yaml` 只有 PostgreSQL（两档）与 Redis 腿，ClickHouse
> 排程未接线——上表 Schedule 为目标口径，接线属部署面事项（接线时按上述同路径挂载约束配 sidecar）。

### Kafka

**Backup Type**: Topic Replication + MirrorMaker 2

**Configuration**:
- Replication factor: 3
- Min in-sync replicas: 2
- MirrorMaker 2 for cross-cluster replication

## Disaster Recovery Procedures

### Scenario 1: PostgreSQL Failure

**Detection**: Health check failure, connection errors

**Recovery Steps**:
1. Switch to standby database (if available)
2. If no standby, restore from latest backup:
   ```bash
   # Stop PostgreSQL
   systemctl stop postgresql

   # Restore from backup
   pg_basebackup -h backup-server -U oddsmaker -D /var/lib/postgresql/data -R

   # Start PostgreSQL
   systemctl start postgresql
   ```

3. Verify data integrity:
   ```sql
   SELECT count(*) FROM games;
   SELECT count(*) FROM users;
   SELECT max(created_at) FROM audit_logs;
   ```

> CronJob 实际产物是 `postgres-backup.sh` 的 `pg_dump --format=custom` 备份（非
> pg_basebackup），恢复用 `pg_restore`。已验证的完整流程见下方
> 「2026-10-19 本地恢复演练」——备份→新空库 `pg_restore --no-owner --no-privileges`→
> 逐表行数与源库一致→应用 `ddl-auto=validate` 启动健康 UP，全程隔离容器内完成；
> WAL 归档 + PITR 通路（pg_basebackup 基线 + 归档回放）同日演练通过，见下
> 「PITR 腿」。注意 PITR 语义：`recovery_target_time` 晚于归档 WAL 内最后一条提交
> 记录时会 FATAL「recovery ended before configured recovery target was reached」
> （演练实证）——恢复到最新应不设 target、直接回放到归档末端提升。

### Scenario 2: Redis Failure

**Detection**: Health check failure, cache miss rate increase

**Recovery Steps**:
1. Restart Redis:
   ```bash
   systemctl restart redis
   ```

2. If data corruption, restore from RDB:
   ```bash
   systemctl stop redis
   cp /backup/redis/dump_latest.rdb /var/lib/redis/dump.rdb
   systemctl start redis
   ```

3. Warm up cache (optional):
   ```bash
   # Application will repopulate cache on demand
   ```

### Scenario 3: ClickHouse Failure

**Detection**: Health check failure, query errors

**Recovery Steps**:
1. Restart ClickHouse:
   ```bash
   systemctl restart clickhouse-server
   ```

2. If data corruption, restore from backup:
   ```bash
   # 恢复链 = 先全量后增量（增量依赖全量链）；`latest_full`/`latest_incremental`
   # 不是 2.8.x 合法备份名（实测 FTL: open .../latest_full/metadata.json: no such file）
   clickhouse-backup restore_remote full_YYYYMMDD
   clickhouse-backup restore_remote incremental_YYYYMMDD

   # 本地备份目录已有同名档时（与 CH 数据卷同路径挂载的运行面）：
   clickhouse-backup restore full_YYYYMMDD
   ```

3. Verify data integrity:
   ```sql
   SELECT count() FROM events;
   SELECT count() FROM sessions;
   ```

### Scenario 4: Complete Site Failure

**Detection**: All services down

**Recovery Steps**:
1. Activate DR site (if available)
2. Restore databases in order:
   - PostgreSQL (primary)
   - Redis
   - ClickHouse
3. Start application services
4. Verify functionality
5. Update DNS to point to DR site

## Kubernetes Backup (Velero)

### Installation
```bash
velero install \
  --provider aws \
  --bucket oddsmaker-backups \
  --secret-file ./credentials \
  --use-restic
```

### Backup Schedule
```yaml
apiVersion: velero.io/v1
kind: Schedule
metadata:
  name: oddsmaker-daily-backup
  namespace: velero
spec:
  schedule: "0 2 * * *"
  template:
    includedNamespaces:
      - oddsmaker
    storageLocation: default
    ttl: 720h  # 30 days
```

### Restore
```bash
# List backups
velero backup get

# Restore from backup
velero restore create --from-backup oddsmaker-daily-backup-20240101020000
```

## Monitoring and Alerting

### Backup Monitoring
- Backup job success/failure
- Backup size and duration
- Storage usage
- Retention policy compliance

### Alerts
- Backup job failure
- Backup size anomaly
- Storage capacity warning
- RPO violation

## Testing

### DR Drill Schedule
- Monthly: Tabletop exercise
- Quarterly: Partial recovery test
- Annually: Full DR drill

### Test Checklist

> 2026-10 收尾核验：演练为环境执行项——本机栈未起、WAL 归档 / clickhouse-backup / 对象存储备份链路未部署（仓库侧交付物 = 本策略文档 + `postgres-backup.sh`），6 项需在具备完整备份链路的环境按上方演练计划执行后据实勾选，如实保留未勾。
>
> **2026-10-19 本地恢复演练（R1019，PG + Redis 两腿已执行）**：本机隔离容器内闭环——
> ① PG：产线脚本本体在 `postgres:16-alpine` 容器内对 demo 库全量备份（exit 0，424K
> custom 格式，自带 `pg_restore --list` 校验过）→ 全新空 PG16 容器 `pg_restore` 灌入 →
> **102/102 表逐表行数与源库完全一致**（flyway 历史 53/53 success 双库一致，真实数据面
> 含 permissions 102 行等）→ control-service `bootRun` 直连恢复库（`ddl-auto=validate`）
> 32s 启动、`/actuator/health` UP、日志零 ERROR——应用连接与 schema 接受实证；
> ② Redis：k8s CronJob 同款机制（`redis-cli --rdb` 网络流式导出，redis:7-alpine）→
> 新容器加载 dump.rdb → **43/43 键恢复**，string/hash/list/set 抽验逐键一致；
> ③ **PITR 腿**（WAL 归档 + pg_basebackup 基线回放，生产 compose/k8s 同款归档配置）：
> 源库分两批写数（R1 在基线前、R2 只存在于归档 WAL），`pg_basebackup` 基线 +
> `archive_timeout=30s` 自动切段归档 → 恢复端 A（回放到归档末端）**20/20 行全量恢复**
> （R2 仅经 WAL 回放重现）→ 恢复端 B（`recovery_target_time`=基线后 R2 前）日志显式
> `recovery stopping before commit of transaction` → **10 行（R2 精确排除）**。
> **演练揪出真缺陷**：配置原写的 `wal_archive_timeout` 不是 PostgreSQL GUC（真名
> `archive_timeout`），生产 postgres 带错名启动直接 FATAL crash 循环——39e2a1c 入库
> 以来从未被真实执行过，本演练为首次执行面。已全仓修正（compose/k8s/文档 6 处）并加
> `WalArchiveConfigTest` 钉测试（compose/k8s GUC 集一致 + 错名禁绝）。
> ④ **ClickHouse 腿**（同日，clickhouse/clickhouse-server:24.8 + Altinity clickhouse-backup
> 2.8.1 真二进制 + moto 作 S3 兼容端点）：`clickhouse-backup.sh` 原稿入库以来从未被执行，
> 演练实证其全部调用面与 2.8.x 不符（连接类 CLI 参数 / create --diff-from /
> upload --storage-class 三处 flag provided but not defined + BACKUP_DIR 与工具真实落点
> 错位致留存清理扫空目录）——已按实证契约重写并加 `ClickhouseBackupCliContractTest` 8 例
> 钉测试（先红后绿）。修复后全链闭环：full（exit 0）→ S3 上传 → R2 数据 → incremental
> （`upload --diff-from=full_*`，exit 0）→ R3 数据（备份后写入）→ 全新 CH 容器
> `restore_remote` 全量+增量两步 → **12000 行 = R1+R2 精确恢复、R3 的 500 行精确排除**、
> sessions 3000 一致；死端点实证离站失败面：本地备份完好 + 留存清理照常 + **exit 2**
> 非静默。文档对账同步修正：`restore latest_full` 非合法 2.8.x 备份名（实测 FTL），
> 恢复命令改 `restore_remote <name>`。**未覆盖（维持环境执行项）**：真实对象存储凭据
> 的离站恢复（moto 已证机制面，生产凭据待配）、全栈 RTO/RPO 实测——清单 6 项仍如实
> 保留未勾，待完整备份链路环境执行后勾选。

- [ ] Backup restoration successful
- [ ] Data integrity verified
- [ ] Application functionality verified
- [ ] RTO met
- [ ] RPO met
- [ ] Documentation updated

## Contacts

| Role | Name | Contact |
|------|------|---------|
| DBA | TBD | TBD |
| Platform Engineer | TBD | TBD |
| On-call Engineer | TBD | TBD |
