package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 多因素认证配置实体
 * 管理用户MFA设置
 */
@Entity
@Table(name = "mfa_configs")
public class MFAConfigEntity {

    /**
     * MFA方法类型
     */
    public enum MFAMethod {
        TOTP,           // 基于时间的一次性密码
        SMS,            // 短信验证码
        EMAIL,          // 邮箱验证码
        HARDWARE_TOKEN, // 硬件令牌
        BIOMETRIC,      // 生物识别
        PUSH,           // 推送通知
        BACKUP_CODE     // 备用恢复码
    }

    /**
     * MFA状态
     */
    public enum MFAStatus {
        DISABLED,       // 已禁用
        ENABLED,        // 已启用
        PENDING,        // 待验证
        LOCKED,         // 已锁定
        RECOVERY_MODE   // 恢复模式
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "user_id", nullable = false, length = 64)
    public String userId;  // 用户ID

    @Column(name = "mfa_method", nullable = false)
    @Enumerated(EnumType.STRING)
    public MFAMethod mfaMethod;  // MFA方法

    @Column(name = "mfa_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public MFAStatus mfaStatus = MFAStatus.DISABLED;

    @Column(name = "is_primary", columnDefinition = "BOOLEAN")
    public Boolean isPrimary = false;  // 是否为主要方法

    @Column(name = "secret_key", length = 100)
    public String secretKey;  // TOTP密钥

    @Column(name = "qr_code_url", length = 500)
    public String qrCodeUrl;  // 二维码URL（用于TOTP注册）

    @Column(name = "phone_number", length = 50)
    public String phoneNumber;  // 手机号码（SMS）

    @Column(name = "email_address", length = 200)
    public String emailAddress;  // 邮箱地址

    @Column(name = "device_id", length = 100)
    public String deviceId;  // 设备ID（硬件令牌）

    @Column(name = "device_name", length = 100)
    public String deviceName;  // 设备名称

    @Column(name = "verification_attempts", columnDefinition = "INTEGER")
    public Integer verificationAttempts = 0;  // 验证尝试次数

    @Column(name = "last_verified_at")
    public LocalDateTime lastVerifiedAt;  // 最后验证时间

    @Column(name = "last_used_at")
    public LocalDateTime lastUsedAt;  // 最后使用时间

    @Column(name = "backup_codes", columnDefinition = "TEXT")
    public String backupCodes;  // JSON格式的备用码列表

    @Column(name = "backup_codes_used", columnDefinition = "TEXT")
    public String backupCodesUsed;  // JSON格式的已使用备用码列表

    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;  // JSON格式的额外元数据

    @Column(name = "enrolled_at")
    public LocalDateTime enrolledAt;  // 注册时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isEnabled() {
        return mfaStatus == MFAStatus.ENABLED;
    }

    public boolean isPending() {
        return mfaStatus == MFAStatus.PENDING;
    }

    public boolean isLocked() {
        return mfaStatus == MFAStatus.LOCKED;
    }

    public boolean isRecoveryMode() {
        return mfaStatus == MFAStatus.RECOVERY_MODE;
    }

    public boolean isPrimary() {
        return isPrimary != null && isPrimary;
    }

    public boolean isTOTP() {
        return mfaMethod == MFAMethod.TOTP;
    }

    public boolean isSMS() {
        return mfaMethod == MFAMethod.SMS;
    }

    public boolean isEmail() {
        return mfaMethod == MFAMethod.EMAIL;
    }

    public boolean hasExceededAttempts() {
        return verificationAttempts != null && verificationAttempts >= 3;
    }

    public void enable() {
        this.mfaStatus = MFAStatus.ENABLED;
        this.verificationAttempts = 0;
        this.enrolledAt = LocalDateTime.now();
    }

    public void disable() {
        this.mfaStatus = MFAStatus.DISABLED;
        this.verificationAttempts = 0;
    }

    public void lock() {
        this.mfaStatus = MFAStatus.LOCKED;
    }

    public void unlock() {
        this.mfaStatus = MFAStatus.ENABLED;
        this.verificationAttempts = 0;
    }

    public void recordVerification() {
        this.verificationAttempts = 0;
        this.lastVerifiedAt = LocalDateTime.now();
    }

    public void recordFailedVerification() {
        this.verificationAttempts++;
    }

    public void markAsPrimary() {
        this.isPrimary = true;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (mfaStatus == null) {
            mfaStatus = MFAStatus.DISABLED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
