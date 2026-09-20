package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 开发者门户服务
 * 管理SDK密钥、版本和遥测配置
 */
@Service
@Transactional
public class DeveloperPortalService {

    private static final Logger logger = LoggerFactory.getLogger(DeveloperPortalService.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private SDKKeyRepo sdkKeyRepo;

    @Autowired
    private SDKVersionRepo sdkVersionRepo;

    @Autowired
    private TelemetryConfigRepo telemetryConfigRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    // ==================== SDK密钥管理 ====================

    /**
     * 创建SDK密钥
     */
    public SDKKeyEntity createSDKKey(String gameId, String environment, String keyName,
                                      SDKKeyEntity.SDKPlatform platform, SDKKeyEntity.DeliveryMode deliveryMode,
                                      Map<String, Object> config, String createdBy) {

        SDKKeyEntity key = new SDKKeyEntity();
        key.id = "sdk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        key.gameId = gameId;
        key.environment = environment;
        key.keyName = keyName;
        key.platform = platform;
        key.deliveryMode = deliveryMode;
        key.publicKey = generatePublicKey();
        key.createdBy = createdBy;

        // 应用配置
        if (config != null) {
            applyKeyConfig(key, config);
        }

        key = sdkKeyRepo.save(key);

        auditLogService.logCreate("sdk_key", key.id, keyName, createdBy, createdBy, null,
            Map.of("gameId", gameId, "platform", platform, "deliveryMode", deliveryMode));

        logger.info("Created SDK key: {} for game: {} platform: {}", key.id, gameId, platform);
        return key;
    }

    /**
     * 获取SDK密钥
     */
    public SDKKeyEntity getSDKKey(String keyId) {
        return sdkKeyRepo.findById(keyId)
            .orElseThrow(() -> new IllegalArgumentException("SDK key not found: " + keyId));
    }

    /**
     * 获取游戏的SDK密钥
     */
    public List<SDKKeyEntity> getGameSDKKeys(String gameId, String environment) {
        if (environment != null) {
            return sdkKeyRepo.findByGameIdAndEnvironmentAndDeletedAtIsNull(gameId, environment);
        }
        return sdkKeyRepo.findByGameIdAndDeletedAtIsNull(gameId);
    }

    /**
     * 更新SDK密钥配置
     */
    public SDKKeyEntity updateSDKKey(String keyId, Map<String, Object> updates, String updatedBy) {
        SDKKeyEntity key = getSDKKey(keyId);

        if (updates.containsKey("keyName")) {
            key.keyName = (String) updates.get("keyName");
        }
        if (updates.containsKey("deliveryMode")) {
            key.deliveryMode = SDKKeyEntity.DeliveryMode.valueOf((String) updates.get("deliveryMode"));
        }

        applyKeyConfig(key, updates);

        key = sdkKeyRepo.save(key);

        auditLogService.logUpdate("sdk_key", key.id, key.keyName, updatedBy, updatedBy, null,
            Map.of("updates", updates.keySet()));

        return key;
    }

    /**
     * 暂停SDK密钥
     */
    public SDKKeyEntity suspendSDKKey(String keyId, String suspendedBy) {
        SDKKeyEntity key = getSDKKey(keyId);
        key.suspend();
        key = sdkKeyRepo.save(key);

        auditLogService.logUpdate("sdk_key", key.id, key.keyName, suspendedBy, suspendedBy, null,
            Map.of("action", "suspend"));

        logger.info("Suspended SDK key: {}", keyId);
        return key;
    }

    /**
     * 激活SDK密钥
     */
    public SDKKeyEntity activateSDKKey(String keyId, String activatedBy) {
        SDKKeyEntity key = getSDKKey(keyId);
        key.activate();
        key = sdkKeyRepo.save(key);

        auditLogService.logUpdate("sdk_key", key.id, key.keyName, activatedBy, activatedBy, null,
            Map.of("action", "activate"));

        logger.info("Activated SDK key: {}", keyId);
        return key;
    }

    /**
     * 撤销SDK密钥
     */
    public SDKKeyEntity revokeSDKKey(String keyId, String revokedBy) {
        SDKKeyEntity key = getSDKKey(keyId);
        key.revoke();
        key = sdkKeyRepo.save(key);

        auditLogService.logUpdate("sdk_key", key.id, key.keyName, revokedBy, revokedBy, null,
            Map.of("action", "revoke"));

        logger.info("Revoked SDK key: {}", keyId);
        return key;
    }

    /**
     * 删除SDK密钥
     */
    public void deleteSDKKey(String keyId, String deletedBy) {
        SDKKeyEntity key = getSDKKey(keyId);
        key.deletedAt = LocalDateTime.now();
        sdkKeyRepo.save(key);

        auditLogService.logDelete("sdk_key", key.id, key.keyName, deletedBy, deletedBy, null);

        logger.info("Deleted SDK key: {}", keyId);
    }

    /**
     * 验证SDK密钥
     */
    public boolean validateSDKKey(String publicKey, String gameId, String environment) {
        Optional<SDKKeyEntity> keyOpt = sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull(publicKey);

        if (keyOpt.isEmpty()) {
            return false;
        }

        SDKKeyEntity key = keyOpt.get();

        // 检查密钥是否活跃
        if (!key.isActive()) {
            return false;
        }

        // 检查游戏ID匹配
        if (!key.gameId.equals(gameId)) {
            return false;
        }

        // 检查环境匹配
        if (key.environment != null && !key.environment.equals(environment)) {
            return false;
        }

        return true;
    }

    // ==================== SDK版本管理 ====================

    /**
     * 创建SDK版本
     */
    public SDKVersionEntity createSDKVersion(SDKVersionEntity.SDKPlatform platform, String version,
                                              SDKVersionEntity.ChangeType changeType,
                                              String releaseNotes, String changelog,
                                              String createdBy) {

        // 检查版本唯一性
        if (sdkVersionRepo.existsByPlatformAndVersion(platform, version)) {
            throw new IllegalArgumentException("SDK version already exists: " + version);
        }

        SDKVersionEntity sdkVersion = new SDKVersionEntity();
        sdkVersion.id = "sdkver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        sdkVersion.platform = platform;
        sdkVersion.version = version;
        sdkVersion.changeType = changeType;
        sdkVersion.releaseNotes = releaseNotes;
        sdkVersion.changelog = changelog;
        sdkVersion.createdBy = createdBy;

        sdkVersion = sdkVersionRepo.save(sdkVersion);

        auditLogService.logCreate("sdk_version", sdkVersion.id, version, createdBy, createdBy, null,
            Map.of("platform", platform, "changeType", changeType));

        logger.info("Created SDK version: {} for platform: {}", version, platform);
        return sdkVersion;
    }

    /**
     * 获取SDK版本
     */
    public SDKVersionEntity getSDKVersion(String versionId) {
        return sdkVersionRepo.findById(versionId)
            .orElseThrow(() -> new IllegalArgumentException("SDK version not found: " + versionId));
    }

    /**
     * 获取平台的版本列表
     */
    public List<SDKVersionEntity> getPlatformVersions(SDKVersionEntity.SDKPlatform platform, String status) {
        if (status != null) {
            SDKVersionEntity.VersionStatus versionStatus = SDKVersionEntity.VersionStatus.valueOf(status);
            return sdkVersionRepo.findByPlatformAndVersionStatusOrderByCreatedAtDesc(platform, versionStatus);
        }
        return sdkVersionRepo.findByPlatformOrderByCreatedAtDesc(platform);
    }

    /**
     * 获取最新版本
     */
    public SDKVersionEntity getLatestVersion(SDKVersionEntity.SDKPlatform platform) {
        return sdkVersionRepo.findLatestByPlatform(platform)
            .orElseThrow(() -> new IllegalArgumentException("No released version found for platform: " + platform));
    }

    /**
     * 发布版本
     */
    public SDKVersionEntity releaseVersion(String versionId, String downloadUrl, String packageName,
                                            String packageManager, String checksumSha256,
                                            Long fileSizeBytes, String releasedBy) {
        SDKVersionEntity sdkVersion = getSDKVersion(versionId);

        if (sdkVersion.isReleased()) {
            throw new IllegalStateException("Version is already released");
        }

        sdkVersion.downloadUrl = downloadUrl;
        sdkVersion.packageName = packageName;
        sdkVersion.packageManager = packageManager;
        sdkVersion.checksumSha256 = checksumSha256;
        sdkVersion.fileSizeBytes = fileSizeBytes;
        sdkVersion.release(releasedBy);

        sdkVersion = sdkVersionRepo.save(sdkVersion);

        auditLogService.logUpdate("sdk_version", sdkVersion.id, sdkVersion.version, releasedBy, releasedBy, null,
            Map.of("action", "release"));

        logger.info("Released SDK version: {} for platform: {}", sdkVersion.version, sdkVersion.platform);
        return sdkVersion;
    }

    /**
     * 弃用版本
     */
    public SDKVersionEntity deprecateVersion(String versionId, String deprecationNotice, String deprecatedBy) {
        SDKVersionEntity sdkVersion = getSDKVersion(versionId);

        if (!sdkVersion.isReleased()) {
            throw new IllegalStateException("Only released versions can be deprecated");
        }

        sdkVersion.deprecate(deprecationNotice);
        sdkVersion = sdkVersionRepo.save(sdkVersion);

        auditLogService.logUpdate("sdk_version", sdkVersion.id, sdkVersion.version, deprecatedBy, deprecatedBy, null,
            Map.of("action", "deprecate"));

        logger.info("Deprecated SDK version: {}", sdkVersion.version);
        return sdkVersion;
    }

    /**
     * 退役版本
     */
    public SDKVersionEntity retireVersion(String versionId, String retiredBy) {
        SDKVersionEntity sdkVersion = getSDKVersion(versionId);

        if (!sdkVersion.isDeprecated()) {
            throw new IllegalStateException("Only deprecated versions can be retired");
        }

        sdkVersion.retire();
        sdkVersion = sdkVersionRepo.save(sdkVersion);

        auditLogService.logUpdate("sdk_version", sdkVersion.id, sdkVersion.version, retiredBy, retiredBy, null,
            Map.of("action", "retire"));

        logger.info("Retired SDK version: {}", sdkVersion.version);
        return sdkVersion;
    }

    /**
     * 记录下载
     */
    public void recordDownload(String versionId) {
        SDKVersionEntity sdkVersion = getSDKVersion(versionId);
        sdkVersion.recordDownload();
        sdkVersionRepo.save(sdkVersion);
    }

    /**
     * 更新活跃安装量
     */
    public void updateActiveInstallations(String versionId, long count) {
        SDKVersionEntity sdkVersion = getSDKVersion(versionId);
        sdkVersion.updateActiveInstallations(count);
        sdkVersionRepo.save(sdkVersion);
    }

    /**
     * 获取SDK统计信息
     */
    public Map<String, Object> getSDKStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // 密钥统计
        List<Object[]> keyPlatformStats = sdkKeyRepo.countByPlatform();
        Map<String, Long> keysByPlatform = new HashMap<>();
        for (Object[] row : keyPlatformStats) {
            keysByPlatform.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("keysByPlatform", keysByPlatform);

        List<Object[]> keyStatusStats = sdkKeyRepo.countByStatus();
        Map<String, Long> keysByStatus = new HashMap<>();
        for (Object[] row : keyStatusStats) {
            keysByStatus.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("keysByStatus", keysByStatus);

        // 版本统计
        List<Object[]> versionPlatformStats = sdkVersionRepo.countByPlatform();
        Map<String, Long> versionsByPlatform = new HashMap<>();
        for (Object[] row : versionPlatformStats) {
            versionsByPlatform.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("versionsByPlatform", versionsByPlatform);

        List<Object[]> versionStatusStats = sdkVersionRepo.countByStatus();
        Map<String, Long> versionsByStatus = new HashMap<>();
        for (Object[] row : versionStatusStats) {
            versionsByStatus.put(row[0].toString(), (Long) row[1]);
        }
        stats.put("versionsByStatus", versionsByStatus);

        // 最新版本
        for (SDKVersionEntity.SDKPlatform platform : SDKVersionEntity.SDKPlatform.values()) {
            try {
                SDKVersionEntity latest = getLatestVersion(platform);
                stats.put("latest_" + platform.name(), Map.of(
                    "version", latest.version,
                    "releasedAt", latest.releasedAt,
                    "totalDownloads", latest.totalDownloads
                ));
            } catch (Exception e) {
                // 没有该平台的版本
            }
        }

        return stats;
    }

    // ==================== 遥测配置管理 ====================

    /**
     * 创建遥测配置
     */
    public TelemetryConfigEntity createTelemetryConfig(String gameId, String environmentId,
                                                        String configName, TelemetryConfigEntity.ConfigType configType,
                                                        String description, boolean isDefault,
                                                        Map<String, Object> config, String createdBy) {

        TelemetryConfigEntity telemetryConfig = new TelemetryConfigEntity();
        telemetryConfig.id = "telem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        telemetryConfig.gameId = gameId;
        telemetryConfig.environmentId = environmentId;
        telemetryConfig.configName = configName;
        telemetryConfig.configType = configType;
        telemetryConfig.description = description;
        telemetryConfig.isDefault = isDefault;
        telemetryConfig.createdBy = createdBy;

        // 应用配置
        if (config != null) {
            applyTelemetryConfig(telemetryConfig, config);
        }

        telemetryConfig = telemetryConfigRepo.save(telemetryConfig);

        auditLogService.logCreate("telemetry_config", telemetryConfig.id, configName, createdBy, createdBy, null,
            Map.of("gameId", gameId, "configType", configType));

        logger.info("Created telemetry config: {} for game: {}", telemetryConfig.id, gameId);
        return telemetryConfig;
    }

    /**
     * 获取遥测配置
     */
    public TelemetryConfigEntity getTelemetryConfig(String configId) {
        return telemetryConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("Telemetry config not found: " + configId));
    }

    /**
     * 获取游戏的遥测配置
     */
    public List<TelemetryConfigEntity> getGameTelemetryConfigs(String gameId, String environmentId) {
        if (environmentId != null) {
            return telemetryConfigRepo.findByGameIdAndEnvironmentIdAndDeletedAtIsNull(gameId, environmentId);
        }
        return telemetryConfigRepo.findByGameIdAndDeletedAtIsNull(gameId);
    }

    /**
     * 获取有效的遥测配置
     */
    public TelemetryConfigEntity getEffectiveConfig(String gameId, String environmentId,
                                                     TelemetryConfigEntity.ConfigType configType) {
        // 先查找游戏特定配置
        List<TelemetryConfigEntity> configs = telemetryConfigRepo.findActiveByGameIdAndEnvironmentIdAndType(
            gameId, environmentId, configType);

        if (!configs.isEmpty()) {
            return configs.get(0); // 返回最高优先级的配置
        }

        // 查找游戏级别配置
        configs = telemetryConfigRepo.findActiveByGameIdAndType(gameId, configType);

        if (!configs.isEmpty()) {
            return configs.get(0);
        }

        // 查找全局默认配置
        List<TelemetryConfigEntity> globalConfigs = telemetryConfigRepo.findActiveGlobalConfigs();
        for (TelemetryConfigEntity config : globalConfigs) {
            if (config.configType == configType) {
                return config;
            }
        }

        // 返回默认配置
        return getDefaultConfig(configType);
    }

    /**
     * 更新遥测配置
     */
    public TelemetryConfigEntity updateTelemetryConfig(String configId, Map<String, Object> updates, String updatedBy) {
        TelemetryConfigEntity config = getTelemetryConfig(configId);

        if (updates.containsKey("configName")) {
            config.configName = (String) updates.get("configName");
        }
        if (updates.containsKey("description")) {
            config.description = (String) updates.get("description");
        }
        if (updates.containsKey("isDefault")) {
            config.isDefault = (Boolean) updates.get("isDefault");
        }
        if (updates.containsKey("priority")) {
            config.priority = (Integer) updates.get("priority");
        }

        applyTelemetryConfig(config, updates);

        config = telemetryConfigRepo.save(config);

        auditLogService.logUpdate("telemetry_config", config.id, config.configName, updatedBy, updatedBy, null,
            Map.of("updates", updates.keySet()));

        return config;
    }

    /**
     * 激活遥测配置
     */
    public TelemetryConfigEntity activateTelemetryConfig(String configId, String activatedBy) {
        TelemetryConfigEntity config = getTelemetryConfig(configId);
        config.activate();
        config = telemetryConfigRepo.save(config);

        auditLogService.logUpdate("telemetry_config", config.id, config.configName, activatedBy, activatedBy, null,
            Map.of("action", "activate"));

        return config;
    }

    /**
     * 停用遥测配置
     */
    public TelemetryConfigEntity deactivateTelemetryConfig(String configId, String deactivatedBy) {
        TelemetryConfigEntity config = getTelemetryConfig(configId);
        config.deactivate();
        config = telemetryConfigRepo.save(config);

        auditLogService.logUpdate("telemetry_config", config.id, config.configName, deactivatedBy, deactivatedBy, null,
            Map.of("action", "deactivate"));

        return config;
    }

    /**
     * 归档遥测配置
     */
    public TelemetryConfigEntity archiveTelemetryConfig(String configId, String archivedBy) {
        TelemetryConfigEntity config = getTelemetryConfig(configId);
        config.archive();
        config = telemetryConfigRepo.save(config);

        auditLogService.logUpdate("telemetry_config", config.id, config.configName, archivedBy, archivedBy, null,
            Map.of("action", "archive"));

        return config;
    }

    /**
     * 删除遥测配置
     */
    public void deleteTelemetryConfig(String configId, String deletedBy) {
        TelemetryConfigEntity config = getTelemetryConfig(configId);
        config.deletedAt = LocalDateTime.now();
        telemetryConfigRepo.save(config);

        auditLogService.logDelete("telemetry_config", config.id, config.configName, deletedBy, deletedBy, null);
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成公钥
     */
    private String generatePublicKey() {
        return "pk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    /**
     * 应用密钥配置
     */
    private void applyKeyConfig(SDKKeyEntity key, Map<String, Object> config) {
        if (config.containsKey("rateLimitRpm")) {
            key.rateLimitRpm = (Integer) config.get("rateLimitRpm");
        }
        if (config.containsKey("rateLimitRps")) {
            key.rateLimitRps = (Integer) config.get("rateLimitRps");
        }
        if (config.containsKey("batchSizeLimit")) {
            key.batchSizeLimit = (Integer) config.get("batchSizeLimit");
        }
        if (config.containsKey("batchIntervalMs")) {
            key.batchIntervalMs = (Integer) config.get("batchIntervalMs");
        }
        if (config.containsKey("maxEventSizeBytes")) {
            key.maxEventSizeBytes = (Integer) config.get("maxEventSizeBytes");
        }
        if (config.containsKey("maxBatchSizeBytes")) {
            key.maxBatchSizeBytes = (Integer) config.get("maxBatchSizeBytes");
        }
        if (config.containsKey("enableCompression")) {
            key.enableCompression = (Boolean) config.get("enableCompression");
        }
        if (config.containsKey("enableEncryption")) {
            key.enableEncryption = (Boolean) config.get("enableEncryption");
        }
        if (config.containsKey("sdkVersionConstraint")) {
            key.sdkVersionConstraint = (String) config.get("sdkVersionConstraint");
        }
        if (config.containsKey("minSdkVersion")) {
            key.minSdkVersion = (String) config.get("minSdkVersion");
        }
        if (config.containsKey("maxSdkVersion")) {
            key.maxSdkVersion = (String) config.get("maxSdkVersion");
        }

        try {
            if (config.containsKey("allowedDomains")) {
                key.allowedDomains = objectMapper.writeValueAsString(config.get("allowedDomains"));
            }
            if (config.containsKey("allowedIps")) {
                key.allowedIps = objectMapper.writeValueAsString(config.get("allowedIps"));
            }
            if (config.containsKey("retryPolicy")) {
                key.retryPolicy = objectMapper.writeValueAsString(config.get("retryPolicy"));
            }
            if (config.containsKey("offlineConfig")) {
                key.offlineConfig = objectMapper.writeValueAsString(config.get("offlineConfig"));
            }
            if (config.containsKey("flushConfig")) {
                key.flushConfig = objectMapper.writeValueAsString(config.get("flushConfig"));
            }
            if (config.containsKey("telemetryConfig")) {
                key.telemetryConfig = objectMapper.writeValueAsString(config.get("telemetryConfig"));
            }
            if (config.containsKey("customConfig")) {
                key.customConfig = objectMapper.writeValueAsString(config.get("customConfig"));
            }
        } catch (Exception e) {
            logger.error("Failed to serialize config", e);
        }
    }

    /**
     * 应用遥测配置
     */
    private void applyTelemetryConfig(TelemetryConfigEntity config, Map<String, Object> updates) {
        if (updates.containsKey("deliveryMode")) {
            config.deliveryMode = (String) updates.get("deliveryMode");
        }
        if (updates.containsKey("batchSize")) {
            config.batchSize = (Integer) updates.get("batchSize");
        }
        if (updates.containsKey("batchIntervalMs")) {
            config.batchIntervalMs = (Integer) updates.get("batchIntervalMs");
        }
        if (updates.containsKey("maxQueueSize")) {
            config.maxQueueSize = (Integer) updates.get("maxQueueSize");
        }
        if (updates.containsKey("flushOnBackground")) {
            config.flushOnBackground = (Boolean) updates.get("flushOnBackground");
        }
        if (updates.containsKey("flushOnAppClose")) {
            config.flushOnAppClose = (Boolean) updates.get("flushOnAppClose");
        }
        if (updates.containsKey("enableCompression")) {
            config.enableCompression = (Boolean) updates.get("enableCompression");
        }
        if (updates.containsKey("compressionAlgorithm")) {
            config.compressionAlgorithm = (String) updates.get("compressionAlgorithm");
        }
        if (updates.containsKey("compressionLevel")) {
            config.compressionLevel = (Integer) updates.get("compressionLevel");
        }
        if (updates.containsKey("compressionThresholdBytes")) {
            config.compressionThresholdBytes = (Integer) updates.get("compressionThresholdBytes");
        }
        if (updates.containsKey("enableEncryption")) {
            config.enableEncryption = (Boolean) updates.get("enableEncryption");
        }
        if (updates.containsKey("encryptionAlgorithm")) {
            config.encryptionAlgorithm = (String) updates.get("encryptionAlgorithm");
        }
        if (updates.containsKey("encryptionKeyId")) {
            config.encryptionKeyId = (String) updates.get("encryptionKeyId");
        }
        if (updates.containsKey("maxRetries")) {
            config.maxRetries = (Integer) updates.get("maxRetries");
        }
        if (updates.containsKey("retryIntervalMs")) {
            config.retryIntervalMs = (Integer) updates.get("retryIntervalMs");
        }
        if (updates.containsKey("retryBackoffMultiplier")) {
            config.retryBackoffMultiplier = (Double) updates.get("retryBackoffMultiplier");
        }
        if (updates.containsKey("maxRetryIntervalMs")) {
            config.maxRetryIntervalMs = (Integer) updates.get("maxRetryIntervalMs");
        }
        if (updates.containsKey("retryOnStatusCodes")) {
            config.retryOnStatusCodes = (String) updates.get("retryOnStatusCodes");
        }
        if (updates.containsKey("enableOfflineStorage")) {
            config.enableOfflineStorage = (Boolean) updates.get("enableOfflineStorage");
        }
        if (updates.containsKey("offlineStorageMaxMb")) {
            config.offlineStorageMaxMb = (Integer) updates.get("offlineStorageMaxMb");
        }
        if (updates.containsKey("offlineStorageTtlHours")) {
            config.offlineStorageTtlHours = (Integer) updates.get("offlineStorageTtlHours");
        }
        if (updates.containsKey("offlineBatchSize")) {
            config.offlineBatchSize = (Integer) updates.get("offlineBatchSize");
        }
        if (updates.containsKey("enableTelemetry")) {
            config.enableTelemetry = (Boolean) updates.get("enableTelemetry");
        }
        if (updates.containsKey("telemetryIntervalMs")) {
            config.telemetryIntervalMs = (Integer) updates.get("telemetryIntervalMs");
        }
        if (updates.containsKey("reportErrors")) {
            config.reportErrors = (Boolean) updates.get("reportErrors");
        }
        if (updates.containsKey("reportPerformance")) {
            config.reportPerformance = (Boolean) updates.get("reportPerformance");
        }
        if (updates.containsKey("sampleRate")) {
            config.sampleRate = (Double) updates.get("sampleRate");
        }
        if (updates.containsKey("connectionTimeoutMs")) {
            config.connectionTimeoutMs = (Integer) updates.get("connectionTimeoutMs");
        }
        if (updates.containsKey("readTimeoutMs")) {
            config.readTimeoutMs = (Integer) updates.get("readTimeoutMs");
        }
        if (updates.containsKey("writeTimeoutMs")) {
            config.writeTimeoutMs = (Integer) updates.get("writeTimeoutMs");
        }

        try {
            if (updates.containsKey("customConfig")) {
                config.customConfig = objectMapper.writeValueAsString(updates.get("customConfig"));
            }
        } catch (Exception e) {
            logger.error("Failed to serialize custom config", e);
        }
    }

    /**
     * 获取默认配置
     */
    private TelemetryConfigEntity getDefaultConfig(TelemetryConfigEntity.ConfigType configType) {
        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.configType = configType;
        config.isDefault = true;

        // 设置默认值
        config.deliveryMode = "REALTIME";
        config.batchSize = 500;
        config.batchIntervalMs = 3000;
        config.maxQueueSize = 10000;
        config.flushOnBackground = true;
        config.flushOnAppClose = true;
        config.enableCompression = true;
        config.compressionAlgorithm = "GZIP";
        config.compressionLevel = 6;
        config.compressionThresholdBytes = 1024;
        config.enableEncryption = false;
        config.maxRetries = 3;
        config.retryIntervalMs = 1000;
        config.retryBackoffMultiplier = 2.0;
        config.maxRetryIntervalMs = 30000;
        config.enableOfflineStorage = true;
        config.offlineStorageMaxMb = 50;
        config.offlineStorageTtlHours = 72;
        config.offlineBatchSize = 100;
        config.enableTelemetry = true;
        config.telemetryIntervalMs = 60000;
        config.reportErrors = true;
        config.reportPerformance = true;
        config.sampleRate = 1.0;
        config.connectionTimeoutMs = 10000;
        config.readTimeoutMs = 30000;
        config.writeTimeoutMs = 30000;

        return config;
    }

    // ==================== 定时任务 ====================

    /**
     * 清理过期的SDK密钥
     */
    @Scheduled(cron = "0 0 1 * * ?")  // 每天凌晨1点
    public void cleanupExpiredKeys() {
        List<SDKKeyEntity> expiredKeys = sdkKeyRepo.findExpired();

        for (SDKKeyEntity key : expiredKeys) {
            key.expire();
            sdkKeyRepo.save(key);
            logger.info("Expired SDK key: {}", key.id);
        }
    }

    /**
     * 检查即将退役的SDK版本
     */
    @Scheduled(cron = "0 0 9 * * ?")  // 每天上午9点
    public void checkRetiringVersions() {
        LocalDateTime threshold = LocalDateTime.now().plusDays(30);
        List<SDKVersionEntity> retiringSoon = sdkVersionRepo.findRetiringSoon(threshold);

        for (SDKVersionEntity version : retiringSoon) {
            logger.warn("SDK version retiring soon: {} - {} (platform: {})",
                version.version, version.retirementDate, version.platform);

            // SDK 版本无游戏维度，走 DEFAULT 平台级配置（game 需 V0.4.1 seed 保证 FK）
            long daysUntilRetiring = version.retirementDate != null
                ? java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), version.retirementDate) : -1;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event_type", WebhookService.EVENT_SDK_VERSION_RETIRING);
            payload.put("version", version.version);
            payload.put("platform", version.platform != null ? version.platform.name() : null);
            payload.put("version_status", version.versionStatus != null ? version.versionStatus.name() : null);
            payload.put("retirement_date", version.retirementDate != null ? version.retirementDate.toString() : null);
            payload.put("days_until_retiring", daysUntilRetiring);
            try {
                webhookService.sendCustomWebhook("DEFAULT", WebhookService.EVENT_SDK_VERSION_RETIRING, payload);
            } catch (Exception e) {
                logger.warn("SDK version retiring webhook dispatch failed: {}", e.getMessage());
            }
        }
    }
}
