package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 维护管理服务
 * 管理维护窗口、功能开关和系统配置
 */
@Service
@Transactional
public class MaintenanceService {

    private static final Logger logger = LoggerFactory.getLogger(MaintenanceService.class);

    @Autowired
    private MaintenanceWindowRepo maintenanceWindowRepo;

    @Autowired
    private SystemConfigRepo systemConfigRepo;

    @Autowired
    private FeatureFlagRepo featureFlagRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    // ============== Maintenance Window Methods ==============

    /**
     * 创建维护窗口
     */
    public MaintenanceWindowEntity createMaintenanceWindow(String title, String description,
                                                          MaintenanceWindowEntity.MaintenanceType type,
                                                          MaintenanceWindowEntity.ImpactScope scope,
                                                          String gameId, String environmentId,
                                                          LocalDateTime scheduledStart, LocalDateTime scheduledEnd,
                                                          String createdBy) {

        MaintenanceWindowEntity window = new MaintenanceWindowEntity();
        window.id = "mw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        window.title = title;
        window.description = description;
        window.maintenanceType = type;
        window.impactScope = scope;
        window.gameId = gameId;
        window.environmentId = environmentId;
        window.scheduledStart = scheduledStart;
        window.scheduledEnd = scheduledEnd;
        window.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED;
        window.createdBy = createdBy;

        if (type == MaintenanceWindowEntity.MaintenanceType.EMERGENCY) {
            window.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.PENDING;
        }

        window = maintenanceWindowRepo.save(window);

        // 记录审计日志
        auditLogService.logCreate("maintenance_window", window.id, title, createdBy, createdBy, null,
            Map.of("type", type, "scope", scope, "scheduledStart", scheduledStart));

        logger.info("Created maintenance window: {} - {}", window.id, title);
        return window;
    }

    /**
     * 开始维护
     */
    public MaintenanceWindowEntity startMaintenance(String windowId) {
        MaintenanceWindowEntity window = maintenanceWindowRepo.findById(windowId)
            .orElseThrow(() -> new IllegalArgumentException("Maintenance window not found: " + windowId));

        if (!window.isPending()) {
            throw new IllegalStateException("Maintenance window is not pending: " + window.maintenanceStatus);
        }

        window.start();
        window = maintenanceWindowRepo.save(window);

        logger.info("Started maintenance: {} - {}", window.id, window.title);
        return window;
    }

    /**
     * 完成维护
     */
    public MaintenanceWindowEntity completeMaintenance(String windowId, String notes) {
        MaintenanceWindowEntity window = maintenanceWindowRepo.findById(windowId)
            .orElseThrow(() -> new IllegalArgumentException("Maintenance window not found: " + windowId));

        if (!window.isInProgress()) {
            throw new IllegalStateException("Maintenance window is not in progress: " + window.maintenanceStatus);
        }

        window.complete(notes);
        window = maintenanceWindowRepo.save(window);

        logger.info("Completed maintenance: {} - {}", window.id, window.title);
        return window;
    }

    /**
     * 取消维护
     */
    public MaintenanceWindowEntity cancelMaintenance(String windowId, String reason) {
        MaintenanceWindowEntity window = maintenanceWindowRepo.findById(windowId)
            .orElseThrow(() -> new IllegalArgumentException("Maintenance window not found: " + windowId));

        window.cancel(reason);
        window = maintenanceWindowRepo.save(window);

        logger.info("Cancelled maintenance: {} - {}", window.id, window.title);
        return window;
    }

    /**
     * 检查游戏是否在维护中
     */
    @Transactional(readOnly = true)
    public boolean isGameInMaintenance(String gameId) {
        List<MaintenanceWindowEntity> activeWindows = maintenanceWindowRepo.findActive();

        return activeWindows.stream().anyMatch(w -> w.affectsGame(gameId));
    }

