package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 玩家数据导出任务：按 (gameId, playerId) 打包玩家全量数据（档案/充值/登录/兑换）。
 * sweep 异步处理 PENDING 任务生成文件；到期后 cleanup 标记 EXPIRED 并删除文件。
 */
@Entity
@Table(name = "player_export_jobs",
    indexes = @Index(name = "idx_pex_game_player", columnList = "game_id, player_id, created_at"))
public class PlayerExportJobEntity {

    @Id
    @Column(length = 48)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 64)
    public String environmentId;

    @Column(name = "player_id", nullable = false, length = 128)
    public String playerId;

    /** json：单文件；csv：按分区打包 zip */
    @Column(name = "export_format", nullable = false, length = 20)
    public String exportFormat = "json";

    /** 导出分区 JSON 数组：profile / payments / login-logs / redeem-records */
    @Column(columnDefinition = "TEXT")
    public String sections;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.PENDING;

    @Column(name = "file_name", length = 255)
    public String fileName;

    @Column(name = "file_path", length = 500)
    public String filePath;

    @Column(name = "file_size_bytes")
    public Long fileSizeBytes;

    /** 导出记录总数（档案计 1 条 + 各分区行数） */
    @Column(name = "row_count")
    public Long rowCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "requested_by", length = 64)
    public String requestedBy;

    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        EXPIRED
    }
}
