#!/bin/bash
# PostgreSQL Backup Script for Oddsmaker
# This script performs automated backups of the PostgreSQL database

set -e

# Configuration
BACKUP_DIR="${BACKUP_DIR:-/backup/postgres}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-oddsmaker}"
DB_USER="${DB_USER:-oddsmaker}"
BACKUP_TYPE="${1:-full}"  # full or incremental

# Logging。LOG_FILE 可被环境变量覆盖：容器内 /var/log 未必可写，
# tee -a 失败会因 set -e 直接中止，备份根本跑不起来。
LOG_FILE="${LOG_FILE:-/var/log/postgres-backup.log}"
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE" 2> /dev/null || echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Create backup directory if not exists
mkdir -p "$BACKUP_DIR"

# Generate backup filename
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/${BACKUP_TYPE}_${TIMESTAMP}.sql.gz"

# Start backup
log "Starting $BACKUP_TYPE backup of $DB_NAME"

if [ "$BACKUP_TYPE" = "full" ]; then
    # Full backup using pg_dump
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --format=custom \
        --compress=9 \
        --verbose \
        --file="$BACKUP_FILE" 2>> "$LOG_FILE"

    log "Full backup completed: $BACKUP_FILE"
else
    # Incremental backup (schema-only + recent data)
    SCHEMA_FILE="$BACKUP_DIR/schema_${TIMESTAMP}.sql"
    DATA_FILE="$BACKUP_DIR/data_${TIMESTAMP}.sql"

    # Backup schema
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --schema-only \
        --file="$SCHEMA_FILE" 2>> "$LOG_FILE"

    # Backup recent data (last 7 days)
    pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        --data-only \
        --where="created_at > NOW() - INTERVAL '7 days'" \
        --file="$DATA_FILE" 2>> "$LOG_FILE"

    # Compress
    tar -czf "$BACKUP_FILE" "$SCHEMA_FILE" "$DATA_FILE"
    rm -f "$SCHEMA_FILE" "$DATA_FILE"

    log "Incremental backup completed: $BACKUP_FILE"
fi

# Calculate backup size
BACKUP_SIZE=$(du -h "$BACKUP_FILE" 2> /dev/null | cut -f1 || echo "unknown")
log "Backup size: $BACKUP_SIZE"

# Verify backup integrity
log "Verifying backup integrity"
# 注意 set -e：此处不可写成 `pg_restore ... ; if [ $? -eq 0 ]`——pg_restore 失败
# 会被 set -e 先行中止，后面的分支永远走不到（校验形同虚设）。显式 if 承接退出码。
if pg_restore --list "$BACKUP_FILE" > /dev/null 2>&1; then
    log "Backup verification successful"
else
    log "ERROR: Backup verification failed"
    exit 1
fi

# Upload to remote storage (if configured)。
# 先判 aws 存在：缺 CLI 时 `aws: not found` 会被 set -e 当成失败，
# 连带下面的留存清理与通知永不执行。
# 离站状态显式化（审计 DR-001）：本地备份成功 ≠ 离站上传成功。上传失败不中止
# 后续留存清理（a0c1b9f 意图保留），但结尾以 exit 2 报失败——离站缺口不得静默成功。
# 退出码语义：0=本地+离站全成；1=本地备份校验失败；2=本地完好但离站副本缺失。
OFFSITE_STATUS="skipped"   # S3_BUCKET 未配置时无离站要求
OFFSITE_REASON=""
if [ -n "$S3_BUCKET" ]; then
    if command -v aws > /dev/null 2>&1; then
        log "Uploading backup to S3: $S3_BUCKET"
        if aws s3 cp "$BACKUP_FILE" "s3://$S3_BUCKET/postgres/" --storage-class STANDARD_IA; then
            OFFSITE_STATUS="uploaded"
            log "S3 upload completed"
        else
            OFFSITE_STATUS="failed"
            OFFSITE_REASON="s3 upload error"
            log "ERROR: S3 上传失败，离站副本缺失（本地备份保留在 $BACKUP_FILE）"
        fi
    else
        OFFSITE_STATUS="failed"
        OFFSITE_REASON="aws CLI missing"
        log "ERROR: S3_BUCKET=$S3_BUCKET 已配置但环境内无 aws CLI，离站副本缺失（本地备份保留在 $BACKUP_FILE）"
    fi
fi

# Clean up old backups
log "Cleaning up backups older than $RETENTION_DAYS days"
find "$BACKUP_DIR" -name "*.sql.gz" -type f -mtime +$RETENTION_DAYS -delete
log "Cleanup completed"

# Calculate total backup size
TOTAL_SIZE=$(du -sh "$BACKUP_DIR" 2> /dev/null | cut -f1 || echo "unknown")
log "Total backup storage: $TOTAL_SIZE"

# Send notification (if configured)。离站失败时正文与状态同步喊出来，通知面不留「全绿」假象
if [ -n "$WEBHOOK_URL" ]; then
    if [ "$OFFSITE_STATUS" = "failed" ]; then
        curl -s -X POST "$WEBHOOK_URL" \
            -H "Content-Type: application/json" \
            -d "{
                \"text\": \"❌ PostgreSQL backup OFFSITE FAILURE ($OFFSITE_REASON)\",
                \"details\": {
                    \"type\": \"$BACKUP_TYPE\",
                    \"file\": \"$BACKUP_FILE\",
                    \"size\": \"$BACKUP_SIZE\",
                    \"database\": \"$DB_NAME\",
                    \"offsite\": \"$OFFSITE_STATUS: $OFFSITE_REASON\"
                }
            }"
    else
        curl -s -X POST "$WEBHOOK_URL" \
            -H "Content-Type: application/json" \
            -d "{
                \"text\": \"✅ PostgreSQL backup completed\",
                \"details\": {
                    \"type\": \"$BACKUP_TYPE\",
                    \"file\": \"$BACKUP_FILE\",
                    \"size\": \"$BACKUP_SIZE\",
                    \"database\": \"$DB_NAME\",
                    \"offsite\": \"$OFFSITE_STATUS\"
                }
            }"
    fi
fi

if [ "$OFFSITE_STATUS" = "failed" ]; then
    log "Backup process finished with OFFSITE FAILURE ($OFFSITE_REASON)：本地备份完好，离站副本缺失"
    exit 2
fi

log "Backup process completed successfully"
