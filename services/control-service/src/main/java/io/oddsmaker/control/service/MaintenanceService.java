package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * 创建功能开关（全字段创建，补齐此前仅 enable/disable/percentage/advance 的面）。
     * 新建恒 DISABLED——启停与灰度走既有端点（enable/disable/percentage/advance），
     * 避免未配置 rolloutSteps 就带状态上线的半成品态。名单列以 JSON 数组落库
     * （成员判断而非子串匹配，见 FeatureFlagEntity.listContains）。
     */
    public FeatureFlagEntity createFeatureFlag(FeatureFlagCreate req) {
        if (req.flagKey == null || req.flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey is required");
        }
        if (req.flagName == null || req.flagName.isBlank()) {
            throw new IllegalArgumentException("flagName is required");
        }
        String key = req.flagKey.trim();
        if (featureFlagRepo.findByKey(key).isPresent()) {
            throw new IllegalStateException("Feature flag already exists: " + key);
        }

        FeatureFlagEntity flag = new FeatureFlagEntity();
        flag.flagKey = key;
        flag.flagName = req.flagName.trim();
        flag.description = req.description;
        flag.category = req.category;
        flag.owner = req.owner;
        flag.tags = req.tags;
        flag.flagStatus = FeatureFlagEntity.FlagStatus.DISABLED;
        flag.flagType = req.flagType != null ? req.flagType : FeatureFlagEntity.FlagType.BOOLEAN;
        flag.defaultValue = req.defaultValue != null ? req.defaultValue : false;
        flag.percentageValue = req.percentageValue != null
                ? Math.max(0, Math.min(100, req.percentageValue)) : 0;
        flag.whitelistUsers = toJsonList("whitelistUsers", req.whitelistUsers);
        flag.blacklistUsers = toJsonList("blacklistUsers", req.blacklistUsers);
        flag.whitelistGames = toJsonList("whitelistGames", req.whitelistGames);
        flag.blacklistGames = toJsonList("blacklistGames", req.blacklistGames);
        flag.conditions = validateJson("conditions", req.conditions);
        flag.rolloutSteps = validateJson("rolloutSteps", req.rolloutSteps);
        flag.createdBy = req.createdBy;
        flag = featureFlagRepo.save(flag);

        auditLogService.log(AuditLogEntity.AuditAction.CREATE, "feature_flag", flag.flagKey, flag.flagName,
                "create feature flag", AuditLogEntity.AuditResult.SUCCESS, req.createdBy,
                null, null, null, null,
                Map.of("flagType", flag.flagType.name(), "defaultValue", flag.defaultValue));

        logger.info("Created feature flag: {} ({})", flag.flagKey, flag.flagType);
        return flag;
    }

    /**
     * 更新功能开关（全字段、提供才写；名单空数组=清空，conditions/rolloutSteps 空串=清空）。
     * 不含启停与灰度——状态与百分比由 enable/disable/percentage/advance 端点独占，
     * 避免绕过 setPercentage 的状态联动。
     */
    public FeatureFlagEntity updateFeatureFlag(String flagKey, FeatureFlagUpdate req) {
        FeatureFlagEntity flag = featureFlagRepo.findByKey(flagKey)
                .orElseThrow(() -> new IllegalArgumentException("Feature flag not found: " + flagKey));

        List<String> changed = new ArrayList<>();
        if (req.flagName != null) {
            if (req.flagName.isBlank()) throw new IllegalArgumentException("flagName cannot be blank");
            flag.flagName = req.flagName.trim();
            changed.add("flagName");
        }
        if (req.description != null) { flag.description = req.description; changed.add("description"); }
        if (req.category != null) { flag.category = req.category; changed.add("category"); }
        if (req.owner != null) { flag.owner = req.owner; changed.add("owner"); }
        if (req.tags != null) { flag.tags = req.tags; changed.add("tags"); }
        if (req.flagType != null) { flag.flagType = req.flagType; changed.add("flagType"); }
        if (req.defaultValue != null) { flag.defaultValue = req.defaultValue; changed.add("defaultValue"); }
        if (req.whitelistUsers != null) { flag.whitelistUsers = toJsonList("whitelistUsers", req.whitelistUsers); changed.add("whitelistUsers"); }
        if (req.blacklistUsers != null) { flag.blacklistUsers = toJsonList("blacklistUsers", req.blacklistUsers); changed.add("blacklistUsers"); }
        if (req.whitelistGames != null) { flag.whitelistGames = toJsonList("whitelistGames", req.whitelistGames); changed.add("whitelistGames"); }
        if (req.blacklistGames != null) { flag.blacklistGames = toJsonList("blacklistGames", req.blacklistGames); changed.add("blacklistGames"); }
        if (req.conditions != null) {
            flag.conditions = req.conditions.isBlank() ? null : validateJson("conditions", req.conditions);
            changed.add("conditions");
        }
        if (req.rolloutSteps != null) {
            flag.rolloutSteps = req.rolloutSteps.isBlank() ? null : validateJson("rolloutSteps", req.rolloutSteps);
            changed.add("rolloutSteps");
        }
        if (req.scheduledEnableAt != null) { flag.scheduledEnableAt = req.scheduledEnableAt; changed.add("scheduledEnableAt"); }
        if (req.scheduledDisableAt != null) { flag.scheduledDisableAt = req.scheduledDisableAt; changed.add("scheduledDisableAt"); }
        if (req.expiryDate != null) { flag.expiryDate = req.expiryDate; changed.add("expiryDate"); }

        if (changed.isEmpty()) {
            throw new IllegalArgumentException("No updatable fields provided for: " + flagKey);
        }
        flag.lastModifiedBy = req.modifiedBy;
        flag = featureFlagRepo.save(flag);

        auditLogService.log(AuditLogEntity.AuditAction.UPDATE, "feature_flag", flag.flagKey, flag.flagName,
                "update feature flag: " + String.join(",", changed), AuditLogEntity.AuditResult.SUCCESS,
                req.modifiedBy, null, null, null, null, Map.of("changed", String.join(",", changed)));

        logger.info("Updated feature flag: {} fields={}", flagKey, changed);
        return flag;
    }

    /** 名单序列化为 JSON 数组文本（null 原样保留=不设置，空列表=[] 显式清空）。 */
    private String toJsonList(String field, List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize " + field, e);
        }
    }

    /** JSON 字段写前校验：null 原样、空白原样（清空语义在调用方），非空须为合法 JSON。 */
    private String validateJson(String field, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            JSON.readTree(value);
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(field + " is not valid JSON: " + e.getMessage());
        }
    }

    /** 创建请求体（controller 直接绑定；新建恒 DISABLED）。 */
    public static class FeatureFlagCreate {
        public String flagKey;
        public String flagName;
        public String description;
        public String category;
        public String owner;
        public String tags;
        public FeatureFlagEntity.FlagType flagType;
        public Boolean defaultValue;
        public Integer percentageValue;
        public List<String> whitelistUsers;
        public List<String> blacklistUsers;
        public List<String> whitelistGames;
        public List<String> blacklistGames;
        public String conditions;
        public String rolloutSteps;
        public String createdBy;
    }

    /** 更新请求体（提供才写；不含启停/灰度）。 */
    public static class FeatureFlagUpdate {
        public String flagName;
        public String description;
        public String category;
        public String owner;
        public String tags;
        public FeatureFlagEntity.FlagType flagType;
        public Boolean defaultValue;
        public List<String> whitelistUsers;
        public List<String> blacklistUsers;
        public List<String> whitelistGames;
        public List<String> blacklistGames;
        public String conditions;
        public String rolloutSteps;
        public java.time.LocalDateTime scheduledEnableAt;
        public java.time.LocalDateTime scheduledDisableAt;
        public java.time.LocalDateTime expiryDate;
        public String modifiedBy;
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