    /**
     * 获取活跃的维护窗口
     */
    @Transactional(readOnly = true)
    public List<MaintenanceWindowEntity> getActiveMaintenances() {
        return maintenanceWindowRepo.findActive();
    }

    /**
     * 获取即将到来的维护
     */
    @Transactional(readOnly = true)
    public List<MaintenanceWindowEntity> getUpcomingMaintenances() {
        return maintenanceWindowRepo.findUpcoming(LocalDateTime.now());
    }

    // ============== System Config Methods ==============

    /**
     * 获取配置值
     */
    @Transactional(readOnly = true)
    public String getConfigValue(String configKey) {
        return systemConfigRepo.findByKey(configKey)
            .map(SystemConfigEntity::getRawValue)
            .orElse(null);
    }

    /**
     * 获取配置值（带默认值）
     */
    @Transactional(readOnly = true)
    public String getConfigValue(String configKey, String defaultValue) {
        return systemConfigRepo.findByKey(configKey)
            .map(SystemConfigEntity::getRawValue)
            .orElse(defaultValue);
    }

    /**
     * 设置配置值
     */
    public SystemConfigEntity setConfigValue(String configKey, String value, String modifiedBy) {
        SystemConfigEntity config = systemConfigRepo.findByKey(configKey)
            .orElseThrow(() -> new IllegalArgumentException("Config not found: " + configKey));

        if (config.isReadonly) {
            throw new IllegalStateException("Config is readonly: " + configKey);
        }

        String oldValue = config.getRawValue();
        config.setStringValue(value);
        config.lastModifiedBy = modifiedBy;
        config = systemConfigRepo.save(config);

        // 记录审计日志
        auditLogService.logUpdate("system_config", config.id, configKey, modifiedBy, modifiedBy, null,
            Map.of("oldValue", oldValue, "newValue", value));

        logger.info("Updated config: {} = {}", configKey, value);
        return config;
    }

    /**
     * 获取所有配置
     */
    @Transactional(readOnly = true)
    public List<SystemConfigEntity> getAllConfigs() {
        return systemConfigRepo.findAll().stream()
            .filter(c -> c.deletedAt == null)
            .collect(Collectors.toList());
    }

    /**
     * 获取公开配置
     */
    @Transactional(readOnly = true)
    public Map<String, String> getPublicConfigs() {
        return systemConfigRepo.findPublic().stream()
            .collect(Collectors.toMap(
                c -> c.configKey,
                SystemConfigEntity::getStringValue,
                (a, b) -> a
            ));
    }

    // ============== Feature Flag Methods ==============

    /**
     * 检查功能是否启用
     */
    @Transactional(readOnly = true)
    public boolean isFeatureEnabled(String flagKey, String userId, String gameId) {
        return featureFlagRepo.findByKey(flagKey)
            .map(ff -> ff.isAvailableForUser(userId, gameId))
            .orElse(false);
    }

    /**
     * 启用功能
     */
    public FeatureFlagEntity enableFeature(String flagKey, String modifiedBy) {
        FeatureFlagEntity flag = featureFlagRepo.findByKey(flagKey)
            .orElseThrow(() -> new IllegalArgumentException("Feature flag not found: " + flagKey));

        flag.enable();
        flag.lastModifiedBy = modifiedBy;
        flag = featureFlagRepo.save(flag);

        logger.info("Enabled feature: {}", flagKey);
        return flag;
    }

    /**
     * 禁用功能
     */
    public FeatureFlagEntity disableFeature(String flagKey, String modifiedBy) {
        FeatureFlagEntity flag = featureFlagRepo.findByKey(flagKey)
            .orElseThrow(() -> new IllegalArgumentException("Feature flag not found: " + flagKey));

        flag.disable();
        flag.lastModifiedBy = modifiedBy;
        flag = featureFlagRepo.save(flag);

        logger.info("Disabled feature: {}", flagKey);
        return flag;
    }

