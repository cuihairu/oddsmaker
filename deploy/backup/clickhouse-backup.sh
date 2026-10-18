#!/bin/bash
# ClickHouse Backup Script for Oddsmaker
# Implements deploy/backup/backup-strategy.md §ClickHouse (weekly full + daily incremental,
# 90-day retention) using the Altinity clickhouse-backup CLI.
#
# Usage:
#   clickhouse-backup.sh full          # weekly full backup
#   clickhouse-backup.sh incremental   # daily incremental (diff from last full)
#
# Environment:
#   CLICKHOUSE_HOST   clickhouse host (default: localhost)
#   CLICKHOUSE_PORT   native port (default: 9000)
#   CLICKHOUSE_USER   (default: oddsmaker)
#   CLICKHOUSE_PASSWORD
#   BACKUP_DIR        local backup staging dir (default: /backup/clickhouse)
#   RETENTION_DAYS    (default: 90)
#   S3_BUCKET         if set, uploads backups to s3://$S3_BUCKET/clickhouse/

set -e

BACKUP_TYPE="${1:-full}"
BACKUP_DIR="${BACKUP_DIR:-/backup/clickhouse}"
RETENTION_DAYS="${RETENTION_DAYS:-90}"
CH_HOST="${CLICKHOUSE_HOST:-localhost}"
CH_PORT="${CLICKHOUSE_PORT:-9000}"
CH_USER="${CLICKHOUSE_USER:-oddsmaker}"
CH_PASSWORD="${CLICKHOUSE_PASSWORD:-}"
TIMESTAMP=$(date +%Y%m%d)

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"; }

mkdir -p "$BACKUP_DIR"

# clickhouse-backup CLI flags (remote server via native protocol)
CH_FLAGS="--host $CH_HOST --port $CH_PORT --user $CH_USER"
if [ -n "$CH_PASSWORD" ]; then
    CH_FLAGS="$CH_FLAGS --password $CH_PASSWORD"
fi

if [ "$BACKUP_TYPE" = "full" ]; then
    BACKUP_NAME="full_$TIMESTAMP"
    log "Starting full backup: $BACKUP_NAME"
    clickhouse-backup $CH_FLAGS create "$BACKUP_NAME"
elif [ "$BACKUP_TYPE" = "incremental" ]; then
    # diff-from: 最近一次 full（按名字排序取最后一个）
    LAST_FULL=$(clickhouse-backup $CH_FLAGS list 2>/dev/null | awk '/^full_/ {print $1}' | sort | tail -1)
    if [ -z "$LAST_FULL" ]; then
        log "No full backup found; falling back to full"
        BACKUP_NAME="full_$TIMESTAMP"
        clickhouse-backup $CH_FLAGS create "$BACKUP_NAME"
    else
        BACKUP_NAME="incremental_$TIMESTAMP"
        log "Starting incremental backup: $BACKUP_NAME (diff from $LAST_FULL)"
        clickhouse-backup $CH_FLAGS create "$BACKUP_NAME" --diff-from="$LAST_FULL"
    fi
else
    echo "Unknown backup type: $BACKUP_TYPE (expected full|incremental)" >&2
    exit 1
fi

# Upload to S3 (if configured)
# 注意 set -e：上传失败不得中止——留存清理在其后，中止会让备份目录无界增长。
# 离站状态显式化（审计 DR-001）：本地成功 ≠ 离站成功，结尾按状态以 exit 2 报离站失败。
OFFSITE_STATUS="skipped"
OFFSITE_REASON=""
if [ -n "$S3_BUCKET" ]; then
    log "Uploading $BACKUP_NAME to s3://$S3_BUCKET/clickhouse/"
    if clickhouse-backup $CH_FLAGS upload "$BACKUP_NAME" --storage-class STANDARD_IA; then
        OFFSITE_STATUS="uploaded"
    else
        OFFSITE_STATUS="failed"
        OFFSITE_REASON="s3 upload error"
        log "ERROR: S3 上传失败，离站副本缺失（本地备份保留在 $BACKUP_DIR/$BACKUP_NAME）"
    fi
fi

# Retention: delete local backups older than RETENTION_DAYS
# -name 谓词必须括号分组：-o 优先级低于隐式 AND，不分组时 -maxdepth/-type 只约束第一个分支。
find "$BACKUP_DIR" -maxdepth 1 -type d \( -name "full_*" -o -name "incremental_*" \) | while read -r d; do
    if [ -n "$(find "$d" -maxdepth 0 -mtime +"$RETENTION_DAYS" -print -quit 2>/dev/null)" ]; then
        log "Pruning expired backup: $d"
        rm -rf "$d"
    fi
done

if [ "$OFFSITE_STATUS" = "failed" ]; then
    log "ClickHouse backup finished with OFFSITE FAILURE ($OFFSITE_REASON)：本地备份完好，离站副本缺失"
    exit 2
fi

log "ClickHouse backup completed: $BACKUP_NAME"
