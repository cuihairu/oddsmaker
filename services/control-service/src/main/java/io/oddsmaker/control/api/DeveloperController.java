package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.*;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.DeveloperPortalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开发者门户API控制器
 * 提供SDK管理和开发者工具接口；鉴权走 AccessGuard 行内风格（sdkkey / sdkversion / telemetry 各 read/manage）。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_SDK_KEYS') 静态式与 'MANAGE_SDK_KEYS:'+gameId 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/developer")
public class DeveloperController {

    @Autowired
    private DeveloperPortalService developerPortalService;

    @Autowired
    private AccessGuard accessGuard;

    // ==================== SDK密钥管理 ====================

    /**
     * 创建SDK密钥
     */
    @PostMapping("/sdk-keys")
    public ResponseEntity<SDKKeyEntity> createSDKKey(@RequestBody CreateSDKKeyRequest request) {
        accessGuard.requireGamePermission(request.gameId, "sdkkey:manage");
        SDKKeyEntity key = developerPortalService.createSDKKey(
            request.gameId,
            request.environment,
            request.keyName,
            request.platform,
            request.deliveryMode,
            request.config,
            request.createdBy
        );
        return ResponseEntity.ok(key);
    }

    /**
     * 获取SDK密钥详情
     */
    @GetMapping("/sdk-keys/{keyId}")
    public ResponseEntity<SDKKeyEntity> getSDKKey(@PathVariable String keyId) {
        accessGuard.requirePermission("sdkkey:read");
        SDKKeyEntity key = developerPortalService.getSDKKey(keyId);
        return ResponseEntity.ok(key);
    }

    /**
     * 获取游戏的SDK密钥列表
     */
    @GetMapping("/sdk-keys/game/{gameId}")
    public ResponseEntity<List<SDKKeyEntity>> getGameSDKKeys(
            @PathVariable String gameId,
            @RequestParam(required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "sdkkey:read");
        List<SDKKeyEntity> keys = developerPortalService.getGameSDKKeys(gameId, environment);
        return ResponseEntity.ok(keys);
    }

    /**
     * 更新SDK密钥配置
     */
    @PutMapping("/sdk-keys/{keyId}")
    public ResponseEntity<SDKKeyEntity> updateSDKKey(
            @PathVariable String keyId,
            @RequestBody UpdateSDKKeyRequest request) {
        accessGuard.requirePermission("sdkkey:manage");
        SDKKeyEntity key = developerPortalService.updateSDKKey(keyId, request.updates, request.updatedBy);
        return ResponseEntity.ok(key);
    }

    /**
     * 暂停SDK密钥
     */
    @PostMapping("/sdk-keys/{keyId}/suspend")
    public ResponseEntity<SDKKeyEntity> suspendSDKKey(
            @PathVariable String keyId,
            @RequestBody SuspendRequest request) {
        accessGuard.requirePermission("sdkkey:manage");
        SDKKeyEntity key = developerPortalService.suspendSDKKey(keyId, request.suspendedBy);
        return ResponseEntity.ok(key);
    }

    /**
     * 激活SDK密钥
     */
    @PostMapping("/sdk-keys/{keyId}/activate")
    public ResponseEntity<SDKKeyEntity> activateSDKKey(
            @PathVariable String keyId,
            @RequestBody ActivateRequest request) {
        accessGuard.requirePermission("sdkkey:manage");
        SDKKeyEntity key = developerPortalService.activateSDKKey(keyId, request.activatedBy);
        return ResponseEntity.ok(key);
    }

    /**
     * 撤销SDK密钥
     */
    @PostMapping("/sdk-keys/{keyId}/revoke")
    public ResponseEntity<SDKKeyEntity> revokeSDKKey(
            @PathVariable String keyId,
            @RequestBody RevokeRequest request) {
        accessGuard.requirePermission("sdkkey:manage");
        SDKKeyEntity key = developerPortalService.revokeSDKKey(keyId, request.revokedBy);
        return ResponseEntity.ok(key);
    }

