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

**Configuration** (in-repo, both compose and k8s):
```sql
-- postgresql.conf（等价于 docker-compose.yml 的 postgres command 与 deploy/k8s/postgres.yaml 的 args）
archive_mode = on
wal_level = replica
max_wal_senders = 3
wal_archive_timeout = 30s      -- 保证 RPO ≤ 5 分钟
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

# Incremental backup（每日 03:00 UTC；自动 diff-from 最近一次 full，无 full 时回退 full）
clickhouse-backup.sh incremental

# 配置项：CLICKHOUSE_HOST / CLICKHOUSE_PORT / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD
#         BACKUP_DIR（默认 /backup/clickhouse）/ RETENTION_DAYS（默认 90）/ S3_BUCKET（可选上传）
```

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
   clickhouse-backup restore latest_full
   clickhouse-backup restore latest_incremental
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
