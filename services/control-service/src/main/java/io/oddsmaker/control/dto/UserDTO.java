package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.UserEntity;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户数据传输对象
 */
public class UserDTO {

    public String id;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    public String email;

    @Size(max = 100, message = "姓名不能超过100个字符")
    public String name;

    @Size(max = 100, message = "显示名称不能超过100个字符")
    public String displayName;

    public String avatarUrl;

    public UserEntity.GlobalRole globalRole;
    public UserEntity.UserStatus status;

    // 个人信息
    @Size(max = 100, message = "公司名称不能超过100个字符")
    public String company;

    @Size(max = 50, message = "职位不能超过50个字符")
    public String title;

    @Pattern(regexp = "^[+]?[0-9\\s-()]+$", message = "电话格式不正确")
    public String phone;

    public String timeZone;
    public String locale;

    // 偏好设置
    public Boolean notificationEmail;
    public Boolean notificationSms;
    public String dashboardTheme;

    // 安全信息（只读）
    public Boolean emailVerified;
    public Boolean twoFactorEnabled;
    public Integer loginAttempts;
    public LocalDateTime lastLogin;
    public String lastLoginIp;
    public Boolean isLocked;

    // 角色信息（只读）
    public List<Map<String, String>> roles;

    // 时间戳
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    // 构造函数
    public UserDTO() {}

    public UserDTO(UserEntity entity) {
        this.id = entity.id;
        this.email = entity.email;
        this.name = entity.name;
        this.displayName = entity.displayName;
        this.avatarUrl = entity.avatarUrl;
        this.globalRole = entity.globalRole;
        this.status = entity.status;
        this.company = entity.company;
        this.title = entity.title;
        this.phone = entity.phone;
        this.timeZone = entity.timeZone;
        this.locale = entity.locale;
        this.notificationEmail = entity.notificationEmail;
        this.notificationSms = entity.notificationSms;
        this.dashboardTheme = entity.dashboardTheme;
        this.emailVerified = entity.emailVerified;
        this.twoFactorEnabled = entity.twoFactorEnabled;
        this.loginAttempts = entity.loginAttempts;
        this.lastLogin = entity.lastLogin;
        this.lastLoginIp = entity.lastLoginIp;
        this.isLocked = entity.isLocked();
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
    }

    /**
     * 转换为实体对象
     */
    public UserEntity toEntity() {
        UserEntity entity = new UserEntity();
        entity.id = this.id;
        entity.email = this.email;
        entity.name = this.name;
        entity.displayName = this.displayName;
        entity.avatarUrl = this.avatarUrl;
        entity.globalRole = this.globalRole != null ? this.globalRole : UserEntity.GlobalRole.USER;
        entity.status = this.status != null ? this.status : UserEntity.UserStatus.ACTIVE;
        entity.company = this.company;
        entity.title = this.title;
        entity.phone = this.phone;
        entity.timeZone = this.timeZone != null ? this.timeZone : "UTC";
        entity.locale = this.locale != null ? this.locale : "en-US";
        entity.notificationEmail = this.notificationEmail != null ? this.notificationEmail : true;
        entity.notificationSms = this.notificationSms != null ? this.notificationSms : false;
        entity.dashboardTheme = this.dashboardTheme != null ? this.dashboardTheme : "light";
        return entity;
    }

    /**
     * 更新实体对象
     */
    public void updateEntity(UserEntity entity) {
        if (this.email != null) entity.email = this.email;
        if (this.name != null) entity.name = this.name;
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.avatarUrl != null) entity.avatarUrl = this.avatarUrl;
        if (this.globalRole != null) entity.globalRole = this.globalRole;
        if (this.status != null) entity.status = this.status;
        if (this.company != null) entity.company = this.company;
        if (this.title != null) entity.title = this.title;
        if (this.phone != null) entity.phone = this.phone;
        if (this.timeZone != null) entity.timeZone = this.timeZone;
        if (this.locale != null) entity.locale = this.locale;
        if (this.notificationEmail != null) entity.notificationEmail = this.notificationEmail;
        if (this.notificationSms != null) entity.notificationSms = this.notificationSms;
        if (this.dashboardTheme != null) entity.dashboardTheme = this.dashboardTheme;
    }

    // 业务方法
    public boolean isSuperAdmin() {
        return globalRole == UserEntity.GlobalRole.SUPER_ADMIN;
    }

    public boolean isOperator() {
        return globalRole == UserEntity.GlobalRole.OPERATOR || isSuperAdmin();
    }

    public boolean isActive() {
        return status == UserEntity.UserStatus.ACTIVE;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified != null && emailVerified;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled != null && twoFactorEnabled;
    }

    /**
     * 检查是否有指定角色
     */
    public boolean hasRole(String roleName) {
        if (roles == null) return false;
        return roles.stream()
            .anyMatch(role -> roleName.equals(role.get("role")));
    }

    /**
     * 检查是否在指定游戏有角色
     */
    public boolean hasRoleInGame(String gameId) {
        if (roles == null) return false;
        return roles.stream()
            .anyMatch(role -> gameId.equals(role.get("gameId")));
    }

}