    /**
     * 设置功能百分比
     */
    public FeatureFlagEntity setFeaturePercentage(String flagKey, Integer percentage, String modifiedBy) {
        FeatureFlagEntity flag = featureFlagRepo.findByKey(flagKey)
            .orElseThrow(() -> new IllegalArgumentException("Feature flag not found: " + flagKey));

        flag.setPercentage(percentage);
        flag.lastModifiedBy = modifiedBy;
        flag = featureFlagRepo.save(flag);

        logger.info("Set feature percentage: {} = {}%", flagKey, percentage);
        return flag;
    }

    /**
     * 推进灰度：按 rolloutSteps 步进并回写百分比（末步 100 自动转 ENABLED）。
     * 仅灰度中且有步骤列表的开关可推进；步骤列表非法/越界时按实体契约仅递增不回写。
     */
    public FeatureFlagEntity advanceFeatureRollout(String flagKey, String modifiedBy) {
        FeatureFlagEntity flag = featureFlagRepo.findByKey(flagKey)
            .orElseThrow(() -> new IllegalArgumentException("Feature flag not found: " + flagKey));

        if (!flag.isStagedRollout()) {
            throw new IllegalArgumentException("Feature flag is not in staged rollout: " + flagKey);
        }
        if (flag.rolloutSteps == null || flag.rolloutSteps.isBlank()) {
            throw new IllegalArgumentException("Feature flag has no rollout steps: " + flagKey);
        }

        flag.advanceRollout();
        flag.lastModifiedBy = modifiedBy;
        flag = featureFlagRepo.save(flag);

        logger.info("Advanced feature flag rollout: {} -> step {}, {}%", flagKey, flag.currentStep, flag.percentageValue);
        return flag;
    }

    /**
     * 获取所有功能开关
     */
    @Transactional(readOnly = true)
    public List<FeatureFlagEntity> getAllFeatureFlags() {
        return featureFlagRepo.findAll().stream()
            .filter(f -> f.deletedAt == null)
            .collect(Collectors.toList());
    }

    /**
     * 获取启用的功能开关
     */
    @Transactional(readOnly = true)
    public List<FeatureFlagEntity> getEnabledFeatureFlags() {
        return featureFlagRepo.findEnabled();
    }

    // ============== Scheduled Tasks ==============

