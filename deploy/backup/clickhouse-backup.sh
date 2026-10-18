#!/bin/bash
# ClickHouse Backup Script for Oddsmaker
# Implements deploy/backup/backup-strategy.md §ClickHouse (weekly full + daily incremental,
# 90-day retention) using the Altinity clickhouse-backup CLI.
#
# 2.8.x CLI 契约（2026-10-19 恢复演练首次真实执行实证——此前脚本从未被执行过）：
#   - 连接信息没有 CLI 参数（一律 flag provided but not defined），只认环境变量
#     clickhouse.host/port/username/password——键名是 username 非 user，
#     故 CLICKHOUSE_USER 在此桥接为 CLICKHOUSE_USERNAME
#   - create 没有 --diff-from：增量只存在于远程上传面（upload --diff-from=<上次 full>）；
#     未配远程时增量退化全量并显式打日志，不冒充
#   - upload 的存储类别不是 CLI 参数（实测 flag provided but not defined）：
#     走 s3.storage_class（env S3_STORAGE_CLASS）注入
#   - 工具经硬链接 CH 数据 parts 生成备份，必须与 CH 数据卷同路径挂载运行（CH 容器内或
#     共享数据卷的 sidecar，非纯网络客户端）；本地备份固定落 <CH 数据目录>/backup/ 下，
#     工具不读任何备份目录配置——BACKUP_DIR 只服务于本脚本的留存清理，须与工具落点对齐
#
# Usage:
#   clickhouse-backup.sh full          # weekly full backup
#   clickhouse-backup.sh incremental   # daily incremental (uploaded as diff from last full)
#
# Environment:
#   CLICKHOUSE_HOST   clickhouse host (default: localhost)
#   CLICKHOUSE_PORT   native port (default: 9000)
#   CLICKHOUSE_USER   (default: oddsmaker)
#   CLICKHOUSE_PASSWORD
#   BACKUP_DIR        工具本地备份目录（默认 /var/lib/clickhouse/backup，与 CH 数据卷对齐）
#   RETENTION_DAYS    (default: 90)
#   S3_BUCKET         if set, uploads backups to s3://$S3_BUCKET/clickhouse/
#   S3_PATH / S3_STORAGE_CLASS / S3_ENDPOINT / S3_ACCESS_KEY / S3_SECRET_KEY /
#   S3_FORCE_PATH_STYLE / S3_REGION   直透工具 config（实测键名）

set -e

BACKUP_TYPE="${1:-full}"
BACKUP_DIR="${BACKUP_DIR:-/var/lib/clickhouse/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-90}"
CH_HOST="${CLICKHOUSE_HOST:-localhost}"
CH_PORT="${CLICKHOUSE_PORT:-9000}"
CH_USER="${CLICKHOUSE_USER:-oddsmaker}"
CH_PASSWORD="${CLICKHOUSE_PASSWORD:-}"
TIMESTAMP=$(date +%Y%m%d)

# 2.8.x 无连接类 CLI 参数：经环境变量注入 config（clickhouse.*，键名 username）
export CLICKHOUSE_HOST="$CH_HOST"
export CLICKHOUSE_PORT="$CH_PORT"
export CLICKHOUSE_USERNAME="$CH_USER"
export CLICKHOUSE_PASSWORD="$CH_PASSWORD"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"; }

mkdir -p "$BACKUP_DIR"

# 上次 full 的本地名（`list local` 文本格式名称在首列；日志行走 stderr 已滤）
LAST_FULL=$(clickhouse-backup list local 2>/dev/null | awk '$1 ~ /^full_/ {print $1}' | sort | tail -1)

if [ -n "$S3_BUCKET" ]; then
    # 工具默认 general.remote_storage=none，不显式切到 s3 则 upload 直接失败
    export REMOTE_STORAGE=s3
fi

if [ "$BACKUP_TYPE" = "full" ]; then
    BACKUP_NAME="full_$TIMESTAMP"
    log "Starting full backup: $BACKUP_NAME"
    clickhouse-backup create "$BACKUP_NAME"
elif [ "$BACKUP_TYPE" = "incremental" ]; then
    if [ -z "$S3_BUCKET" ]; then
        log "No S3_BUCKET configured; 2.8.x has no local-only incremental — fallback to full: full_$TIMESTAMP"
        BACKUP_NAME="full_$TIMESTAMP"
        clickhouse-backup create "$BACKUP_NAME"
    elif [ -z "$LAST_FULL" ]; then
        log "No full backup found; falling back to full"
        BACKUP_NAME="full_$TIMESTAMP"
        clickhouse-backup create "$BACKUP_NAME"
    else
        BACKUP_NAME="incremental_$TIMESTAMP"
        log "Starting incremental backup: $BACKUP_NAME (diff from $LAST_FULL)"
        clickhouse-backup create "$BACKUP_NAME"
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
uploadDiffArg=""
if [ "$BACKUP_TYPE" = "incremental" ] && [ -n "$LAST_FULL" ]; then
    # 增量的 diff 只作用于上传面（create 本地档仍是完整硬链接树）
    uploadDiffArg="--diff-from=$LAST_FULL"
fi
if [ -n "$S3_BUCKET" ]; then
    log "Uploading $BACKUP_NAME to s3://$S3_BUCKET/clickhouse/"
    if clickhouse-backup upload "$BACKUP_NAME" $uploadDiffArg; then
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
