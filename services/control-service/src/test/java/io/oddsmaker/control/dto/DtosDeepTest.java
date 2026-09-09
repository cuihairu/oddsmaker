package io.oddsmaker.control.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 深度字段映射测试：覆盖 fromEntity/toEntity/updateEntity 默认值填充与业务判定方法。
 */
@DisplayName("DTO 字段映射深度覆盖")
class DtosDeepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final LocalDateTime T = LocalDateTime.of(2026, 3, 4, 5, 6);

    @Test
    @DisplayName("UserDTO：fromEntity/toEntity/updateEntity 映射、默认值与角色判定")
    void userDtoMappings() {
        UserEntity e = new UserEntity();
        e.id = "u-1";
        e.email = "u@x.c";
        e.name = "王五";
        e.displayName = "五哥";
        e.avatarUrl = "http://av";
        e.globalRole = UserEntity.GlobalRole.SUPER_ADMIN;
        e.status = UserEntity.UserStatus.LOCKED;
        e.company = "OM";
        e.title = "SRE";
        e.phone = "+86-138-0000-0000";
        e.timeZone = "Asia/Shanghai";
        e.locale = "zh-CN";
        e.notificationEmail = false;
        e.notificationSms = true;
        e.dashboardTheme = "dark";
        e.emailVerified = true;
        e.twoFactorEnabled = true;
        e.loginAttempts = 5;
        e.lastLogin = T;
        e.lastLoginIp = "192.168.1.1";
        e.createdAt = T;
        e.updatedAt = T;

        UserDTO dto = new UserDTO(e);
        assertEquals("u-1", dto.id);
        assertEquals("u@x.c", dto.email);
        assertEquals("王五", dto.name);
        assertEquals("五哥", dto.displayName);
        assertEquals("http://av", dto.avatarUrl);
        assertEquals(UserEntity.GlobalRole.SUPER_ADMIN, dto.globalRole);
        assertEquals(UserEntity.UserStatus.LOCKED, dto.status);
        assertEquals("OM", dto.company);
        assertEquals("SRE", dto.title);
        assertEquals("+86-138-0000-0000", dto.phone);
        assertEquals("Asia/Shanghai", dto.timeZone);
        assertEquals("zh-CN", dto.locale);
        assertEquals(Boolean.FALSE, dto.notificationEmail);
        assertEquals(Boolean.TRUE, dto.notificationSms);
        assertEquals("dark", dto.dashboardTheme);
        assertEquals(Boolean.TRUE, dto.emailVerified);
        assertEquals(Boolean.TRUE, dto.twoFactorEnabled);
        assertEquals(5, dto.loginAttempts);
        assertEquals(T, dto.lastLogin);
        assertEquals("192.168.1.1", dto.lastLoginIp);
        assertEquals(Boolean.TRUE, dto.isLocked);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);

        UserEntity back = dto.toEntity();
        assertEquals("u-1", back.id);
        assertEquals("u@x.c", back.email);
        assertEquals("王五", back.name);
        assertEquals(UserEntity.GlobalRole.SUPER_ADMIN, back.globalRole);
        assertEquals(UserEntity.UserStatus.LOCKED, back.status);
        assertEquals(Boolean.FALSE, back.notificationEmail);
        assertEquals("Asia/Shanghai", back.timeZone);

        UserDTO empty = new UserDTO();
        UserEntity def = empty.toEntity();
        assertEquals(UserEntity.GlobalRole.USER, def.globalRole);
        assertEquals(UserEntity.UserStatus.ACTIVE, def.status);
        assertEquals("UTC", def.timeZone);
        assertEquals("en-US", def.locale);
        assertEquals(Boolean.TRUE, def.notificationEmail);
        assertEquals(Boolean.FALSE, def.notificationSms);
        assertEquals("light", def.dashboardTheme);

        UserEntity target = new UserEntity();
        UserDTO patch = new UserDTO();
        patch.email = "new@x.c";
        patch.updateEntity(target);
        assertEquals("new@x.c", target.email);
        assertNull(target.name);

        assertTrue(dto.isSuperAdmin());
        assertTrue(dto.isOperator());
        assertFalse(dto.isActive());
        assertEquals("五哥", dto.getDisplayName());
        assertTrue(dto.isEmailVerified());
        assertTrue(dto.isTwoFactorEnabled());

        UserDTO plain = new UserDTO();
        plain.globalRole = UserEntity.GlobalRole.USER;
        assertFalse(plain.isOperator());
        UserDTO op = new UserDTO();
        op.globalRole = UserEntity.GlobalRole.OPERATOR;
        assertTrue(op.isOperator());
        op.status = UserEntity.UserStatus.ACTIVE;
        assertTrue(op.isActive());

        UserDTO fallback = new UserDTO();
        fallback.email = "e@x.c";
        assertEquals("e@x.c", fallback.getDisplayName());
        fallback.name = "李四";
        assertEquals("李四", fallback.getDisplayName());
        fallback.displayName = "   ";
        assertEquals("李四", fallback.getDisplayName());

        dto.roles = List.of(Map.of("role", "ADMIN", "gameId", "g-1"));
        assertTrue(dto.hasRole("ADMIN"));
        assertFalse(dto.hasRole("VIEWER"));
        assertTrue(dto.hasRoleInGame("g-1"));
        assertFalse(dto.hasRoleInGame("g-2"));
        assertFalse(new UserDTO().hasRole("ADMIN"));
        assertFalse(new UserDTO().hasRoleInGame("g-1"));
        assertFalse(new UserDTO().isEmailVerified());
        assertFalse(new UserDTO().isTwoFactorEnabled());
    }

    @Test
    @DisplayName("GameDTO：fromEntity/toEntity/updateEntity 映射、默认值与状态判定")
    void gameDtoMappings() {
        GameEntity e = new GameEntity();
        e.id = "g-1";
        e.name = "星辰";
        e.displayName = "星辰物语";
        e.description = "desc";
        e.genre = GameEntity.GameGenre.MMORPG;
        e.platforms = Set.of(GameEntity.GamePlatform.PC, GameEntity.GamePlatform.MOBILE);
        e.status = GameEntity.GameStatus.LIVE;
        e.currentVersion = "1.2.3";
        e.minSupportedVersion = "1.0.0";
        e.releaseDate = T;
        e.appStoreUrl = "https://app";
        e.googlePlayUrl = "https://gp";
        e.steamUrl = "https://st";
        e.defaultCurrency = "CNY";
        e.defaultTimezone = "Asia/Shanghai";
        e.virtualCurrencies = "[\"coins\"]";
        e.maxLevel = 99;
        e.hasMultiplayer = true;
        e.hasGuilds = true;
        e.hasPvp = false;
        e.dataRetentionDays = 180;
        e.enableRealTimeAnalytics = false;
        e.enableCrashReporting = false;
        e.sampleRate = 0.25;
        e.piiDetectionEnabled = false;
        e.gdprCompliance = true;
        e.coppaCompliance = false;
        e.createdAt = T;
        e.updatedAt = T;

        GameDTO dto = new GameDTO(e);
        assertEquals("g-1", dto.id);
        assertEquals("星辰", dto.name);
        assertEquals("星辰物语", dto.displayName);
        assertEquals("desc", dto.description);
        assertEquals(GameEntity.GameGenre.MMORPG, dto.genre);
        assertEquals(Set.of(GameEntity.GamePlatform.PC, GameEntity.GamePlatform.MOBILE), dto.platforms);
        assertEquals(GameEntity.GameStatus.LIVE, dto.status);
        assertEquals("1.2.3", dto.currentVersion);
        assertEquals("1.0.0", dto.minSupportedVersion);
        assertEquals(T, dto.releaseDate);
        assertEquals("https://app", dto.appStoreUrl);
        assertEquals("https://gp", dto.googlePlayUrl);
        assertEquals("https://st", dto.steamUrl);
        assertEquals("CNY", dto.defaultCurrency);
        assertEquals("Asia/Shanghai", dto.defaultTimezone);
        assertEquals("[\"coins\"]", dto.virtualCurrencies);
        assertEquals(99, dto.maxLevel);
        assertEquals(Boolean.TRUE, dto.hasMultiplayer);
        assertEquals(Boolean.TRUE, dto.hasGuilds);
        assertEquals(Boolean.FALSE, dto.hasPvp);
        assertEquals(180, dto.dataRetentionDays);
        assertEquals(Boolean.FALSE, dto.enableRealTimeAnalytics);
        assertEquals(Boolean.FALSE, dto.enableCrashReporting);
        assertEquals(0.25, dto.sampleRate, 1e-9);
        assertEquals(Boolean.FALSE, dto.piiDetectionEnabled);
        assertEquals(Boolean.TRUE, dto.gdprCompliance);
        assertEquals(Boolean.FALSE, dto.coppaCompliance);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);

        GameEntity back = dto.toEntity();
        assertEquals("g-1", back.id);
        assertEquals(GameEntity.GameGenre.MMORPG, back.genre);
        assertEquals(GameEntity.GameStatus.LIVE, back.status);
        assertEquals(Boolean.TRUE, back.hasMultiplayer);
        assertEquals(180, back.dataRetentionDays);
        assertEquals(0.25, back.sampleRate, 1e-9);
        assertEquals(Set.of(GameEntity.GamePlatform.PC, GameEntity.GamePlatform.MOBILE), back.platforms);

        GameEntity def = new GameDTO().toEntity();
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, def.status);
        assertEquals("USD", def.defaultCurrency);
        assertEquals("UTC", def.defaultTimezone);
        assertEquals(Boolean.FALSE, def.hasMultiplayer);
        assertEquals(Boolean.FALSE, def.hasGuilds);
        assertEquals(Boolean.FALSE, def.hasPvp);
        assertEquals(90, def.dataRetentionDays);
        assertEquals(Boolean.TRUE, def.enableRealTimeAnalytics);
        assertEquals(Boolean.TRUE, def.enableCrashReporting);
        assertEquals(1.0, def.sampleRate, 1e-9);
        assertEquals(Boolean.TRUE, def.piiDetectionEnabled);
        assertEquals(Boolean.FALSE, def.gdprCompliance);
        assertEquals(Boolean.FALSE, def.coppaCompliance);

        GameEntity target = new GameEntity();
        GameDTO patch = new GameDTO();
        patch.name = "新名";
        patch.genre = GameEntity.GameGenre.RPG;
        patch.updateEntity(target);
        assertEquals("新名", target.name);
        assertEquals(GameEntity.GameGenre.RPG, target.genre);
        assertNull(target.description);

        assertTrue(dto.isLive());
        assertTrue(dto.isMultiplayer());
        assertTrue(dto.supportsPlatform(GameEntity.GamePlatform.PC));
        assertFalse(dto.supportsPlatform(GameEntity.GamePlatform.VR));
        assertFalse(new GameDTO().supportsPlatform(GameEntity.GamePlatform.PC));
        assertEquals("星辰物语", dto.getDisplayName());
        assertTrue(dto.isCompliant());
        GameDTO bare = new GameDTO();
        bare.name = "原始名";
        bare.displayName = "  ";
        assertEquals("原始名", bare.getDisplayName());
        assertFalse(bare.isCompliant());
        assertFalse(bare.isLive());
        assertFalse(bare.isMultiplayer());
    }

    @Test
    @DisplayName("EnvironmentDTO：fromEntity/toEntity/updateEntity 映射、inferType 推断与默认值")
    void environmentDtoMappings() {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env-1";
        e.gameId = "g-1";
        e.name = "prod";
        e.displayName = "生产";
        e.description = "d";
        e.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        e.status = GameEnvironmentEntity.EnvironmentStatus.MAINTENANCE;
        e.storageProfileId = "sp-1";
        e.apiEndpoint = "https://api";
        e.dataNamespace = "ns";
        e.kafkaTopicPrefix = "kp";
        e.databaseName = "db_g1";
        e.dataRetentionDays = 30;
        e.maxEventsPerDay = 1000L;
        e.enableDebugMode = true;
        e.enableSampling = false;
        e.sampleRate = 0.5;
        e.enableRealTime = false;
        e.requireHttps = false;
        e.allowedOrigins = "*";
        e.ipWhitelist = "1.1.1.1";
        e.enableAlerts = false;
        e.alertEmail = "a@b.c";
        e.errorThreshold = 0.1;
        e.schemaVersion = "v1";
        e.configVersion = "c1";
        e.createdAt = T;
        e.updatedAt = T;

        EnvironmentDTO dto = new EnvironmentDTO(e);
        assertEquals("env-1", dto.id);
        assertEquals("g-1", dto.gameId);
        assertEquals("prod", dto.name);
        assertEquals("生产", dto.displayName);
        assertEquals("d", dto.description);
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, dto.type);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.MAINTENANCE, dto.status);
        assertEquals("sp-1", dto.storageProfileId);
        assertEquals("https://api", dto.apiEndpoint);
        assertEquals("ns", dto.dataNamespace);
        assertEquals("kp", dto.kafkaTopicPrefix);
        assertEquals("db_g1", dto.databaseName);
        assertEquals(30, dto.dataRetentionDays);
        assertEquals(1000L, dto.maxEventsPerDay);
        assertEquals(Boolean.TRUE, dto.enableDebugMode);
        assertEquals(Boolean.FALSE, dto.enableSampling);
        assertEquals(0.5, dto.sampleRate, 1e-9);
        assertEquals(Boolean.FALSE, dto.enableRealTime);
        assertEquals(Boolean.FALSE, dto.requireHttps);
        assertEquals("*", dto.allowedOrigins);
        assertEquals("1.1.1.1", dto.ipWhitelist);
        assertEquals(Boolean.FALSE, dto.enableAlerts);
        assertEquals("a@b.c", dto.alertEmail);
        assertEquals(0.1, dto.errorThreshold, 1e-9);
        assertEquals("v1", dto.schemaVersion);
        assertEquals("c1", dto.configVersion);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);

        GameEnvironmentEntity back = dto.toEntity();
        assertEquals("env-1", back.id);
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, back.type);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.MAINTENANCE, back.status);
        assertEquals(0.5, back.sampleRate, 1e-9);
        assertEquals(0.1, back.errorThreshold, 1e-9);
        assertEquals(30, back.dataRetentionDays);

        GameEnvironmentEntity def = new EnvironmentDTO().toEntity();
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.ACTIVE, def.status);
        assertEquals(Boolean.FALSE, def.enableDebugMode);
        assertEquals(Boolean.TRUE, def.enableSampling);
        assertEquals(1.0, def.sampleRate, 1e-9);
        assertEquals(Boolean.TRUE, def.enableRealTime);
        assertEquals(Boolean.TRUE, def.requireHttps);
        assertEquals(Boolean.TRUE, def.enableAlerts);
        assertEquals(0.05, def.errorThreshold, 1e-9);

        EnvironmentDTO infer = new EnvironmentDTO();
        infer.name = "production";
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, infer.toEntity().type);
        infer.name = " Staging ";
        assertEquals(GameEnvironmentEntity.EnvironmentType.STAGING, infer.toEntity().type);
        infer.name = "pre";
        assertEquals(GameEnvironmentEntity.EnvironmentType.STAGING, infer.toEntity().type);
        infer.name = "qa";
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, infer.toEntity().type);
        infer.name = "testing";
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, infer.toEntity().type);
        infer.name = "loadtest";
        assertEquals(GameEnvironmentEntity.EnvironmentType.LOADTEST, infer.toEntity().type);
        infer.name = "whatever";
        assertEquals(GameEnvironmentEntity.EnvironmentType.DEVELOPMENT, infer.toEntity().type);
        infer.name = null;
        assertEquals(GameEnvironmentEntity.EnvironmentType.DEVELOPMENT, infer.toEntity().type);

        GameEnvironmentEntity target = new GameEnvironmentEntity();
        target.name = "prod";
        EnvironmentDTO patch = new EnvironmentDTO();
        patch.displayName = "新显示名";
        patch.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
        patch.updateEntity(target);
        assertEquals("新显示名", target.displayName);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, target.status);
        assertEquals("prod", target.name);
        assertNull(target.description);
    }

    @Test
    @DisplayName("EventDefinitionDTO：fromEntity/toEntity/updateEntity 映射、默认值与重要性判定")
    void eventDefinitionDtoMappings() {
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.id = "ed-1";
        e.trackingPlanId = "tp-1";
        e.eventName = "level_up";
        e.eventType = "game";
        e.displayName = "升级";
        e.description = "玩家升级";
        e.category = "progression";
        e.subcategory = "level";
        e.identity = EventDefinitionEntity.EventIdentity.PLAYER;
        e.requireUserId = true;
        e.requireSessionId = false;
        e.requirePlayerId = true;
        e.validationRules = "{}";
        e.examplePayload = "{}";
        e.docUrl = "http://doc";
        e.importance = EventDefinitionEntity.Importance.CRITICAL;
        e.status = EventDefinitionEntity.DefinitionStatus.DISABLED;
        e.usageCount = 42L;
        e.lastUsedAt = T;
        e.createdAt = T;
        e.updatedAt = T;
        e.createdBy = "admin";

        EventDefinitionDTO dto = new EventDefinitionDTO(e);
        assertEquals("ed-1", dto.id);
        assertEquals("tp-1", dto.trackingPlanId);
        assertEquals("level_up", dto.eventName);
        assertEquals("game", dto.eventType);
        assertEquals("升级", dto.displayName);
        assertEquals("玩家升级", dto.description);
        assertEquals("progression", dto.category);
        assertEquals("level", dto.subcategory);
        assertEquals(EventDefinitionEntity.EventIdentity.PLAYER, dto.identity);
        assertEquals(Boolean.TRUE, dto.requireUserId);
        assertEquals(Boolean.FALSE, dto.requireSessionId);
        assertEquals(Boolean.TRUE, dto.requirePlayerId);
        assertEquals("{}", dto.validationRules);
        assertEquals("{}", dto.examplePayload);
        assertEquals("http://doc", dto.docUrl);
        assertEquals(EventDefinitionEntity.Importance.CRITICAL, dto.importance);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DISABLED, dto.status);
        assertEquals(42L, dto.usageCount);
        assertEquals(T, dto.lastUsedAt);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);
        assertEquals("admin", dto.createdBy);

        EventDefinitionEntity back = dto.toEntity();
        assertEquals("ed-1", back.id);
        assertEquals("tp-1", back.trackingPlanId);
        assertEquals("level_up", back.eventName);
        assertEquals(EventDefinitionEntity.EventIdentity.PLAYER, back.identity);
        assertEquals(Boolean.TRUE, back.requireUserId);
        assertEquals(EventDefinitionEntity.Importance.CRITICAL, back.importance);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DISABLED, back.status);
        assertEquals(42L, back.usageCount);
        assertEquals("admin", back.createdBy);

        EventDefinitionEntity def = new EventDefinitionDTO().toEntity();
        assertEquals(EventDefinitionEntity.EventIdentity.DEVICE, def.identity);
        assertEquals(Boolean.FALSE, def.requireUserId);
        assertEquals(Boolean.FALSE, def.requireSessionId);
        assertEquals(Boolean.FALSE, def.requirePlayerId);
        assertEquals(EventDefinitionEntity.Importance.NORMAL, def.importance);
        assertEquals(EventDefinitionEntity.DefinitionStatus.ACTIVE, def.status);
        assertEquals(0L, def.usageCount);

        EventDefinitionEntity target = new EventDefinitionEntity();
        EventDefinitionDTO patch = new EventDefinitionDTO();
        patch.displayName = "新名";
        patch.importance = EventDefinitionEntity.Importance.HIGH;
        patch.updateEntity(target);
        assertEquals("新名", target.displayName);
        assertEquals(EventDefinitionEntity.Importance.HIGH, target.importance);
        assertNull(target.description);

        assertFalse(dto.isActive());
        assertTrue(dto.isRequired());
        assertTrue(dto.hasRequiredIdentity());

        EventDefinitionDTO plain = new EventDefinitionDTO();
        plain.importance = EventDefinitionEntity.Importance.NORMAL;
        plain.status = EventDefinitionEntity.DefinitionStatus.ACTIVE;
        assertTrue(plain.isActive());
        assertFalse(plain.isRequired());
        assertFalse(plain.hasRequiredIdentity());
        plain.importance = EventDefinitionEntity.Importance.HIGH;
        assertTrue(plain.isRequired());
        plain.requireSessionId = true;
        assertTrue(plain.hasRequiredIdentity());
    }

    @Test
    @DisplayName("EventPropertyDefinitionDTO：fromEntity/toEntity/updateEntity 映射、默认值与校验判定")
    void eventPropertyDefinitionDtoMappings() {
        EventPropertyDefinitionEntity e = new EventPropertyDefinitionEntity();
        e.id = "pd-1";
        e.eventDefinitionId = "ed-1";
        e.propertyName = "level";
        e.displayName = "等级";
        e.description = "d";
        e.type = EventPropertyDefinitionEntity.PropertyType.INTEGER;
        e.arrayElementType = "STRING";
        e.required = true;
        e.defaultValue = "1";
        e.allowedValues = "1,2,3";
        e.cardinalityLimit = 100;
        e.minValue = 0.0;
        e.maxValue = 99.0;
        e.minLength = 1;
        e.maxLength = 3;
        e.regexPattern = "^\\d+$";
        e.isPii = true;
        e.piiType = "EMAIL";
        e.isIndexed = false;
        e.propertyGroup = "core";
        e.displayOrder = 2;
        e.status = EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED;
        e.createdAt = T;
        e.updatedAt = T;

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO(e);
        assertEquals("pd-1", dto.id);
        assertEquals("ed-1", dto.eventDefinitionId);
        assertEquals("level", dto.propertyName);
        assertEquals("等级", dto.displayName);
        assertEquals("d", dto.description);
        assertEquals(EventPropertyDefinitionEntity.PropertyType.INTEGER, dto.type);
        assertEquals("STRING", dto.arrayElementType);
        assertEquals(Boolean.TRUE, dto.required);
        assertEquals("1", dto.defaultValue);
        assertEquals("1,2,3", dto.allowedValues);
        assertEquals(100, dto.cardinalityLimit);
        assertEquals(0.0, dto.minValue, 1e-9);
        assertEquals(99.0, dto.maxValue, 1e-9);
        assertEquals(1, dto.minLength);
        assertEquals(3, dto.maxLength);
        assertEquals("^\\d+$", dto.regexPattern);
        assertEquals(Boolean.TRUE, dto.isPii);
        assertEquals("EMAIL", dto.piiType);
        assertEquals(Boolean.FALSE, dto.isIndexed);
        assertEquals("core", dto.propertyGroup);
        assertEquals(2, dto.displayOrder);
        assertEquals(EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED, dto.status);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);

        EventPropertyDefinitionEntity back = dto.toEntity();
        assertEquals("pd-1", back.id);
        assertEquals("ed-1", back.eventDefinitionId);
        assertEquals("level", back.propertyName);
        assertEquals(EventPropertyDefinitionEntity.PropertyType.INTEGER, back.type);
        assertEquals(Boolean.TRUE, back.required);
        assertEquals(100, back.cardinalityLimit);
        assertEquals(0.0, back.minValue, 1e-9);
        assertEquals(99.0, back.maxValue, 1e-9);
        assertEquals(Boolean.TRUE, back.isPii);
        assertEquals(Boolean.FALSE, back.isIndexed);
        assertEquals(2, back.displayOrder);
        assertEquals(EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED, back.status);

        EventPropertyDefinitionEntity def = new EventPropertyDefinitionDTO().toEntity();
        assertEquals(EventPropertyDefinitionEntity.PropertyType.STRING, def.type);
        assertEquals(Boolean.FALSE, def.required);
        assertEquals(Boolean.FALSE, def.isPii);
        assertEquals(Boolean.TRUE, def.isIndexed);
        assertEquals(0, def.displayOrder);
        assertEquals(EventPropertyDefinitionEntity.PropertyStatus.ACTIVE, def.status);

        EventPropertyDefinitionEntity target = new EventPropertyDefinitionEntity();
        EventPropertyDefinitionDTO patch = new EventPropertyDefinitionDTO();
        patch.displayName = "新";
        patch.required = false;
        patch.updateEntity(target);
        assertEquals("新", target.displayName);
        assertEquals(Boolean.FALSE, target.required);
        assertNull(target.description);

        assertFalse(dto.isActive());
        assertTrue(dto.isSensitive());
        assertTrue(dto.hasValidation());

        EventPropertyDefinitionDTO plain = new EventPropertyDefinitionDTO();
        assertFalse(plain.hasValidation());
        assertFalse(plain.isSensitive());
        plain.status = EventPropertyDefinitionEntity.PropertyStatus.ACTIVE;
        plain.minValue = 1.0;
        assertTrue(plain.isActive());
        assertTrue(plain.hasValidation());
    }

    @Test
    @DisplayName("TrackingPlanDTO：fromEntity/toEntity/updateEntity 映射、默认值与状态判定")
    void trackingPlanDtoMappings() {
        TrackingPlanEntity e = new TrackingPlanEntity();
        e.id = "tp-1";
        e.gameId = "g-1";
        e.name = "核心计划";
        e.displayName = "核心";
        e.description = "d";
        e.version = "v2";
        e.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        e.environmentId = "env-1";
        e.strictness = TrackingPlanEntity.ValidationStrictness.WARN;
        e.enableAutoValidation = false;
        e.rejectUnknownEvents = true;
        e.totalEvents = 10;
        e.activeEvents = 8;
        e.createdAt = T;
        e.updatedAt = T;
        e.activatedAt = T;
        e.deactivatedAt = T;
        e.createdBy = "creator";
        e.activatedBy = "actor";

        TrackingPlanDTO dto = new TrackingPlanDTO(e);
        assertEquals("tp-1", dto.id);
        assertEquals("g-1", dto.gameId);
        assertEquals("核心计划", dto.name);
        assertEquals("核心", dto.displayName);
        assertEquals("d", dto.description);
        assertEquals("v2", dto.version);
        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, dto.status);
        assertEquals("env-1", dto.environmentId);
        assertEquals(TrackingPlanEntity.ValidationStrictness.WARN, dto.strictness);
        assertEquals(Boolean.FALSE, dto.enableAutoValidation);
        assertEquals(Boolean.TRUE, dto.rejectUnknownEvents);
        assertEquals(10, dto.totalEvents);
        assertEquals(8, dto.activeEvents);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);
        assertEquals(T, dto.activatedAt);
        assertEquals(T, dto.deactivatedAt);
        assertEquals("creator", dto.createdBy);
        assertEquals("actor", dto.activatedBy);

        TrackingPlanEntity back = dto.toEntity();
        assertEquals("tp-1", back.id);
        assertEquals("g-1", back.gameId);
        assertEquals("核心计划", back.name);
        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, back.status);
        assertEquals("env-1", back.environmentId);
        assertEquals(TrackingPlanEntity.ValidationStrictness.WARN, back.strictness);
        assertEquals(Boolean.FALSE, back.enableAutoValidation);
        assertEquals(Boolean.TRUE, back.rejectUnknownEvents);
        assertEquals(10, back.totalEvents);
        assertEquals(8, back.activeEvents);
        assertEquals("creator", back.createdBy);

        TrackingPlanEntity def = new TrackingPlanDTO().toEntity();
        assertEquals(TrackingPlanEntity.PlanStatus.DRAFT, def.status);
        assertEquals(TrackingPlanEntity.ValidationStrictness.STRICT, def.strictness);
        assertEquals(Boolean.TRUE, def.enableAutoValidation);
        assertEquals(Boolean.FALSE, def.rejectUnknownEvents);
        assertEquals(0, def.totalEvents);
        assertEquals(0, def.activeEvents);

        TrackingPlanEntity target = new TrackingPlanEntity();
        TrackingPlanDTO patch = new TrackingPlanDTO();
        patch.version = "v3";
        patch.strictness = TrackingPlanEntity.ValidationStrictness.OFF;
        patch.updateEntity(target);
        assertEquals("v3", target.version);
        assertEquals(TrackingPlanEntity.ValidationStrictness.OFF, target.strictness);
        assertNull(target.description);

        assertTrue(dto.isActive());
        assertFalse(dto.isDraft());
        assertFalse(dto.canEdit());
        assertFalse(dto.isGlobal());

        TrackingPlanDTO draft = new TrackingPlanDTO();
        draft.status = TrackingPlanEntity.PlanStatus.DRAFT;
        assertFalse(draft.isActive());
        assertTrue(draft.isDraft());
        assertTrue(draft.canEdit());
        assertTrue(draft.isGlobal());
        draft.environmentId = "env-x";
        assertFalse(draft.isGlobal());
    }

    @Test
    @DisplayName("ExperimentDTO：字段填充与 JsonNode 配置序列化")
    void experimentDtoFields() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = "exp-1";
        dto.gameId = "g-1";
        dto.environmentId = "env-1";
        dto.environment = "prod";
        dto.name = "按钮颜色实验";
        dto.status = "running";
        dto.salt = "salt-1";
        dto.config = MAPPER.valueToTree(Map.of("variants", List.of("a", "b")));
        dto.createdAt = Instant.ofEpochMilli(1700000000000L);
        dto.updatedAt = Instant.ofEpochMilli(1700000009999L);

        assertEquals("exp-1", dto.id);
        assertEquals("g-1", dto.gameId);
        assertEquals("env-1", dto.environmentId);
        assertEquals("prod", dto.environment);
        assertEquals("按钮颜色实验", dto.name);
        assertEquals("running", dto.status);
        assertEquals("salt-1", dto.salt);
        assertEquals(2, dto.config.get("variants").size());
        assertEquals("b", dto.config.get("variants").get(1).asText());
        assertEquals(Instant.ofEpochMilli(1700000000000L), dto.createdAt);
        assertEquals(Instant.ofEpochMilli(1700000009999L), dto.updatedAt);

        JsonNode json = MAPPER.valueToTree(dto);
        assertEquals("exp-1", json.get("id").asText());
        assertEquals("running", json.get("status").asText());
        assertEquals("prod", json.get("environment").asText());
        assertEquals(2, json.get("config").get("variants").size());
        assertEquals(1700000000L, json.get("createdAt").asLong());  // JSR310 默认秒级时间戳
    }

    @Test
    @DisplayName("ExperimentConfigDTO：字段填充与 JSON 序列化")
    void experimentConfigDtoFields() {
        ExperimentConfigDTO dto = new ExperimentConfigDTO();
        dto.id = "exp-9";
        dto.salt = "salty";
        dto.config = MAPPER.valueToTree(Map.of("ratio", 0.5, "enabled", true));

        assertEquals("exp-9", dto.id);
        assertEquals("salty", dto.salt);
        assertEquals(0.5, dto.config.get("ratio").asDouble(), 1e-9);
        assertTrue(dto.config.get("enabled").asBoolean());

        JsonNode json = MAPPER.valueToTree(dto);
        assertEquals("exp-9", json.get("id").asText());
        assertEquals("salty", json.get("salt").asText());
        assertEquals(3, json.size());  // id + salt + config
        assertTrue(json.get("config").get("enabled").asBoolean());
    }

    @Test
    @DisplayName("StorageProfileDTO：fromEntity/toEntity/updateEntity 映射、默认值与隔离策略判定")
    void storageProfileDtoMappings() {
        StorageProfileEntity e = new StorageProfileEntity();
        e.id = "sp-1";
        e.name = "共享集群";
        e.displayName = "共享";
        e.description = "d";
        e.isolationStrategy = StorageProfileEntity.IsolationStrategy.PROD_ISOLATED;
        e.kafkaCluster = "k1";
        e.clickhouseCluster = "c1";
        e.redisCluster = "r1";
        e.archiveBucket = "bucket-1";
        e.active = false;
        e.createdAt = T;
        e.updatedAt = T;
        e.deletedAt = T;

        StorageProfileDTO dto = new StorageProfileDTO(e);
        assertEquals("sp-1", dto.id);
        assertEquals("共享集群", dto.name);
        assertEquals("共享", dto.displayName);
        assertEquals("d", dto.description);
        assertEquals(StorageProfileEntity.IsolationStrategy.PROD_ISOLATED, dto.isolationStrategy);
        assertEquals("k1", dto.kafkaCluster);
        assertEquals("c1", dto.clickhouseCluster);
        assertEquals("r1", dto.redisCluster);
        assertEquals("bucket-1", dto.archiveBucket);
        assertEquals(Boolean.FALSE, dto.active);
        assertEquals(T, dto.createdAt);
        assertEquals(T, dto.updatedAt);
        assertEquals(T, dto.deletedAt);

        StorageProfileEntity back = dto.toEntity();
        assertEquals("sp-1", back.id);
        assertEquals("共享集群", back.name);
        assertEquals("共享", back.displayName);
        assertEquals(StorageProfileEntity.IsolationStrategy.PROD_ISOLATED, back.isolationStrategy);
        assertEquals("k1", back.kafkaCluster);
        assertEquals("bucket-1", back.archiveBucket);
        assertEquals(Boolean.FALSE, back.active);

        StorageProfileEntity def = new StorageProfileDTO().toEntity();
        assertEquals(StorageProfileEntity.IsolationStrategy.SHARED, def.isolationStrategy);
        assertEquals(Boolean.TRUE, def.active);
        assertNull(def.displayName);

        StorageProfileDTO named = new StorageProfileDTO();
        named.name = "n1";
        assertEquals("n1", named.toEntity().displayName);
        assertEquals("n1", named.getDisplayName());

        StorageProfileEntity target = new StorageProfileEntity();
        StorageProfileDTO patch = new StorageProfileDTO();
        patch.name = "新名";
        patch.active = false;
        patch.updateEntity(target);
        assertEquals("新名", target.name);
        assertEquals(Boolean.FALSE, target.active);
        assertNull(target.description);

        assertTrue(dto.isProdIsolated());
        assertFalse(dto.isShared());
        assertFalse(dto.isDedicated());
        assertFalse(dto.isActive());
        StorageProfileDTO shared = new StorageProfileDTO();
        shared.isolationStrategy = StorageProfileEntity.IsolationStrategy.SHARED;
        assertTrue(shared.isShared());
        shared.active = true;
        assertTrue(shared.isActive());
        shared.deletedAt = T;
        assertFalse(shared.isActive());
        StorageProfileDTO dedicated = new StorageProfileDTO();
        dedicated.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        assertTrue(dedicated.isDedicated());
        StorageProfileDTO blank = new StorageProfileDTO();
        blank.name = "回退名";
        blank.displayName = "   ";
        assertEquals("回退名", blank.getDisplayName());
    }

    @Test
    @DisplayName("ApiResponse：静态工厂、错误码、分页计算与追踪ID")
    void apiResponseFactories() {
        ApiResponse<String> ok = ApiResponse.success("payload");
        assertEquals(200, ok.code);
        assertEquals("Success", ok.message);
        assertEquals("payload", ok.data);
        assertTrue(ok.isSuccess());
        assertNotNull(ok.timestamp);

        assertEquals(200, ApiResponse.success().code);
        assertNull(ApiResponse.success().data);

        ApiResponse<String> okMsg = ApiResponse.success("已创建", "x");
        assertEquals(200, okMsg.code);
        assertEquals("已创建", okMsg.message);
        assertEquals("x", okMsg.data);

        ApiResponse<Void> err = ApiResponse.error("服务器崩溃");
        assertEquals(500, err.code);
        assertEquals("服务器崩溃", err.message);
        assertFalse(err.isSuccess());

        assertEquals(500, ApiResponse.error(500, "内部错误").code);
        assertEquals(400, ApiResponse.badRequest("参数错误").code);
        assertEquals(409, ApiResponse.conflict("冲突").code);

        ApiResponse<Void> nf = ApiResponse.notFound(null);
        assertEquals(404, nf.code);
        assertEquals("Not Found", nf.message);
        assertEquals("资源不存在", ApiResponse.notFound("资源不存在").message);

        assertEquals(401, ApiResponse.unauthorized(null).code);
        assertEquals("Unauthorized", ApiResponse.unauthorized(null).message);
        assertEquals("未登录", ApiResponse.unauthorized("未登录").message);

        assertEquals(403, ApiResponse.forbidden(null).code);
        assertEquals("Forbidden", ApiResponse.forbidden(null).message);
        assertEquals("无权限", ApiResponse.forbidden("无权限").message);

        ApiResponse<List<String>> ve = ApiResponse.validationError("校验失败", List.of("a", "b"));
        assertEquals(400, ve.code);
        assertEquals("校验失败", ve.message);
        assertEquals(List.of("a", "b"), ve.data);

        ApiResponse<List<String>> page = ApiResponse.page(List.of("a"), 101, 3, 20);
        assertEquals(200, page.code);
        assertEquals(101, page.pageInfo.total);
        assertEquals(3, page.pageInfo.page);
        assertEquals(20, page.pageInfo.size);
        assertEquals(6, page.pageInfo.totalPages);
        assertEquals(0, new ApiResponse.PageInfo(100, 0, 0).totalPages);
        assertEquals(1, new ApiResponse.PageInfo(1, 1, 5).totalPages);

        ApiResponse<String> traced = ApiResponse.success("d");
        assertSame(traced, traced.withTraceId("t-1"));
        assertEquals("t-1", traced.traceId);

        ApiResponse<Void> empty = new ApiResponse<>(201, "Created");
        assertEquals(201, empty.code);
        assertTrue(empty.isSuccess());
        assertFalse(ApiResponse.error(300, "重定向").isSuccess());
    }

    @Test
    @DisplayName("IdentityEventDto：字段填充与 snake_case JSON 映射")
    void identityEventDtoJsonMapping() {
        IdentityEventDto dto = new IdentityEventDto();
        dto.gameId = "g-1";
        dto.environment = "prod";
        dto.identityId = "32-char-identity-id";
        dto.userId = "u-1";
        dto.playerId = "p-1";
        dto.deviceIds = List.of("d-1", "d-2");
        dto.playerIds = List.of("p-1", "p-2");
        dto.characterIds = List.of("c-1");
        dto.firstSeen = 1700000000000L;
        dto.lastSeen = 1700000009999L;

        assertEquals("g-1", dto.gameId);
        assertEquals("32-char-identity-id", dto.identityId);
        assertEquals(2, dto.deviceIds.size());
        assertEquals(1700000000000L, dto.firstSeen);

        JsonNode json = MAPPER.valueToTree(dto);
        assertEquals("g-1", json.get("game_id").asText());
        assertEquals("prod", json.get("environment").asText());
        assertEquals("32-char-identity-id", json.get("identity_id").asText());
        assertEquals("u-1", json.get("user_id").asText());
        assertEquals("p-1", json.get("player_id").asText());
        assertEquals(2, json.get("device_ids").size());
        assertEquals("d-2", json.get("device_ids").get(1).asText());
        assertEquals(2, json.get("player_ids").size());
        assertEquals("p-2", json.get("player_ids").get(1).asText());
        assertEquals(1, json.get("character_ids").size());
        assertEquals("c-1", json.get("character_ids").get(0).asText());
        assertEquals(1700000000000L, json.get("first_seen").asLong());
        assertEquals(1700000009999L, json.get("last_seen").asLong());
        assertFalse(json.has("gameId"));
        assertFalse(json.has("identityId"));
        assertFalse(json.has("deviceIds"));
    }

    @Test
    @DisplayName("RiskEventDto：字段填充与 snake_case JSON 映射")
    void riskEventDtoJsonMapping() {
        RiskEventDto dto = new RiskEventDto();
        dto.gameId = "g-1";
        dto.environment = "prod";
        dto.ts = 1700000000000L;
        dto.riskEventId = "re-1";
        dto.sourceEventId = "se-1";
        dto.ruleId = "rule-9";
        dto.riskType = "CHEAT";
        dto.severity = "HIGH";
        dto.subjectType = "PLAYER";
        dto.subjectId = "p-1";
        dto.score = 98.5f;
        dto.action = "BLOCK";
        dto.reason = "多账号作弊";
        dto.evidence = Map.of("ip", "1.2.3.4", "count", "7");

        assertEquals("re-1", dto.riskEventId);
        assertEquals("BLOCK", dto.action);
        assertEquals(98.5f, dto.score, 1e-6);
        assertEquals(2, dto.evidence.size());

        JsonNode json = MAPPER.valueToTree(dto);
        assertEquals("g-1", json.get("game_id").asText());
        assertEquals("prod", json.get("environment").asText());
        assertEquals(1700000000000L, json.get("ts").asLong());
        assertEquals("re-1", json.get("risk_event_id").asText());
        assertEquals("se-1", json.get("source_event_id").asText());
        assertEquals("rule-9", json.get("rule_id").asText());
        assertEquals("CHEAT", json.get("risk_type").asText());
        assertEquals("HIGH", json.get("severity").asText());
        assertEquals("PLAYER", json.get("subject_type").asText());
        assertEquals("p-1", json.get("subject_id").asText());
        assertEquals(98.5, json.get("score").asDouble(), 1e-6);
        assertEquals("BLOCK", json.get("action").asText());
        assertEquals("多账号作弊", json.get("reason").asText());
        assertEquals("1.2.3.4", json.get("evidence").get("ip").asText());
        assertEquals("7", json.get("evidence").get("count").asText());
        assertFalse(json.has("gameId"));
        assertFalse(json.has("riskEventId"));
        assertFalse(json.has("subjectType"));
    }
}
