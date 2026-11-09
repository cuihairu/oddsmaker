package io.oddsmaker.control.jpa;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.GameDTO;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率冲刺第二轮·玩家运营/DTO 域（LAY-002 拆分）：玩家导出作业、兑换码三件套、覆盖实体生命周期回调、Game/Environment DTO 剩余分支。与 EntitiesSmokeCoverageTest（空构造冒烟）、EntitiesDeep* 拆分类（主要状态方法）互补。
 */
class EntitiesSweep2PlayerDtoTest {

    @Test
    @DisplayName("PlayerExportJobEntity：导出任务字段与默认状态")
    void playerExportJobEntity() {
        PlayerExportJobEntity e = new PlayerExportJobEntity();
        e.id = "pex-1";
        e.gameId = "g1";
        e.environmentId = "prod";
        e.playerId = "p-100";
        e.sections = "[\"profile\",\"payments\"]";
        e.requestedBy = "合规-孙十";
        e.expiresAt = LocalDateTime.now().plusDays(7);

        assertEquals("json", e.exportFormat);
        assertEquals(PlayerExportJobEntity.Status.PENDING, e.status);
        assertNull(e.fileName);
        e.status = PlayerExportJobEntity.Status.COMPLETED;
        e.fileName = "p-100-export.json";
        e.filePath = "/exports/pex-1.json";
        e.fileSizeBytes = 20480L;
        e.rowCount = 356L;
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, e.status);
        assertEquals(20480L, e.fileSizeBytes);
        assertEquals(356L, e.rowCount);
        e.status = PlayerExportJobEntity.Status.FAILED;
        e.errorMessage = "玩家数据不存在";
        assertEquals("玩家数据不存在", e.errorMessage);
    }

    @Test
    @DisplayName("RedeemCodeBatchEntity：批次可兑换判定（状态/删除/过期）")
    void redeemCodeBatchEntity() {
        LocalDateTime now = LocalDateTime.now();
        RedeemCodeBatchEntity b = new RedeemCodeBatchEntity();
        b.gameId = "g1";
        b.name = "春节礼包码";
        b.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        b.codeType = RedeemCodeBatchEntity.CodeType.SHARED;
        b.total = 1000;
        b.perUserLimit = 1;
        b.createdBy = "运营-郑一";

        assertTrue(b.redeemable(now));
        b.status = RedeemCodeBatchEntity.Status.DISABLED;
        assertFalse(b.redeemable(now));
        b.status = RedeemCodeBatchEntity.Status.ACTIVE;
        b.deletedAt = now;
        assertFalse(b.redeemable(now));
        b.deletedAt = null;
        b.expiresAt = now.minusMinutes(1);
        assertFalse(b.redeemable(now));
        b.expiresAt = now.plusDays(3);
        assertTrue(b.redeemable(now));
    }

    @Test
    @DisplayName("RedeemCodeEntity：兑换码状态与兑换信息回填")
    void redeemCodeEntity() {
        RedeemCodeEntity c = new RedeemCodeEntity();
        c.id = "rc-1";
        c.batchId = "batch-1";
        c.code = "SPRING2026";

        assertEquals(RedeemCodeEntity.Status.AVAILABLE, c.status);
        assertNull(c.redeemedBy);
        c.status = RedeemCodeEntity.Status.REDEEMED;
        c.redeemedBy = "p-100";
        c.redeemedAt = LocalDateTime.now().minusMinutes(5);
        assertEquals(RedeemCodeEntity.Status.REDEEMED, c.status);
        assertEquals("p-100", c.redeemedBy);
        assertNotNull(c.redeemedAt);
    }

    @Test
    @DisplayName("RedeemRecordEntity：兑换记录字段固化")
    void redeemRecordEntity() {
        RedeemRecordEntity r = new RedeemRecordEntity();
        r.id = "rr-1";
        r.batchId = "batch-1";
        r.gameId = "g1";
        r.playerKey = "p-100";
        r.seq = 1;
        r.code = "SPRING2026";
        r.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        r.redeemedAt = LocalDateTime.now().minusMinutes(2);

        assertEquals("batch-1", r.batchId);
        assertEquals("p-100", r.playerKey);
        assertEquals(1, r.seq);
        assertEquals("SPRING2026", r.code);
        assertTrue(r.reward.contains("gem"));
    }

    @Test
    @DisplayName("已覆盖实体的 @PrePersist/@PreUpdate 生命周期回调补齐")
    void lifecycleCallbacksForCoveredEntities() {
        FeatureFlagEntity flag = new FeatureFlagEntity();
        assertNull(flag.createdAt);
        flag.onCreate();
        assertNotNull(flag.createdAt);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, flag.flagStatus);
        flag.onUpdate();
        assertNotNull(flag.updatedAt);

        HealthCheckEntity check = new HealthCheckEntity();
        check.onCreate();
        assertNotNull(check.createdAt);
        check.onUpdate();
        assertNotNull(check.updatedAt);

        MLModelEntity model = new MLModelEntity();
        model.modelStatus = null;
        model.onCreate();
        assertNotNull(model.createdAt);
        assertEquals(MLModelEntity.ModelStatus.DRAFT, model.modelStatus);
        model.onUpdate();
        assertNotNull(model.updatedAt);

        PipelineJobEntity job = new PipelineJobEntity();
        job.jobStatus = null;
        job.onCreate();
        assertNotNull(job.createdAt);
        assertEquals(PipelineJobEntity.JobStatus.PENDING, job.jobStatus);
        job.onUpdate();
        assertNotNull(job.updatedAt);

        QuotaEntity quota = new QuotaEntity();
        quota.onCreate();
        assertNotNull(quota.createdAt);
        quota.onUpdate();
        assertNotNull(quota.updatedAt);

        SDKKeyEntity key = new SDKKeyEntity();
        key.keyStatus = null;
        key.deliveryMode = null;
        key.onCreate();
        assertNotNull(key.createdAt);
        assertEquals(SDKKeyEntity.KeyStatus.ACTIVE, key.keyStatus);
        assertEquals(SDKKeyEntity.DeliveryMode.REALTIME, key.deliveryMode);
        key.onUpdate();
        assertNotNull(key.updatedAt);

        SecuritySessionEntity session = new SecuritySessionEntity();
        session.sessionStatus = null;
        session.onCreate();
        assertNotNull(session.createdAt);
        assertNotNull(session.loginAt);
        assertNotNull(session.lastActivityAt);
        assertEquals(SecuritySessionEntity.SessionStatus.ACTIVE, session.sessionStatus);
        session.onUpdate();
        assertNotNull(session.updatedAt);

        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.configStatus = null;
        config.onCreate();
        assertNotNull(config.createdAt);
        assertEquals(TelemetryConfigEntity.ConfigStatus.DRAFT, config.configStatus);
        config.onUpdate();
        assertNotNull(config.updatedAt);
    }

    @Test
    @DisplayName("GameDTO 补余：updateEntity 全字段补丁与 coppa 合规分支")
    void gameDtoRemainingBranches() {
        GameDTO patch = new GameDTO();
        LocalDateTime t = LocalDateTime.of(2026, 9, 9, 10, 0);
        patch.name = "新游戏名";
        patch.displayName = "星海物语";
        patch.description = "太空题材新游";
        patch.genre = GameEntity.GameGenre.STRATEGY;
        patch.platforms = Set.of(GameEntity.GamePlatform.PC);
        patch.status = GameEntity.GameStatus.LIVE;
        patch.currentVersion = "1.2.3";
        patch.minSupportedVersion = "1.0.0";
        patch.releaseDate = t;
        patch.appStoreUrl = "https://app.example.com";
        patch.googlePlayUrl = "https://play.example.com";
        patch.steamUrl = "https://steam.example.com";
        patch.defaultCurrency = "CNY";
        patch.defaultTimezone = "Asia/Shanghai";
        patch.virtualCurrencies = "[\"coin\"]";
        patch.maxLevel = 120;
        patch.hasMultiplayer = true;
        patch.hasGuilds = true;
        patch.hasPvp = true;
        patch.dataRetentionDays = 365;
        patch.enableRealTimeAnalytics = false;
        patch.enableCrashReporting = false;
        patch.sampleRate = 0.5;
        patch.piiDetectionEnabled = true;
        patch.gdprCompliance = true;
        patch.coppaCompliance = true;

        GameEntity target = new GameEntity();
        target.name = "旧名";
        patch.updateEntity(target);
        assertEquals("新游戏名", target.name);
        assertEquals("星海物语", target.displayName);
        assertEquals("太空题材新游", target.description);
        assertEquals(GameEntity.GameGenre.STRATEGY, target.genre);
        assertEquals(Set.of(GameEntity.GamePlatform.PC), target.platforms);
        assertEquals(GameEntity.GameStatus.LIVE, target.status);
        assertEquals("1.2.3", target.currentVersion);
        assertEquals("1.0.0", target.minSupportedVersion);
        assertEquals(t, target.releaseDate);
        assertEquals("https://app.example.com", target.appStoreUrl);
        assertEquals("https://play.example.com", target.googlePlayUrl);
        assertEquals("https://steam.example.com", target.steamUrl);
        assertEquals("CNY", target.defaultCurrency);
        assertEquals("Asia/Shanghai", target.defaultTimezone);
        assertEquals("[\"coin\"]", target.virtualCurrencies);
        assertEquals(120, target.maxLevel);
        assertTrue(target.hasMultiplayer);
        assertTrue(target.hasGuilds);
        assertTrue(target.hasPvp);
        assertEquals(365, target.dataRetentionDays);
        assertFalse(target.enableRealTimeAnalytics);
        assertFalse(target.enableCrashReporting);
        assertEquals(0.5, target.sampleRate, 1e-9);
        assertTrue(target.piiDetectionEnabled);
        assertTrue(target.gdprCompliance);
        assertTrue(target.coppaCompliance);

        GameDTO coppaOnly = new GameDTO();
        coppaOnly.coppaCompliance = true;
        assertTrue(coppaOnly.isCompliant());
        GameDTO gdprOnly = new GameDTO();
        gdprOnly.gdprCompliance = false;
        gdprOnly.coppaCompliance = false;
        assertFalse(gdprOnly.isCompliant());
    }

    @Test
    @DisplayName("EnvironmentDTO 补余：inferType 补充分支与 updateEntity 全字段补丁")
    void environmentDtoRemainingBranches() {
        EnvironmentDTO prod = new EnvironmentDTO();
        prod.name = "prod";
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, prod.toEntity().type);
        EnvironmentDTO stage = new EnvironmentDTO();
        stage.name = "stage";
        assertEquals(GameEnvironmentEntity.EnvironmentType.STAGING, stage.toEntity().type);
        EnvironmentDTO test = new EnvironmentDTO();
        test.name = "test";
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, test.toEntity().type);

        EnvironmentDTO patch = new EnvironmentDTO();
        patch.displayName = "新生产环境";
        patch.description = "全字段更新";
        patch.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        patch.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
        patch.storageProfileId = "sp-9";
        patch.apiEndpoint = "https://api2.example.com";
        patch.dataNamespace = "ns-9";
        patch.kafkaTopicPrefix = "kp-9";
        patch.databaseName = "db_g1_v2";
        patch.dataRetentionDays = 60;
        patch.maxEventsPerDay = 200000L;
        patch.enableDebugMode = true;
        patch.enableSampling = false;
        patch.sampleRate = 0.25;
        patch.enableRealTime = false;
        patch.requireHttps = false;
        patch.allowedOrigins = "https://console.example.com";
        patch.ipWhitelist = "10.0.0.0/8";
        patch.enableAlerts = false;
        patch.alertEmail = "ops@example.com";
        patch.errorThreshold = 0.2;
        patch.schemaVersion = "v2";
        patch.configVersion = "cfg-2";

        GameEnvironmentEntity target = new GameEnvironmentEntity();
        target.displayName = "旧显示名";
        patch.updateEntity(target);
        assertEquals("新生产环境", target.displayName);
        assertEquals("全字段更新", target.description);
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, target.type);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, target.status);
        assertEquals("sp-9", target.storageProfileId);
        assertEquals("https://api2.example.com", target.apiEndpoint);
        assertEquals("ns-9", target.dataNamespace);
        assertEquals("kp-9", target.kafkaTopicPrefix);
        assertEquals("db_g1_v2", target.databaseName);
        assertEquals(60, target.dataRetentionDays);
        assertEquals(200000L, target.maxEventsPerDay);
        assertTrue(target.enableDebugMode);
        assertFalse(target.enableSampling);
        assertEquals(0.25, target.sampleRate, 1e-9);
        assertFalse(target.enableRealTime);
        assertFalse(target.requireHttps);
        assertEquals("https://console.example.com", target.allowedOrigins);
        assertEquals("10.0.0.0/8", target.ipWhitelist);
        assertFalse(target.enableAlerts);
        assertEquals("ops@example.com", target.alertEmail);
        assertEquals(0.2, target.errorThreshold, 1e-9);
        assertEquals("v2", target.schemaVersion);
        assertEquals("cfg-2", target.configVersion);
    }
}
