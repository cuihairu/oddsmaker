package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮·LAY-003 拆分）：IntegrationService、AuditLogService、ReportService、FlinkJobService、FunnelConfigService、WebhookService、CohortService。与 FinalSweep2* 拆分类（LAY-016）/ ServicesFinalSweep*Test 系列 / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾（第四轮）·集成/报表/漏斗/webhook/群组域")
class FinalSweep3IntegrationReportTest {

// ==================== 共享 Mock（原类逐字搬运） ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @InjectMocks
    private IntegrationService integrationService;

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ReportExecutionRepo executionRepo;

    // 注意：不能加字段级 @Mock ClickHouseClient——PredictionMetricsService 走构造器注入且参数名是
    // client，第二个同类型 mock 会让 Mockito 选错候选（见 testExecuteReportSuccess 内的局部 mock）
    @InjectMocks
    private ReportService reportService;

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @InjectMocks
    private FlinkJobService flinkJobService;

    @Mock
    private FunnelConfigRepo funnelConfigRepo;

    @Mock
    private FunnelStepRepo funnelStepRepo;

    @InjectMocks
    private FunnelConfigService funnelConfigService;

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @InjectMocks
    private WebhookService webhookService;

    @Mock
    private CohortRepo cohortRepo;

    @InjectMocks
    private CohortService cohortService;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    @Test
    @DisplayName("IntegrationService.verifyIntegration 健康检查通过激活")
    void testVerifyIntegration() {
        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g1";
        integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
        integration.endpointUrl = "http://example/hook";
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后 executeHealthCheck 落库探测日志——不 stub 则 save 返回 null → isSuccess NPE → 误判 FAILED
        when(integrationLogRepo.save(any(IntegrationLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        RestTemplate probe = mock(RestTemplate.class);
        when(probe.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
        IntegrationService spied = spy(integrationService);
        doReturn(probe).when(spied).restTemplateFor(any(IntegrationEntity.class));

        IntegrationEntity out = spied.verifyIntegration("i1");
        assertTrue(out.isActive());
        verify(integrationRepo, atLeastOnce()).save(any(IntegrationEntity.class));
    }

    @Test
    @DisplayName("IntegrationService.retryFailedIntegrations 调度重试")
    void testRetryFailedIntegrations() {
        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g1";
        integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
        when(integrationRepo.findRetryable()).thenReturn(List.of(integration));
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后 executeHealthCheck 落库探测日志——不 stub 则 save 返回 null → isSuccess NPE → 误判 FAILED
        when(integrationLogRepo.save(any(IntegrationLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 探测 stub 的 anyString() 不匹配 null——实体必须带 endpointUrl
        integration.endpointUrl = "http://example/hook";
        RestTemplate probe = mock(RestTemplate.class);
        when(probe.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
        IntegrationService spied = spy(integrationService);
        doReturn(probe).when(spied).restTemplateFor(any(IntegrationEntity.class));
        assertDoesNotThrow(() -> spied.retryFailedIntegrations());
        assertTrue(integration.isActive());
    }

    @Test
    @DisplayName("IntegrationService.buildHeaders 三种鉴权头（经 callIntegration 走真实 RestTemplate 失败路径）")
    void testBuildHeadersAuthVariants() {
        for (IntegrationEntity.AuthType authType : List.of(
                IntegrationEntity.AuthType.API_KEY,
                IntegrationEntity.AuthType.BEARER_TOKEN,
                IntegrationEntity.AuthType.BASIC_AUTH)) {
            IntegrationEntity integration = new IntegrationEntity();
            integration.id = "i-" + authType.name();
            integration.gameId = "g1";
            integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
            integration.endpointUrl = "http://127.0.0.1:1/hook";
            integration.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
            integration.authType = authType;
            integration.apiKey = "ak";
            integration.bearerToken = "bt";
            integration.username = "user";
            integration.password = "pass";
            when(integrationRepo.findById(integration.id)).thenReturn(Optional.of(integration));
            when(integrationLogRepo.save(any(IntegrationLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            IntegrationLogEntity log = integrationService.callIntegration(integration.id, "ping", Map.of("k", "v"), "cid");
            assertNotNull(log);
            assertTrue(log.isFailed());
            assertTrue(integration.retryCount >= 1);
        }
    }

    @Test
    @DisplayName("AuditLogService.logCreate resourceType-first：username 缺省回退 + metadata.gameId 注入")
    void testLogCreateResourceTypeFirst() {
        AuditLogService real = new AuditLogService();
        org.springframework.test.util.ReflectionTestUtils.setField(real, "auditLogRepo", auditLogRepo);

        AuditLogEntity out = real.logCreate("game", "g1", "My Game", "u1", null, null,
            Map.of("gameId", "g2"));
        assertNotNull(out);
        assertEquals("u1", out.username);
        assertEquals("g2", out.gameId);
        assertEquals(AuditLogEntity.AuditAction.CREATE, out.action);
        assertEquals("game", out.resourceType);

        AuditLogEntity withUsername = real.logCreate("game", "g1", "My Game", "u1", "bob",
            "1.2.3.4", (Map<String, ?>) null);
        assertEquals("bob", withUsername.username);
        assertEquals("1.2.3.4", withUsername.ipAddress);
        assertNull(withUsername.gameId);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepo, times(2)).save(captor.capture());
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, captor.getValue().status);
    }

    @Test
    @DisplayName("ReportService.createReport 重名抛异常与创建成功")
    void testCreateReport() {
        when(reportRepo.findByGameIdAndName("g1", "r1"))
            .thenReturn(Optional.of(new ReportEntity()));
        assertThrows(IllegalArgumentException.class, () -> reportService.createReport(
            "g1", null, "r1", "R1", "d", null, null, null,
            Map.of("metric", "dau"), null, "creator"));

        when(reportRepo.findByGameIdAndName("g1", "r2")).thenReturn(Optional.empty());
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ReportEntity created = reportService.createReport(
            "g1", "env1", "r2", "R2", "d", ReportEntity.ReportType.SCHEDULED, "growth",
            Map.of("metric", "retention"), Map.of("chart", "line"), "bar", "creator");
        assertEquals("r2", created.name);
        assertEquals(ReportEntity.ReportType.SCHEDULED, created.reportType);
        assertTrue(created.queryConfig.contains("retention"));
        assertTrue(created.visualization.contains("line"));
    }

    @Test
    @DisplayName("ReportService.executeReportAsync 成功完成（markAsRunning→markAsCompleted）")
    void testExecuteReportSuccess() {
        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g1";
        report.name = "r1";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));
        when(executionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后执行走 ClickHouse——局部 mock（避免与 predictionMetricsService 的构造器注入冲突）+ 单行结果
        ClickHouseClient ch = mock(ClickHouseClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(reportService, "clickHouse", ch);
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("bucket", "2026-09-19", "a_events", 7L, "a_players", 4L)));

        ReportExecutionEntity execution = reportService.executeReport(
            "r1", "api", "manual", Map.of("p", "1"), Map.of("f", "2"));
        assertTrue(execution.isCompleted());
        assertTrue(execution.parameters.contains("p"));
        assertTrue(execution.filters.contains("f"));
        assertEquals(1, report.totalRuns);
    }

    @Test
    @DisplayName("ReportService.executeReportAsync 失败回滚（保存报表抛异常→FAILED）")
    void testExecuteReportAsyncFailure() {
        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g1";
        report.name = "r1";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));
        when(executionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any(ReportEntity.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenAnswer(inv -> inv.getArgument(0));

        ReportExecutionEntity execution = reportService.executeReport("r1", "api", null, null, null);
        assertTrue(execution.isFailed());
        verify(reportRepo, times(2)).save(any(ReportEntity.class));
    }

    @Test
    @DisplayName("FlinkJobService.createJob 重名抛异常与创建成功")
    void testCreateJob() {
        when(flinkJobRepo.findByGameIdAndName("g1", "j1"))
            .thenReturn(Optional.of(new FlinkJobEntity()));
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.createJob(
            "g1", "env1", "j1", "J1", "d", null,
            Map.of("k", "v"), Map.of("s", "v"), Map.of("t", "v"),
            List.of("r1"), null, "creator"));

        when(flinkJobRepo.findByGameIdAndName("g1", "j2")).thenReturn(Optional.empty());
        when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        FlinkJobEntity job = flinkJobService.createJob(
            "g1", "env1", "j2", "J2", "d", null,
            Map.of("k", "v"), Map.of("s", "v"), Map.of("t", "v"),
            List.of("r1"), 4, "creator");
        assertEquals("j2", job.name);
        assertEquals(4, job.parallelism);
        assertTrue(job.jobConfig.contains("k"));
        assertTrue(job.ruleIds.contains("r1"));
        verify(auditLogService).logCreate(eq("flink_job"), eq(job.id), eq("j2"), eq("creator"),
            isNull(), isNull(), anyMap());
    }

    @Test
    @DisplayName("FunnelConfigService.createFunnel 默认值回填与步骤级联保存")
    void testCreateFunnel() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.gameId = "g1";
        funnel.name = "purchase";
        funnel.type = null;
        funnel.userKey = null;
        funnel.enabled = null;
        FunnelStepEntity step = new FunnelStepEntity();
        step.name = "login";
        funnel.steps = new ArrayList<>(List.of(step));

        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("g1", "purchase")).thenReturn(false);
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity out = funnelConfigService.createFunnel(funnel);
        assertTrue(out.id.startsWith("funnel_"));
        assertEquals(FunnelConfigEntity.FunnelType.STANDARD, out.type);
        assertEquals("user_id", out.userKey);
        assertEquals(Boolean.TRUE, out.enabled);
        assertSame(out, step.funnel);
        verify(funnelStepRepo).save(step);

        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("g1", "purchase")).thenReturn(true);
        FunnelConfigEntity dup = new FunnelConfigEntity();
        dup.gameId = "g1";
        dup.name = "purchase";
        assertThrows(IllegalArgumentException.class, () -> funnelConfigService.createFunnel(dup));
    }

    @Test
    @DisplayName("FunnelConfigService.updateStep 全字段更新与未找到")
    void testUpdateStep() {
        FunnelStepEntity step = new FunnelStepEntity();
        when(funnelStepRepo.findById("s5")).thenReturn(Optional.of(step));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelStepEntity updates = new FunnelStepEntity();
        updates.name = "pay";
        updates.description = "d";
        updates.eventName = "purchase";
        updates.eventFilter = "{\"amount\":1}";
        updates.timeWindowSec = 60L;
        updates.optional = true;
        updates.icon = "cart";
        updates.color = "blue";
        FunnelStepEntity out = funnelConfigService.updateStep("s5", updates);
        assertEquals("pay", out.name);
        assertEquals("d", out.description);
        assertEquals("purchase", out.eventName);
        assertEquals("{\"amount\":1}", out.eventFilter);
        assertEquals(60L, out.timeWindowSec);
        assertEquals(Boolean.TRUE, out.optional);
        assertEquals("cart", out.icon);
        assertEquals("blue", out.color);

        when(funnelStepRepo.findById("s6")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateStep("s6", new FunnelStepEntity()));
    }

    @Test
    @DisplayName("WebhookService.retryWebhook 重发失败并安排重试（经 processPendingRetries，restTemplate 为 null 走异常分支）")
    void testRetryWebhook() {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "w1";
        config.name = "hook";
        config.gameId = "g1";
        config.webhookUrl = "http://example/cb";
        config.httpMethod = "POST";
        config.requestHeaders = "{\"X-Test\":\"1\"}";
        config.authType = "bearer";
        config.authConfig = "{\"token\":\"tok\"}";
        config.maxRetries = 3;
        config.retryBackoffMs = 10;

        WebhookLogEntity log1 = new WebhookLogEntity();
        log1.webhookConfigId = "w1";
        log1.eventType = "risk_event";
        log1.eventId = "e1";
        log1.requestBody = "{\"a\":1}";
        log1.retryCount = 0;
        WebhookLogEntity log2 = new WebhookLogEntity();
        log2.webhookConfigId = "w2";

        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log1, log2));
        when(webhookConfigRepo.findById("w1")).thenReturn(Optional.of(config));
        when(webhookConfigRepo.findById("w2")).thenReturn(Optional.empty());
        when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> webhookService.processPendingRetries());
        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, atLeastOnce()).save(logCaptor.capture());
        assertTrue(logCaptor.getAllValues().stream()
            .anyMatch(l -> l.deliveryStatus == WebhookLogEntity.DeliveryStatus.RETRYING
                && l.nextRetryAt != null && l.retryCount != null && l.retryCount >= 1));
        assertEquals(WebhookLogEntity.DeliveryStatus.SENDING, log1.deliveryStatus);
        verify(webhookConfigRepo).save(config);
    }

    @Test
    @DisplayName("CohortService.createCohort 重名抛异常与创建成功（含序列化）")
    void testCreateCohort() {
        when(cohortRepo.findByGameIdAndName("g1", "c1")).thenReturn(Optional.of(new CohortEntity()));
        assertThrows(IllegalArgumentException.class, () -> cohortService.createCohort(
            "g1", null, "c1", "C1", "d", null, null, null, null, null, null,
            null, null, null));

        when(cohortRepo.findByGameIdAndName("g1", "c2")).thenReturn(Optional.empty());
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity out = cohortService.createCohort(
            "g1", "env1", "c2", "C2", "d", null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
            "week", "retention", "return_rate",
            Map.of("event", "login"), List.of(1, 7), "creator");
        assertEquals("c2", out.name);
        assertTrue(out.behaviorDefinition.contains("login"));
        assertEquals("[1,7]", out.retentionPeriods);
        assertEquals(CohortEntity.CohortStatus.PENDING, out.status);
    }

    @Test
    @DisplayName("CohortService.calculateCohort 成功完成与失败回滚")
    void testCalculateCohort() {
        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch1";
        cohort.gameId = "g1";
        cohort.name = "c";
        cohort.retentionPeriods = "[1,7]";
        cohort.startDate = java.time.LocalDate.now().minusDays(7);
        cohort.endDate = java.time.LocalDate.now();
        when(cohortRepo.findById("ch1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后计算走 ClickHouse——局部 mock（避免与 PredictionMetricsService 构造器注入抢候选）
        ClickHouseClient ch = mock(ClickHouseClient.class);
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(cohortService, "clickHouse", ch);

        CohortEntity out = cohortService.calculateCohort("ch1");
        assertEquals(CohortEntity.CohortStatus.COMPLETED, out.status);
        assertNotNull(out.resultData);
        assertEquals(0L, out.cohortCount);

        CohortEntity failing = new CohortEntity();
        failing.id = "ch2";
        failing.name = "f";
        when(cohortRepo.findById("ch2")).thenReturn(Optional.of(failing));
        when(cohortRepo.save(any(CohortEntity.class)))
            .thenReturn(failing)
            .thenThrow(new IllegalStateException("db down"));
        assertThrows(RuntimeException.class, () -> cohortService.calculateCohort("ch2"));
        assertEquals(CohortEntity.CohortStatus.FAILED, failing.status);
    }

    @Test
    @DisplayName("CohortService.processPendingCohorts 扫描处理")
    void testProcessPendingCohorts() {
        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch1";
        cohort.gameId = "g1";
        cohort.name = "c";
        cohort.retentionPeriods = "[1,7]";
        cohort.startDate = java.time.LocalDate.now().minusDays(7);
        cohort.endDate = java.time.LocalDate.now();
        when(cohortRepo.findPending()).thenReturn(List.of(cohort));
        when(cohortRepo.findById("ch1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ClickHouseClient ch = mock(ClickHouseClient.class);
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(cohortService, "clickHouse", ch);

        assertDoesNotThrow(() -> cohortService.processPendingCohorts());
        assertEquals(CohortEntity.CohortStatus.COMPLETED, cohort.status);
    }
}
