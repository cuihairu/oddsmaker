package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentMetricsAggregator;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.QuotaRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RemoteConfigEntity;
import io.oddsmaker.control.jpa.RemoteConfigRepo;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SystemConfigEntity;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import io.oddsmaker.control.jpa.TelemetryConfigEntity;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖率收尾补齐：各服务残余分支（默认值回落、定时任务防御 catch、私有辅助、静态装配器边界）。
 * 每条针对此前 jacoco 报告中的具体未覆盖行，不追求业务语义全景（各 DeepTest 已覆盖）。
 */
@DisplayName("覆盖率收尾：残余分支补齐")
class CoverageTopUpTest {

    private static GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game " + id;
        return g;
    }

    // ========== 审计 / 用户 / 维护 ==========

    @Test
    @DisplayName("logIntegrationCall SUCCESS 分支：result 字符串 → SUCCESS 状态落库")
    void auditLogIntegrationCallSuccess() {
        AuditLogRepo repo = mock(AuditLogRepo.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuditLogService service = new AuditLogService();
        ReflectionTestUtils.setField(service, "auditLogRepo", repo);

        AuditLogEntity log = service.logIntegrationCall("int_1", "verify", "SUCCESS", "g1");

        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, log.status);
        assertEquals("integration_call", log.resourceType);
    }

    @Test
    @DisplayName("createUser：status 缺省回落 ACTIVE、roles 缺省 VIEWER")
    void userServiceCreateDefaults() {
        UserRepo userRepo = mock(UserRepo.class);
        AuditLogRepo auditLogRepo = mock(AuditLogRepo.class);
        when(userRepo.existsByUsername("u1")).thenReturn(false);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userRepo", userRepo);
        ReflectionTestUtils.setField(service, "auditLogRepo", auditLogRepo);

        UserEntity user = new UserEntity();
        user.username = "u1";
        user.status = null;  // 字段初始化器默认 ACTIVE，必须显式置 null 才能走到兜底分支
        UserEntity saved = service.createUser(user, "ops");

        assertEquals(UserEntity.UserStatus.ACTIVE, saved.status);
        assertEquals(java.util.Set.of(UserEntity.UserRole.VIEWER), saved.roles);
    }

    @Test
    @DisplayName("getPublicConfigs：重复 configKey 合并取首个")
    void maintenancePublicConfigsDuplicateKey() {
        SystemConfigEntity first = new SystemConfigEntity();
        first.configKey = "k";
        first.configValue = "v1";
        SystemConfigEntity second = new SystemConfigEntity();
        second.configKey = "k";
        second.configValue = "v2";
        SystemConfigRepo repo = mock(SystemConfigRepo.class);
        when(repo.findPublic()).thenReturn(List.of(first, second));
        MaintenanceService service = new MaintenanceService();
        ReflectionTestUtils.setField(service, "systemConfigRepo", repo);

        Map<String, String> configs = service.getPublicConfigs();

        assertEquals(1, configs.size());
        assertEquals("v1", configs.get("k"));
    }

    // ========== 语义 / 校验收窄 ==========

    @Test
    @DisplayName("getDefaultDuration：CRITICAL 无默认时长（返回 null 交由调用方裁决）")
    void blockListCriticalNoDefaultDuration() {
        BlockListService service = new BlockListService();
        assertNull(ReflectionTestUtils.invokeMethod(service, "getDefaultDuration", io.oddsmaker.control.jpa.RiskCaseEntity.RiskLevel.CRITICAL));
    }

    @Test
    @DisplayName("shouldSendForEvent：未配置过滤 + 类型/等级精确匹配")
    void webhookConfigShouldSendFilters() {
        WebhookConfigEntity empty = new WebhookConfigEntity();
        empty.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        assertTrue(empty.shouldSendForEvent("risk_event", "HIGH"));

        WebhookConfigEntity filtered = new WebhookConfigEntity();
        filtered.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        filtered.eventTypes = "risk_event, large_bet";
        filtered.riskLevels = "high";
        assertTrue(filtered.shouldSendForEvent("RISK_EVENT", "HIGH"));
        assertFalse(filtered.shouldSendForEvent("login", "HIGH"));
        assertFalse(filtered.shouldSendForEvent("risk_event", "LOW"));
    }

    @Test
    @DisplayName("createBatch：reward 缺失拒绝（requireGame 通过后校验）")
    void redeemCodeBatchBlankReward() {
        GameRepo gameRepo = mock(GameRepo.class);
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        RedeemCodeService service = new RedeemCodeService();
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);

        RedeemCodeBatchEntity batch = new RedeemCodeBatchEntity();
        batch.gameId = "g1";
        batch.name = "B";
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(batch, "OD", 12, null, "ops"));
    }

    @Test
    @DisplayName("create：environmentId 缺省置空串（全环境生效）")
    void remoteConfigCreateDefaultsEnvironment() {
        RemoteConfigRepo repo = mock(RemoteConfigRepo.class);
        GameRepo gameRepo = mock(GameRepo.class);
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        when(repo.findByGameIdAndEnvironmentIdAndConfigKeyAndDeletedAtIsNull("g1", "", "k"))
            .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RemoteConfigService service = new RemoteConfigService(repo, gameRepo, new ObjectMapper());

        RemoteConfigEntity config = new RemoteConfigEntity();
        config.gameId = "g1";
        config.configKey = "k";
        config.configValue = "{\"a\":1}";
        config.environmentId = null;  // 字段初始化器默认 ""，显式置 null 才能走到兜底分支
        RemoteConfigEntity saved = service.create(config, "ops");

        assertEquals("", saved.environmentId);
        assertEquals(1, saved.version);
    }

    @Test
    @DisplayName("updateFunnel：timeWindowSec 覆盖生效")
    void funnelUpdateTimeWindowSec() {
        io.oddsmaker.control.jpa.FunnelConfigEntity funnel = new io.oddsmaker.control.jpa.FunnelConfigEntity();
        funnel.id = "f1";
        funnel.name = "old";
        FunnelConfigRepo repo = mock(FunnelConfigRepo.class);
        when(repo.findById("f1")).thenReturn(Optional.of(funnel));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FunnelConfigService service = new FunnelConfigService();
        ReflectionTestUtils.setField(service, "funnelConfigRepo", repo);

        io.oddsmaker.control.jpa.FunnelConfigEntity updates = new io.oddsmaker.control.jpa.FunnelConfigEntity();
        updates.timeWindowSec = 600L;
        service.updateFunnel("f1", updates);

        assertEquals(600, funnel.timeWindowSec);
    }

    // ========== 追踪计划：非草稿拒改 + ARRAY 规格校验 ==========

    private TrackingPlanService trackingPlanService(TrackingPlanRepo planRepo,
                                                    EventDefinitionRepo eventDefRepo,
                                                    EventPropertyDefinitionRepo propRepo) {
        TrackingPlanService service = new TrackingPlanService();
        ReflectionTestUtils.setField(service, "trackingPlanRepo", planRepo);
        ReflectionTestUtils.setField(service, "eventDefinitionRepo", eventDefRepo);
        ReflectionTestUtils.setField(service, "propertyDefinitionRepo", propRepo);
        ReflectionTestUtils.setField(service, "auditLog", mock(AuditLogService.class));
        return service;
    }

    private void stubEventDef(EventDefinitionRepo eventDefRepo) {
        EventDefinitionEntity eventDef = new EventDefinitionEntity();
        eventDef.id = "ev1";
        eventDef.trackingPlanId = "tp1";
        when(eventDefRepo.findById("ev1")).thenReturn(Optional.of(eventDef));
    }

    private TrackingPlanEntity plan(TrackingPlanEntity.PlanStatus status) {
        TrackingPlanEntity plan = new TrackingPlanEntity();
        plan.id = "tp1";
        plan.status = status;
        return plan;
    }

    @Test
    @DisplayName("非草稿计划：更新/删除属性定义均拒绝")
    void trackingPlanNonDraftRefused() {
        TrackingPlanRepo planRepo = mock(TrackingPlanRepo.class);
        EventDefinitionRepo eventDefRepo = mock(EventDefinitionRepo.class);
        stubEventDef(eventDefRepo);
        when(planRepo.findByIdAndDeletedAtIsNull("tp1"))
            .thenReturn(Optional.of(plan(TrackingPlanEntity.PlanStatus.ACTIVE)));
        TrackingPlanService service = trackingPlanService(planRepo, eventDefRepo, mock(EventPropertyDefinitionRepo.class));

        assertThrows(IllegalStateException.class,
            () -> service.updatePropertyDefinition("ev1", "p1", new EventPropertyDefinitionDTO()));
        assertThrows(IllegalStateException.class,
            () -> service.deletePropertyDefinition("ev1", "p1"));
    }

    @Test
    @DisplayName("ARRAY 属性缺 arrayElementType 拒绝")
    void trackingPlanArrayRequiresElementType() {
        TrackingPlanRepo planRepo = mock(TrackingPlanRepo.class);
        EventDefinitionRepo eventDefRepo = mock(EventDefinitionRepo.class);
        stubEventDef(eventDefRepo);
        when(planRepo.findByIdAndDeletedAtIsNull("tp1"))
            .thenReturn(Optional.of(plan(TrackingPlanEntity.PlanStatus.DRAFT)));
        EventPropertyDefinitionRepo propRepo = mock(EventPropertyDefinitionRepo.class);
        when(propRepo.findByEventDefinitionIdAndPropertyName("ev1", "tags")).thenReturn(Optional.empty());
        TrackingPlanService service = trackingPlanService(planRepo, eventDefRepo, propRepo);

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tags";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ARRAY;
        assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("ev1", dto));

        // 空串走 isBlank() 分支（null 只短路到 == null，isBlank 指令未执行）
        dto.arrayElementType = "";
        assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("ev1", dto));
    }

    // ========== 身份 / 实验指标 / 预测 ==========

    @Test
    @DisplayName("身份事件：全空白 device/player id 只落主表不建 link")
    void identityConsumerBlankLinkedIds() {
        IdentityRepo identityRepo = mock(IdentityRepo.class);
        IdentityLinkRepo identityLinkRepo = mock(IdentityLinkRepo.class);
        when(identityRepo.findById("idt_" + "a".repeat(28))).thenReturn(Optional.empty());
        when(identityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        IdentityConsumer consumer = new IdentityConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "identityRepo", identityRepo);
        ReflectionTestUtils.setField(consumer, "identityLinkRepo", identityLinkRepo);
        ReflectionTestUtils.setField(consumer, "gameEnvironmentRepo", mock(GameEnvironmentRepo.class));

        String json = "{\"identity_id\":\"idt_" + "a".repeat(28) + "\",\"game_id\":\"g1\","
            + "\"device_ids\":[\"\",\"\"],\"player_ids\":[\"\"]}";
        assertDoesNotThrow(() -> consumer.onIdentityEvent(json));

        verify(identityRepo).save(any());
        verifyNoInteractions(identityLinkRepo);
    }

    @Test
    @DisplayName("实验指标聚合：归因行 variant 为空跳过（快照数 0）")
    void experimentAggregatorSkipsBlankAttributedVariant() {
        ExperimentMetricSnapshotRepo snapshotRepo = mock(ExperimentMetricSnapshotRepo.class);
        ClickHouseClient clickHouse = mock(ClickHouseClient.class);
        when(clickHouse.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("mapContains")) {
                return List.of(Map.of("variant", "", "cnt", 1L, "rev", 0.0, "rev_squares", 0.0));
            }
            return List.of();
        });
        ExperimentMetricsAggregator aggregator = new ExperimentMetricsAggregator(
            mock(ExperimentRepo.class), snapshotRepo, clickHouse);

        assertEquals(0, aggregator.aggregate("exp_1"));
        verifyNoInteractions(snapshotRepo);
    }

    @Test
    @DisplayName("流失刷新：低风险行打 low 档并落预测")
    void predictionChurnLowLevel() {
        ClickHouseClient client = mock(ClickHouseClient.class);
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(
            Map.of("user_id", "u1", "days_inactive", 3L, "session_count", 100L, "revenue_total", 100.0)));
        PredictionMetricsService service = new PredictionMetricsService(client);

        Map<String, Object> resp = service.refreshChurn("g1", null);

        assertEquals(1, resp.get("scored"));
        assertEquals(1L, resp.get("low"));
        assertEquals(0L, resp.get("high"));
        verify(client).update(anyString(), any(Object[].class));
    }

    // ========== 定时任务的防御路径 ==========

    @Test
    @DisplayName("模拟开关关闭：定时健康检查/指标采集直接短路")
    void healthMonitorSimulationsDisabled() {
        HealthMonitorService service = new HealthMonitorService();
        ReflectionTestUtils.setField(service, "simulatedChecksEnabled", false);
        ReflectionTestUtils.setField(service, "simulatedMetricsEnabled", false);

        assertDoesNotThrow(service::performScheduledHealthChecks);
        assertDoesNotThrow(service::collectSystemMetrics);
        // 无类别指标回落 ANOMALY_DETECTED
        assertEquals(io.oddsmaker.control.jpa.SystemAlertEntity.AlertType.ANOMALY_DETECTED,
            ReflectionTestUtils.invokeMethod(service, "getAlertTypeFromMetric",
                io.oddsmaker.control.jpa.HealthMetricEntity.MetricType.NETWORK_IN));
    }

    @Test
    @DisplayName("配额告警：repo 异常被定时任务吞掉（不中断调度）")
    void rateLimitQuotaAlertsRepoFailure() {
        QuotaRepo quotaRepo = mock(QuotaRepo.class);
        when(quotaRepo.findAll()).thenThrow(new RuntimeException("db down"));
        RateLimitService service = new RateLimitService();
        ReflectionTestUtils.setField(service, "quotaRepo", quotaRepo);

        assertDoesNotThrow(service::checkQuotaAlerts);
    }

    @Test
    @DisplayName("审核升级巡检：repo 异常被吞掉（不中断调度）")
    void reviewQueueEscalationRepoFailure() {
        ReviewQueueRepo repo = mock(ReviewQueueRepo.class);
        when(repo.findNeedsEscalation(any())).thenThrow(new RuntimeException("db down"));
        ReviewQueueService service = new ReviewQueueService();
        ReflectionTestUtils.setField(service, "reviewQueueRepo", repo);

        assertDoesNotThrow(service::checkEscalations);
    }

    // ========== 玩家导出 / GDPR 擦除 ==========

    @Test
    @DisplayName("sectionCsv 未知 section 空表头；toJson 失败上抛 IAE")
    void playerExportEdgeCases() {
        PlayerExportService service = new PlayerExportService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        assertEquals("\n", ReflectionTestUtils.invokeMethod(service, "sectionCsv", "unknown", List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "toJson", new Object()));
    }

    @Test
    @DisplayName("GDPR mutation 等待：基线 null 直接 false；查询失败路径中断 → false")
    void playerErasureAwaitMutations() {
        ClickHouseClient clickHouse = mock(ClickHouseClient.class);
        when(clickHouse.query(contains("system.mutations"), any(Object[].class)))
            .thenReturn(List.of(Map.of("c", 1L)));
        PlayerErasureService service = new PlayerErasureService();
        ReflectionTestUtils.setField(service, "clickHouse", clickHouse);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 30);
        ReflectionTestUtils.setField(service, "mutationPollIntervalSeconds", 5);

        // 基线 null（前置查询失败）：无法确认，不误判完成
        Boolean nullBaseline = ReflectionTestUtils.invokeMethod(service, "awaitMutations", List.of("events"), null);
        assertFalse(nullBaseline);

        // pending 持续高于基线 → sleep 可中断 → catch → false（confirmed=false）
        Thread.currentThread().interrupt();
        try {
            Object done = ReflectionTestUtils.invokeMethod(service, "awaitMutations",
                List.of("events"), Map.of("events", 0L));
            assertFalse((Boolean) done);
        } finally {
            assertTrue(Thread.interrupted());  // 清除中断标志，避免污染后续测试
        }
    }

    // ========== 开发者门户 ==========

    @Test
    @DisplayName("createSDKKey：customConfig 不可序列化 → 吞异常，密钥仍落库")
    void developerPortalSdkKeyBadCustomConfig() {
        SDKKeyRepo sdkKeyRepo = mock(SDKKeyRepo.class);
        when(sdkKeyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DeveloperPortalService service = new DeveloperPortalService();
        ReflectionTestUtils.setField(service, "sdkKeyRepo", sdkKeyRepo);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));

        SDKKeyEntity key = service.createSDKKey("g1", "prod", "k1",
            SDKKeyEntity.SDKPlatform.ANDROID, SDKKeyEntity.DeliveryMode.REALTIME,
            Map.of("customConfig", new Object()), "ops");

        assertNotNull(key.publicKey);
        verify(sdkKeyRepo).save(any());
    }

    @Test
    @DisplayName("updateTelemetryConfig：customConfig 不可序列化 → 吞异常，其余字段照常落库")
    void developerPortalTelemetryBadCustomConfig() {
        TelemetryConfigRepo repo = mock(TelemetryConfigRepo.class);
        TelemetryConfigEntity existing = new TelemetryConfigEntity();
        existing.id = "tc1";
        existing.configName = "old";
        when(repo.findById("tc1")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DeveloperPortalService service = new DeveloperPortalService();
        ReflectionTestUtils.setField(service, "telemetryConfigRepo", repo);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));

        TelemetryConfigEntity saved = service.updateTelemetryConfig("tc1",
            Map.of("configName", "new", "customConfig", new Object()), "ops");

        assertEquals("new", saved.configName);
        verify(repo).save(any());
    }

    // ========== 实验服务 ==========

    @Test
    @DisplayName("变体权重和 int 溢出 → 拒绝（两个 MAX_VALUE 相加为负，校验兜底）")
    void experimentWeightOverflowRejected() {
        ExperimentService service = new ExperimentService(mock(ExperimentRepo.class),
            gameRepoStub(), envRepoStub(), new ObjectMapper(), mock(AuditLogService.class));
        ExperimentDTO dto = dtoWithConfig("""
            {"variants":[
              {"name":"a","weight":2147483647},
              {"name":"b","weight":2147483647}]}""");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createExperiment(dto));
        assertTrue(ex.getMessage().contains("total weight"), ex.getMessage());
    }

    @Test
    @DisplayName("config 序列化失败 → IAE（writeConfig 防御路径）")
    void experimentWriteConfigFailure() {
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") {};
            }
        };
        ExperimentService service = new ExperimentService(mock(ExperimentRepo.class),
            gameRepoStub(), envRepoStub(), broken, mock(AuditLogService.class));
        ExperimentDTO dto = dtoWithConfig("{}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createExperiment(dto));
        assertTrue(ex.getMessage().contains("not valid JSON"), ex.getMessage());
    }

    private GameRepo gameRepoStub() {
        GameRepo repo = mock(GameRepo.class);
        when(repo.findById("g1")).thenReturn(Optional.of(game("g1")));
        return repo;
    }

    private GameEnvironmentRepo envRepoStub() {
        GameEnvironmentRepo repo = mock(GameEnvironmentRepo.class);
        io.oddsmaker.control.jpa.GameEnvironmentEntity env = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        env.id = "env1";
        env.gameId = "g1";
        env.name = "prod";
        when(repo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod")).thenReturn(List.of(env));
        return repo;
    }

    private ExperimentDTO dtoWithConfig(String configJson) {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environment = "prod";
        dto.name = "E";
        try {
            dto.config = new ObjectMapper().readTree(configJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return dto;
    }

    // ========== 静态装配器与实体边界 ==========

    @Test
    @DisplayName("LTV 累计：空 cohort 与负 age_day 行跳过")
    void ltvCumulateSkipsInvalidRows() {
        Map<String, java.util.TreeMap<Integer, Double>> cum = LtvForecastAssembler.cumulate(List.of(
            Map.of("cohort", "", "age_day", 1, "revenue", 1.0),
            Map.of("cohort", "2026-09-01", "age_day", -1, "revenue", 2.0)));
        assertTrue(cum.isEmpty());

        cum = LtvForecastAssembler.cumulate(List.of(
            Map.of("cohort", "2026-09-01", "age_day", 0, "revenue", 2.0),
            Map.of("cohort", "2026-09-01", "age_day", 1, "revenue", 3.0)));
        assertEquals(2.0, cum.get("2026-09-01").get(0));
        assertEquals(5.0, cum.get("2026-09-01").get(1));
    }

    @Test
    @DisplayName("留存趋势点：newUsers=0 时比率回落 0.0（除零防御分支）")
    void retentionTrendZeroNewUsersRate() {
        List<Map<String, Object>> points = RetentionMetricsAssembler.toTrendPoints(List.of(
            Map.of("cohort", "2026-09-01", "d", 0, "users", 0L),
            Map.of("cohort", "2026-09-01", "d", 1, "users", 4L)));
        assertEquals(1, points.size());
        assertEquals(0L, points.get(0).get("newUsers"));
        assertEquals(4L, points.get(0).get("d1"));
        assertEquals(0.0, points.get(0).get("d1Rate"));
    }

    @Test
    @DisplayName("留存汇总：newUsers=0 的 cohort 跳过不计入均值")
    void retentionSummarySkipsZeroNewUsers() {
        Map<String, Object> zero = new java.util.HashMap<>();
        zero.put("cohort", "2026-09-01");
        zero.put("newUsers", 0L);
        Map<String, Object> summary = RetentionMetricsAssembler.toSummary(List.of(zero), 30);
        assertEquals(0L, summary.get("totalNewUsers"));
        assertEquals(0, summary.get("cohorts"));
        assertEquals(0.0, summary.get("avgD1Rate"));
    }

    @Test
    @DisplayName("环境权限：全局角色有效但不含该权限 → 落循环体末尾返回 false")
    void permissionGlobalRoleWithoutPermissionFallsThrough() {
        UserRepo userRepo = mock(UserRepo.class);
        UserEntity active = new UserEntity();
        active.status = UserEntity.UserStatus.ACTIVE;
        when(userRepo.findById("u1")).thenReturn(Optional.of(active));
        io.oddsmaker.control.jpa.UserRoleRepo userRoleRepo = mock(io.oddsmaker.control.jpa.UserRoleRepo.class);
        io.oddsmaker.control.jpa.UserRoleEntity valid = new io.oddsmaker.control.jpa.UserRoleEntity();
        valid.enabled = true;  // isValid() true → 进入 role 判定
        valid.roleId = "r1";
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(valid));
        io.oddsmaker.control.jpa.RoleRepo roleRepo = mock(io.oddsmaker.control.jpa.RoleRepo.class);
        io.oddsmaker.control.jpa.RoleEntity role = new io.oddsmaker.control.jpa.RoleEntity();
        role.id = "r1";
        role.enabled = true;   // isEnabled true 但 permissions=null → hasPermission false
        when(roleRepo.findById("r1")).thenReturn(java.util.Optional.of(role));
        PermissionService service = permissionService(userRepo, userRoleRepo);
        ReflectionTestUtils.setField(service, "roleRepo", roleRepo);

        // 游戏级角色未 stub（mock 默认空列表）→ 只走全局循环且 if 判 false，
        // 循环体自然执行到末尾（JaCoCo 行尾探针；continue/return 都跳过该探针）
        assertFalse(service.hasGamePermission("u1", "g1", "perm_missing"));
    }

    @Test
    @DisplayName("toIso：java.util.Date → ISO 字符串")
    void riskAssemblerIsoDate() {
        String iso = ReflectionTestUtils.invokeMethod(RiskMetricsAssembler.class, "toIso",
            new java.util.Date(0L));
        assertEquals("1970-01-01T00:00:00Z", iso);
    }

    @Test
    @DisplayName("审计动作描述：全枚举穷尽映射（switch 无 default 的完整性验证）")
    void auditActionDescriptionsExhaustive() {
        AuditLogEntity entity = new AuditLogEntity();
        for (AuditLogEntity.AuditAction action : AuditLogEntity.AuditAction.values()) {
            entity.action = action;
            assertFalse(entity.getActionDescription().isBlank(), action.name());
        }
    }

    // ========== 权限：锁定用户与失效全局角色 ==========

    private PermissionService permissionService(UserRepo userRepo,
        io.oddsmaker.control.jpa.UserRoleRepo userRoleRepo) {
        PermissionService service = new PermissionService();
        ReflectionTestUtils.setField(service, "userRepo", userRepo);
        ReflectionTestUtils.setField(service, "roleRepo", mock(io.oddsmaker.control.jpa.RoleRepo.class));
        ReflectionTestUtils.setField(service, "permissionRepo", mock(io.oddsmaker.control.jpa.PermissionRepo.class));
        ReflectionTestUtils.setField(service, "userRoleRepo", userRoleRepo);
        return service;
    }

    @Test
    @DisplayName("锁定用户：三类权限检查一律拒绝")
    void permissionLockedUserRefused() {
        UserRepo userRepo = mock(UserRepo.class);
        UserEntity locked = new UserEntity();
        locked.status = UserEntity.UserStatus.LOCKED;
        when(userRepo.findById("u1")).thenReturn(Optional.of(locked));
        PermissionService service = permissionService(userRepo, mock(io.oddsmaker.control.jpa.UserRoleRepo.class));

        assertFalse(service.hasPermission("u1", "perm1"));
        assertFalse(service.hasGamePermission("u1", "g1", "perm1"));
    }

    @Test
    @DisplayName("失效全局角色（enabled=null）跳过，不再查角色表")
    void permissionInvalidGlobalRoleSkipped() {
        UserRepo userRepo = mock(UserRepo.class);
        UserEntity active = new UserEntity();
        active.status = UserEntity.UserStatus.ACTIVE;
        when(userRepo.findById("u1")).thenReturn(Optional.of(active));
        io.oddsmaker.control.jpa.UserRoleRepo userRoleRepo = mock(io.oddsmaker.control.jpa.UserRoleRepo.class);
        io.oddsmaker.control.jpa.UserRoleEntity invalid = new io.oddsmaker.control.jpa.UserRoleEntity();
        invalid.enabled = null;  // isEnabled() false → isValid() false → continue
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(invalid));
        PermissionService service = permissionService(userRepo, userRoleRepo);

        assertFalse(service.hasGamePermission("u1", "g1", "perm1"));
        verify(serviceRoleRepo(service), never()).findById(any());
    }

    private io.oddsmaker.control.jpa.RoleRepo serviceRoleRepo(PermissionService service) {
        return (io.oddsmaker.control.jpa.RoleRepo) ReflectionTestUtils.getField(service, "roleRepo");
    }

    // ========== 数据管道：质量规则失败阻断 ==========

    @Test
    @DisplayName("质量门禁：stop 级规则超阈值 → job 诚实 FAILED 且 recordRun(false)")
    void pipelineQualityRuleStopsOnFailure() {
        io.oddsmaker.control.jpa.PipelineRepo pipelineRepo = mock(io.oddsmaker.control.jpa.PipelineRepo.class);
        io.oddsmaker.control.jpa.PipelineJobRepo jobRepo = mock(io.oddsmaker.control.jpa.PipelineJobRepo.class);
        io.oddsmaker.control.jpa.DataQualityRuleRepo ruleRepo = mock(io.oddsmaker.control.jpa.DataQualityRuleRepo.class);
        PipelineService service = new PipelineService();
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", jobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", ruleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));
        io.oddsmaker.control.service.ClickHouseClient ch = mock(io.oddsmaker.control.service.ClickHouseClient.class);
        ReflectionTestUtils.setField(service, "clickHouse", ch);

        io.oddsmaker.control.jpa.PipelineEntity pipeline = new io.oddsmaker.control.jpa.PipelineEntity();
        pipeline.id = "p1";
        pipeline.pipelineStatus = io.oddsmaker.control.jpa.PipelineEntity.PipelineStatus.ACTIVE;
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        io.oddsmaker.control.jpa.DataQualityRuleEntity rule = new io.oddsmaker.control.jpa.DataQualityRuleEntity();
        rule.ruleName = "row-count";
        rule.actionOnFailure = "stop";
        rule.ruleType = io.oddsmaker.control.jpa.DataQualityRuleEntity.RuleType.COMPLETENESS;
        rule.severity = io.oddsmaker.control.jpa.DataQualityRuleEntity.Severity.ERROR;
        rule.targetTable = "events";
        rule.targetColumn = "device_id";
        when(ruleRepo.findByPipelineId("p1")).thenReturn(List.of(rule));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // CH 返回 total=100 行、违规 12 行 → gt 0 阈值不通过 → stop → job FAILED
        when(ch.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("total", 100L, "v", 12L)));
        io.oddsmaker.control.jpa.PipelineJobEntity job = service.executePipeline("p1", "ops");
        // fail() 落 FAILED 后满足重试条件 → retry() → RETRYING（与 FinalSweep6 先例一致）
        assertEquals(io.oddsmaker.control.jpa.PipelineJobEntity.JobStatus.RETRYING, job.jobStatus);
        assertTrue(job.errorMessage.contains("Quality gate failed"));
        assertNotNull(pipeline.lastError);
        assertEquals(1, pipeline.failureCount);
        assertEquals(0, pipeline.successCount);
    }
}