    /**
     * 删除SDK密钥
     */
    @DeleteMapping("/sdk-keys/{keyId}")
    public ResponseEntity<Void> deleteSDKKey(
            @PathVariable String keyId,
            @RequestBody DeleteRequest request) {
        accessGuard.requirePermission("sdkkey:manage");
        developerPortalService.deleteSDKKey(keyId, request.deletedBy);
        return ResponseEntity.ok().build();
    }

    /**
     * 验证SDK密钥
     */
    @GetMapping("/sdk-keys/{publicKey}/validate")
    public ResponseEntity<Map<String, Object>> validateSDKKey(
            @PathVariable String publicKey,
            @RequestParam String gameId,
            @RequestParam String environment) {
        boolean valid = developerPortalService.validateSDKKey(publicKey, gameId, environment);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    // ==================== SDK版本管理 ====================

    /**
     * 创建SDK版本
     */
    @PostMapping("/sdk-versions")
    public ResponseEntity<SDKVersionEntity> createSDKVersion(@RequestBody CreateSDKVersionRequest request) {
        accessGuard.requirePermission("sdkversion:manage");
        SDKVersionEntity version = developerPortalService.createSDKVersion(
            request.platform,
            request.version,
            request.changeType,
            request.releaseNotes,
            request.changelog,
            request.createdBy
        );
        return ResponseEntity.ok(version);
    }

    /**
     * 获取SDK版本详情
     */
    @GetMapping("/sdk-versions/{versionId}")
    public ResponseEntity<SDKVersionEntity> getSDKVersion(@PathVariable String versionId) {
        accessGuard.requirePermission("sdkversion:read");
        SDKVersionEntity version = developerPortalService.getSDKVersion(versionId);
        return ResponseEntity.ok(version);
    }

    /**
     * 获取平台的版本列表
     */
    @GetMapping("/sdk-versions/platform/{platform}")
    public ResponseEntity<List<SDKVersionEntity>> getPlatformVersions(
            @PathVariable SDKVersionEntity.SDKPlatform platform,
            @RequestParam(required = false) String status) {
        accessGuard.requirePermission("sdkversion:read");
        List<SDKVersionEntity> versions = developerPortalService.getPlatformVersions(platform, status);
        return ResponseEntity.ok(versions);
    }

    /**
     * 获取最新版本
     */
    @GetMapping("/sdk-versions/platform/{platform}/latest")
    public ResponseEntity<SDKVersionEntity> getLatestVersion(@PathVariable SDKVersionEntity.SDKPlatform platform) {
        accessGuard.requirePermission("sdkversion:read");
        SDKVersionEntity version = developerPortalService.getLatestVersion(platform);
        return ResponseEntity.ok(version);
    }

    /**
     * 发布版本
     */
    @PostMapping("/sdk-versions/{versionId}/release")
    public ResponseEntity<SDKVersionEntity> releaseVersion(
            @PathVariable String versionId,
            @RequestBody ReleaseVersionRequest request) {
        accessGuard.requirePermission("sdkversion:manage");
        SDKVersionEntity version = developerPortalService.releaseVersion(
            versionId,
            request.downloadUrl,
            request.packageName,
            request.packageManager,
            request.checksumSha256,
            request.fileSizeBytes,
            request.releasedBy
        );
        return ResponseEntity.ok(version);
    }

    /**
     * 弃用版本
     */
    @PostMapping("/sdk-versions/{versionId}/deprecate")
    public ResponseEntity<SDKVersionEntity> deprecateVersion(
            @PathVariable String versionId,
            @RequestBody DeprecateVersionRequest request) {
        accessGuard.requirePermission("sdkversion:manage");
        SDKVersionEntity version = developerPortalService.deprecateVersion(
            versionId,
            request.deprecationNotice,
            request.deprecatedBy
        );
        return ResponseEntity.ok(version);
    }

    /**
     * 退役版本
     */
    @PostMapping("/sdk-versions/{versionId}/retire")
    public ResponseEntity<SDKVersionEntity> retireVersion(
            @PathVariable String versionId,
            @RequestBody RetireRequest request) {
        accessGuard.requirePermission("sdkversion:manage");
        SDKVersionEntity version = developerPortalService.retireVersion(versionId, request.retiredBy);
        return ResponseEntity.ok(version);
    }

    /**
     * 记录下载
     */
    @PostMapping("/sdk-versions/{versionId}/download")
    public ResponseEntity<Void> recordDownload(@PathVariable String versionId) {
        developerPortalService.recordDownload(versionId);
        return ResponseEntity.ok().build();
    }

    // ==================== 遥测配置管理 ====================

    /**
     * 创建遥测配置
     */
    @PostMapping("/telemetry-configs")
    public ResponseEntity<TelemetryConfigEntity> createTelemetryConfig(@RequestBody CreateTelemetryConfigRequest request) {
        accessGuard.requireGamePermission(request.gameId, "telemetry:manage");
        TelemetryConfigEntity config = developerPortalService.createTelemetryConfig(
            request.gameId,
            request.environmentId,
            request.configName,
            request.configType,
            request.description,
            request.isDefault,
            request.config,
            request.createdBy
        );
        return ResponseEntity.ok(config);
    }

    /**
     * 获取遥测配置详情
     */
    @GetMapping("/telemetry-configs/{configId}")
    public ResponseEntity<TelemetryConfigEntity> getTelemetryConfig(@PathVariable String configId) {
        accessGuard.requirePermission("telemetry:read");
        TelemetryConfigEntity config = developerPortalService.getTelemetryConfig(configId);
        return ResponseEntity.ok(config);
    }

    /**
     * 获取游戏的遥测配置列表
     */
    @GetMapping("/telemetry-configs/game/{gameId}")
    public ResponseEntity<List<TelemetryConfigEntity>> getGameTelemetryConfigs(
            @PathVariable String gameId,
            @RequestParam(required = false) String environmentId) {
        accessGuard.requireGamePermission(gameId, "telemetry:read");
        List<TelemetryConfigEntity> configs = developerPortalService.getGameTelemetryConfigs(gameId, environmentId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 获取有效的遥测配置
     */
    @GetMapping("/telemetry-configs/effective")
    public ResponseEntity<TelemetryConfigEntity> getEffectiveConfig(
            @RequestParam String gameId,
            @RequestParam String environmentId,
            @RequestParam TelemetryConfigEntity.ConfigType configType) {
        accessGuard.requirePermission("telemetry:read");
        TelemetryConfigEntity config = developerPortalService.getEffectiveConfig(gameId, environmentId, configType);
        return ResponseEntity.ok(config);
    }

    /**
     * 更新遥测配置
     */
    @PutMapping("/telemetry-configs/{configId}")
    public ResponseEntity<TelemetryConfigEntity> updateTelemetryConfig(
            @PathVariable String configId,
            @RequestBody UpdateTelemetryConfigRequest request) {
        accessGuard.requirePermission("telemetry:manage");
        TelemetryConfigEntity config = developerPortalService.updateTelemetryConfig(configId, request.updates, request.updatedBy);
        return ResponseEntity.ok(config);
    }

    /**
     * 激活遥测配置
     */
    @PostMapping("/telemetry-configs/{configId}/activate")
    public ResponseEntity<TelemetryConfigEntity> activateTelemetryConfig(
            @PathVariable String configId,
            @RequestBody ActivateRequest request) {
        accessGuard.requirePermission("telemetry:manage");
        TelemetryConfigEntity config = developerPortalService.activateTelemetryConfig(configId, request.activatedBy);
        return ResponseEntity.ok(config);
    }

    /**
     * 停用遥测配置
     */
    @PostMapping("/telemetry-configs/{configId}/deactivate")
    public ResponseEntity<TelemetryConfigEntity> deactivateTelemetryConfig(
            @PathVariable String configId,
            @RequestBody DeactivateRequest request) {
        accessGuard.requirePermission("telemetry:manage");
        TelemetryConfigEntity config = developerPortalService.deactivateTelemetryConfig(configId, request.deactivatedBy);
        return ResponseEntity.ok(config);
    }

    /**
     * 归档遥测配置
     */
    @PostMapping("/telemetry-configs/{configId}/archive")
    public ResponseEntity<TelemetryConfigEntity> archiveTelemetryConfig(
            @PathVariable String configId,
            @RequestBody ArchiveRequest request) {
        accessGuard.requirePermission("telemetry:manage");
        TelemetryConfigEntity config = developerPortalService.archiveTelemetryConfig(configId, request.archivedBy);
        return ResponseEntity.ok(config);
    }

    /**
     * 删除遥测配置
     */
    @DeleteMapping("/telemetry-configs/{configId}")
    public ResponseEntity<Void> deleteTelemetryConfig(
            @PathVariable String configId,
            @RequestBody DeleteRequest request) {
        accessGuard.requirePermission("telemetry:manage");
        developerPortalService.deleteTelemetryConfig(configId, request.deletedBy);
        return ResponseEntity.ok().build();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取SDK统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSDKStatistics() {
        accessGuard.requirePermission("sdkkey:read");
        Map<String, Object> stats = developerPortalService.getSDKStatistics();
        return ResponseEntity.ok(stats);
    }

    // ==================== 请求/响应类 ====================

    /**
     * 创建SDK密钥请求
     */
    public static class CreateSDKKeyRequest {
        public String gameId;
        public String environment;
        public String keyName;
        public SDKKeyEntity.SDKPlatform platform;
        public SDKKeyEntity.DeliveryMode deliveryMode;
        public Map<String, Object> config;
        public String createdBy;
    }

    /**
     * 更新SDK密钥请求
     */
    public static class UpdateSDKKeyRequest {
        public Map<String, Object> updates;
        public String updatedBy;
    }

    /**
     * 暂停请求
     */
    public static class SuspendRequest {
        public String suspendedBy;
    }

    /**
     * 激活请求
     */
    public static class ActivateRequest {
        public String activatedBy;
    }

    /**
     * 撤销请求
     */
    public static class RevokeRequest {
        public String revokedBy;
    }

    /**
     * 删除请求
     */
    public static class DeleteRequest {
        public String deletedBy;
    }

    /**
     * 创建SDK版本请求
     */
    public static class CreateSDKVersionRequest {
        public SDKVersionEntity.SDKPlatform platform;
        public String version;
        public SDKVersionEntity.ChangeType changeType;
        public String releaseNotes;
        public String changelog;
        public String createdBy;
    }

    /**
     * 发布版本请求
     */
    public static class ReleaseVersionRequest {
        public String downloadUrl;
        public String packageName;
        public String packageManager;
        public String checksumSha256;
        public Long fileSizeBytes;
        public String releasedBy;
    }

    /**
     * 弃用版本请求
     */
    public static class DeprecateVersionRequest {
        public String deprecationNotice;
        public String deprecatedBy;
    }

    /**
     * 退役请求
     */
    public static class RetireRequest {
        public String retiredBy;
    }

    /**
     * 创建遥测配置请求
     */
    public static class CreateTelemetryConfigRequest {
        public String gameId;
        public String environmentId;
        public String configName;
        public TelemetryConfigEntity.ConfigType configType;
        public String description;
        public boolean isDefault;
        public Map<String, Object> config;
        public String createdBy;
    }

    /**
     * 更新遥测配置请求
     */
    public static class UpdateTelemetryConfigRequest {
        public Map<String, Object> updates;
        public String updatedBy;
    }

    /**
     * 停用请求
     */
    public static class DeactivateRequest {
        public String deactivatedBy;
    }

    /**
     * 归档请求
     */
    public static class ArchiveRequest {
        public String archivedBy;
    }
}
