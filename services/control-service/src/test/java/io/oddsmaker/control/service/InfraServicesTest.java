package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import io.oddsmaker.control.jpa.QuotaRepo;
import io.oddsmaker.control.jpa.RateLimitRepo;
import io.oddsmaker.control.jpa.RateLimitUsageRepo;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.SSOConfigRepo;
import io.oddsmaker.control.jpa.SecurityPolicyRepo;
import io.oddsmaker.control.jpa.SecuritySessionRepo;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 基础设施类 Service 测试：Flink 作业/集成/健康监控/限流/管线/安全（空数据源下的主路径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("基础设施类 Service 测试")
class InfraServicesTest {

    // ===== Flink 作业 =====

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @InjectMocks
    private FlinkJobService flinkJobService;

    @Test
    @DisplayName("Flink 作业：查询/运行中/规则关联主路径")
    void flinkJobMainPaths() {
        lenient().when(flinkJobRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(flinkJobRepo.findRunningJobs("g")).thenReturn(List.of());
        lenient().when(riskRuleRepo.findById(anyString())).thenReturn(java.util.Optional.empty());

        flinkJobService.getGameJobs("g");
        flinkJobService.getRunningJobs("g");
        assertNotNull(flinkJobService.getJobStats("g"));
    }

    // ===== 集成 =====

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @InjectMocks
    private IntegrationService integrationService;

    @Test
    @DisplayName("集成：查询/统计/重试清理主路径")
    void integrationMainPaths() {
        lenient().when(integrationRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(integrationLogRepo.findByIntegrationId("i1")).thenReturn(List.of());
        lenient().when(integrationLogRepo.countCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.countSuccessCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.countFailedCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.averageDurationSince(eq("i1"), any())).thenReturn(0L);

        integrationService.getIntegrations("g");
        integrationService.getIntegrationLogs("i1");
        assertNotNull(integrationService.getIntegrationStats("g"));
        assertNotNull(integrationService.getCallStats("i1", null));
        integrationService.retryFailedIntegrations();
        integrationService.cleanupExpiredLogs();
    }

    // ===== 健康监控 =====

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Test
    @DisplayName("健康监控：检查/指标/告警查询主路径")
    void healthMainPaths() {
        lenient().when(healthCheckRepo.findAll()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of());
        lenient().when(healthMetricRepo.findRecentByType(any(), any())).thenReturn(List.of());
        lenient().when(systemAlertRepo.findActive()).thenReturn(List.of());
        lenient().when(systemAlertRepo.countByStatus(any())).thenReturn(0L);
        lenient().when(systemAlertRepo.countActiveBySeverity(any())).thenReturn(0L);

        healthMonitorService.getHealthChecks();
        healthMonitorService.getRecentMetrics(io.oddsmaker.control.jpa.HealthMetricEntity.MetricType.CPU_USAGE,
            java.time.LocalDateTime.now().minusHours(1));
        healthMonitorService.getActiveAlerts();
        assertNotNull(healthMonitorService.getAlertStats());
        assertNotNull(healthMonitorService.getSystemHealth());
        healthMonitorService.cleanupExpiredMetrics();
        healthMonitorService.cleanupClosedAlerts();
    }

    // ===== 限流 =====

    @Mock
    private RateLimitRepo rateLimitRepo;

    @Mock
    private RateLimitUsageRepo rateLimitUsageRepo;

    @Mock
    private QuotaRepo quotaRepo;

    @InjectMocks
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("限流：规则/配额查询与统计主路径")
    void rateLimitMainPaths() {
        lenient().when(rateLimitRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(quotaRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(quotaRepo.findAll()).thenReturn(List.of());

        rateLimitService.getRateLimits("g");
        assertNotNull(rateLimitService.getRateLimitStats("g"));
        assertNotNull(rateLimitService.getQuotaStats("g"));
    }

    // ===== 数据管线 =====

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @InjectMocks
    private PipelineService pipelineService;

    @Test
    @DisplayName("管线：查询/统计/清理主路径")
    void pipelineMainPaths() {
        lenient().when(pipelineRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(pipelineJobRepo.findByPipelineId("p1")).thenReturn(List.of());
        lenient().when(dataQualityRuleRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        pipelineService.getPipelines("g");
        pipelineService.getPipelineJobs("p1");
        io.oddsmaker.control.jpa.PipelineEntity pipeline = new io.oddsmaker.control.jpa.PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g";
        pipeline.pipelineStatus = io.oddsmaker.control.jpa.PipelineEntity.PipelineStatus.ACTIVE;
        pipeline.runCount = 0;
        pipeline.successCount = 0;
        pipeline.failureCount = 0;
        pipeline.lastRunAt = java.time.LocalDateTime.now();
        pipeline.lastSuccessAt = java.time.LocalDateTime.now();
        lenient().when(pipelineRepo.findById("p1")).thenReturn(java.util.Optional.of(pipeline));
        assertNotNull(pipelineService.getPipelineStats("p1"));
        pipelineService.getQualityRules("g");
        pipelineService.getPipelineQualityRules("p1");
        pipelineService.cleanupOldJobs();
    }

    // ===== 安全（MFA/SSO/会话） =====

    @Mock
    private MFAConfigRepo mfaConfigRepo;

    @Mock
    private SSOConfigRepo ssoConfigRepo;

    @Mock
    private SecuritySessionRepo securitySessionRepo;

    @Mock
    private SecurityPolicyRepo securityPolicyRepo;

    @InjectMocks
    private SecurityService securityService;

    @Test
    @DisplayName("安全：MFA/SSO/会话查询主路径")
    void securityMainPaths() {
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of());
        lenient().when(ssoConfigRepo.findActive()).thenReturn(List.of());
        lenient().when(securitySessionRepo.findByToken("token")).thenReturn(java.util.Optional.empty());
        lenient().when(securityPolicyRepo.findPasswordPolicyForGame("g")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findSessionPolicyForGame("g")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findMFAPolicyForGame("g")).thenReturn(List.of());

        securityService.getUserMFAConfigs("u1");
        securityService.getActiveSSOConfigs();
        assertFalse(securityService.isUserMFAEnabled("u1"));
        securityService.getPasswordPolicies("g");
        securityService.getSessionPolicies("g");
        securityService.getMFAPolicies("g");
                securityService.cleanupExpiredSessions();
    }
}
