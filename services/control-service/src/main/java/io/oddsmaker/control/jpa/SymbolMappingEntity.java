package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 符号化映射文件元数据
 * 管理游戏上传的 dSYM (iOS) / Proguard mapping (Android) / source map (Web)，
 * 供符号化服务查询使用。实际文件存储由符号化服务或对象存储管理。
 */
@Entity
@Table(name = "symbol_mappings")
public class SymbolMappingEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 20)
    public String platform;

    @Column(name = "app_version", nullable = false, length = 50)
    public String appVersion;

    @Column(name = "build_version", length = 50)
    public String buildVersion;

    @Column(name = "file_type", nullable = false, length = 20)
    public String fileType;

    @Column(name = "file_path", nullable = false, length = 500)
    public String filePath;

    @Column(name = "file_size")
    public Long fileSize;

    @Column(name = "file_checksum", length = 64)
    public String fileChecksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public MappingStatus status = MappingStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false)
    public LocalDateTime uploadedAt;

    @Column(name = "uploaded_by", length = 64)
    public String uploadedBy;

    public enum MappingStatus {
        PENDING,
        ACTIVE,
        DEPRECATED
    }

    public enum FileType {
        DSYM,
        PROGUARD,
        SOURCEMAP
    }
}