    /**
     * 定期检查待开始的维护
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void checkPendingMaintenances() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<MaintenanceWindowEntity> pending = maintenanceWindowRepo.findPendingUnnotified(now);

            for (MaintenanceWindowEntity window : pending) {
                logger.info("Maintenance window is pending: {} - {}", window.id, window.title);

                if (window.gameId != null && !window.gameId.isBlank()) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("event_type", WebhookService.EVENT_MAINTENANCE_UPCOMING);
                    payload.put("window_id", window.id);
                    payload.put("title", window.title);
                    payload.put("description", window.description);
                    payload.put("maintenance_type", window.maintenanceType != null ? window.maintenanceType.name() : null);
                    payload.put("impact_scope", window.impactScope != null ? window.impactScope.name() : null);
                    payload.put("game_id", window.gameId);
                    payload.put("environment_id", window.environmentId);
                    payload.put("scheduled_start", window.scheduledStart != null ? window.scheduledStart.toString() : null);
                    payload.put("scheduled_end", window.scheduledEnd != null ? window.scheduledEnd.toString() : null);
                    try {
                        webhookService.sendCustomWebhook(window.gameId, WebhookService.EVENT_MAINTENANCE_UPCOMING, payload);
                    } catch (Exception e) {
                        logger.warn("Maintenance upcoming webhook dispatch failed: {}", e.getMessage());
                    }
                }

                // 一次性守卫：已通知的窗口不再进入 findPendingUnnotified（状态机不动，窗口仍可手动开始）
                window.notificationSent = true;
                window.notificationSentAt = now;
                maintenanceWindowRepo.save(window);
            }

            if (!pending.isEmpty()) {
                logger.info("Found {} pending maintenance windows", pending.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check pending maintenances", e);
        }
    }

    /**
     * 定期检查应该结束的维护
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void checkEndingMaintenances() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<MaintenanceWindowEntity> shouldEnd = maintenanceWindowRepo.findShouldEndUnnotified(now);

            for (MaintenanceWindowEntity window : shouldEnd) {
                logger.warn("Maintenance window should end: {} - {}", window.id, window.title);

                if (window.gameId != null && !window.gameId.isBlank()) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("event_type", WebhookService.EVENT_MAINTENANCE_ENDING);
                    payload.put("window_id", window.id);
                    payload.put("title", window.title);
                    payload.put("description", window.description);
                    payload.put("maintenance_type", window.maintenanceType != null ? window.maintenanceType.name() : null);
                    payload.put("impact_scope", window.impactScope != null ? window.impactScope.name() : null);
                    payload.put("game_id", window.gameId);
                    payload.put("environment_id", window.environmentId);
                    payload.put("scheduled_start", window.scheduledStart != null ? window.scheduledStart.toString() : null);
                    payload.put("scheduled_end", window.scheduledEnd != null ? window.scheduledEnd.toString() : null);
                    payload.put("is_overdue", true);
                    try {
                        webhookService.sendCustomWebhook(window.gameId, WebhookService.EVENT_MAINTENANCE_ENDING, payload);
                    } catch (Exception e) {
                        logger.warn("Maintenance ending webhook dispatch failed: {}", e.getMessage());
                    }
                }

                window.endNotificationSent = true;
                maintenanceWindowRepo.save(window);
            }

            if (!shouldEnd.isEmpty()) {
                logger.warn("Found {} maintenance windows that should end", shouldEnd.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check ending maintenances", e);
        }
    }

    /**
     * 定期检查计划的功能开关
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void checkScheduledFeatureFlags() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 检查应该启用的开关
            List<FeatureFlagEntity> toEnable = featureFlagRepo.findScheduledToEnable(now);
            for (FeatureFlagEntity flag : toEnable) {
                flag.enable();
                flag.lastModifiedBy = "system";
                featureFlagRepo.save(flag);
                logger.info("Auto-enabled feature flag: {}", flag.flagKey);
            }

            // 检查应该禁用的开关
            List<FeatureFlagEntity> toDisable = featureFlagRepo.findScheduledToDisable(now);
            for (FeatureFlagEntity flag : toDisable) {
                flag.disable();
                flag.lastModifiedBy = "system";
                featureFlagRepo.save(flag);
                logger.info("Auto-disabled feature flag: {}", flag.flagKey);
            }

            // 检查过期的开关
            List<FeatureFlagEntity> expired = featureFlagRepo.findExpired(now);
            for (FeatureFlagEntity flag : expired) {
                flag.disable();
                flag.lastModifiedBy = "system";
                featureFlagRepo.save(flag);
                logger.info("Disabled expired feature flag: {}", flag.flagKey);
            }

            if (!toEnable.isEmpty() || !toDisable.isEmpty() || !expired.isEmpty()) {
                logger.info("Processed {} feature flag changes", toEnable.size() + toDisable.size() + expired.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check scheduled feature flags", e);
        }
    }

    /**
     * 获取系统状态摘要
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemStatus() {
        long activeMaintenances = maintenanceWindowRepo.findActive().size();
        long upcomingMaintenances = maintenanceWindowRepo.findUpcoming(LocalDateTime.now()).size();
        long enabledFeatures = featureFlagRepo.countByStatus(FeatureFlagEntity.FlagStatus.ENABLED);

        return Map.of(
            "activeMaintenances", activeMaintenances,
            "upcomingMaintenances", upcomingMaintenances,
            "enabledFeatures", enabledFeatures,
            "totalFeatures", featureFlagRepo.count(),
            "maintenanceMode", activeMaintenances > 0
        );
    }
}
