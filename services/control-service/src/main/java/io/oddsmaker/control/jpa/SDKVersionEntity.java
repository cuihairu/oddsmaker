package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SDK版本实体
 * 跟踪SDK版本和发布管理
 */
@Entity
@Table(name = "sdk_versions")
public class SDKVersionEntity {

    /**
     * SDK平台
     */
    public enum SDKPlatform {
        WEB,           // Web/JavaScript
        ANDROID,       // Android
        IOS,           // iOS
        UNITY,         // Unity
        UNREAL,        // Unreal Engine
        REACT_NATIVE,  // React Native
        FLUTTER,       // Flutter
        Cocos2d,       // Cocos2d
        SERVER,        // 服务端SDK
        CUSTOM         // 自定义
    }

    /**
     * 版本状态
     */
    public enum VersionStatus {
        DRAFT,         // 草稿
        TESTING,       // 测试中
        BETA,          // Beta版
        RELEASED,      // 已发布
        DEPRECATED,    // 已弃用
        RETIRED        // 已退役
    }

    /**
     * 变更类型
     */
    public enum ChangeType {
        MAJOR,         // 主版本
        MINOR,         // 次版本
        PATCH,         // 补丁
        HOTFIX         // 热修复
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "platform", nullable = false)
    @Enumerated(EnumType.STRING)
    public SDKPlatform platform;  // SDK平台

    @Column(name = "version", nullable = false, length = 20)
    public String version;  // 版本号（语义化版本）

    @Column(name = "version_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public VersionStatus versionStatus = VersionStatus.DRAFT;  // 版本状态

    @Column(name = "change_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public ChangeType changeType = ChangeType.PATCH;  // 变更类型

    @Column(name = "release_notes", columnDefinition = "TEXT")
    public String releaseNotes;  // 发布说明

    @Column(name = "changelog", columnDefinition = "TEXT")
    public String changelog;  // 变更日志

    @Column(name = "breaking_changes", columnDefinition = "TEXT")
    public String breakingChanges;  // 破坏性变更说明

    @Column(name = "migration_guide", columnDefinition = "TEXT")
    public String migrationGuide;  // 迁移指南

    @Column(name = "download_url", length = 500)
    public String downloadUrl;  // 下载链接

    @Column(name = "package_name", length = 200)
    public String packageName;  // 包名

    @Column(name = "package_manager", length = 50)
    public String packageManager;  // 包管理器（npm, maven, cocoapods等）

    @Column(name = "checksum_sha256", length = 64)
    public String checksumSha256;  // SHA256校验和

    @Column(name = "file_size_bytes", columnDefinition = "BIGINT")
    public Long fileSizeBytes;  // 文件大小

    @Column(name = "min_platform_version", length = 20)
    public String minPlatformVersion;  // 最低平台版本

    @Column(name = "max_platform_version", length = 20)
    public String maxPlatformVersion;  // 最高平台版本

    @Column(name = "dependencies", columnDefinition = "TEXT")
    public String dependencies;  // JSON格式的依赖列表

    @Column(name = "api_compatibility", columnDefinition = "TEXT")
    public String apiCompatibility;  // JSON格式的API兼容性信息

    @Column(name = "feature_flags", columnDefinition = "TEXT")
    public String featureFlags;  // JSON格式的特性标志

    @Column(name = "deprecation_notice", columnDefinition = "TEXT")
    public String deprecationNotice;  // 弃用通知

    @Column(name = "retirement_date")
    public LocalDateTime retirementDate;  // 退役日期

    @Column(name = "released_at")
    public LocalDateTime releasedAt;  // 发布时间

    @Column(name = "released_by", length = 64)
    public String releasedBy;  // 发布人

    @Column(name = "total_downloads", columnDefinition = "BIGINT")
    public Long totalDownloads = 0L;  // 总下载次数

    @Column(name = "active_installations", columnDefinition = "BIGINT")
    public Long activeInstallations = 0L;  // 活跃安装数

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    // 辅助方法

    public boolean isDraft() {
        return versionStatus == VersionStatus.DRAFT;
    }

    public boolean isTesting() {
        return versionStatus == VersionStatus.TESTING;
    }

    public boolean isBeta() {
        return versionStatus == VersionStatus.BETA;
    }

    public boolean isReleased() {
        return versionStatus == VersionStatus.RELEASED;
    }

    public boolean isDeprecated() {
        return versionStatus == VersionStatus.DEPRECATED;
    }

    public boolean isRetired() {
        return versionStatus == VersionStatus.RETIRED;
    }

    public boolean isMajor() {
        return changeType == ChangeType.MAJOR;
    }

    public boolean isMinor() {
        return changeType == ChangeType.MINOR;
    }

    public boolean isPatch() {
        return changeType == ChangeType.PATCH;
    }

    public boolean isHotfix() {
        return changeType == ChangeType.HOTFIX;
    }

    public boolean isAvailable() {
        return versionStatus == VersionStatus.RELEASED || versionStatus == VersionStatus.BETA;
    }

    public boolean isRetiredSoon() {
        return retirementDate != null && retirementDate.isBefore(LocalDateTime.now().plusDays(90));
    }

    public void release(String releasedBy) {
        this.versionStatus = VersionStatus.RELEASED;
        this.releasedAt = LocalDateTime.now();
        this.releasedBy = releasedBy;
    }

    public void deprecate(String notice) {
        this.versionStatus = VersionStatus.DEPRECATED;
        this.deprecationNotice = notice;
    }

    public void retire() {
        this.versionStatus = VersionStatus.RETIRED;
        this.retirementDate = LocalDateTime.now();
    }

    public void recordDownload() {
        this.totalDownloads++;
    }

    public void updateActiveInstallations(long count) {
        this.activeInstallations = count;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (versionStatus == null) {
            versionStatus = VersionStatus.DRAFT;
        }
        if (changeType == null) {
            changeType = ChangeType.PATCH;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
