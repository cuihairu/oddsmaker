package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AdAnalysisRepo;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.PerformanceMetricRepo;
import io.oddsmaker.control.jpa.RevenueAnalysisRepo;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.SessionAnalysisRepo;
import io.oddsmaker.control.jpa.SocialAnalyticsRepo;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板类 Service 测试：商业化分析、风控大屏、Webhook（空数据源下的结构组装）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看板类 Service 测试")
class DashboardServicesTest {

    // ===== AnalyticsService =====

    @Mock
    private RevenueAnalysisRepo revenueAnalysisRepo;

    @Mock
    private AdAnalysisRepo adAnalysisRepo;

    @Mock
    private SessionAnalysisRepo sessionAnalysisRepo;

    @Mock
    private PerformanceMetricRepo performanceMetricRepo;

    @Mock
    private SocialAnalyticsRepo socialAnalyticsRepo;

    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    @DisplayName("Analytics：收入概览聚合 + 各趋势/分组组装")
    void analyticsAssembly() {
        LocalDate d = LocalDate.of(2026, 9, 1);
        lenient().when(revenueAnalysisRepo.getDailyRevenueSummary(eq("g"), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{d, 100.0, 80.0, 20.0}));
        when(revenueAnalysisRepo.findByGameIdAndAnalysisDateBetween(eq("g"), any(), any())).thenReturn(List.of());
        lenient().when(revenueAnalysisRepo.getRevenueByPlatform(eq("g"), any()))
            .thenReturn(List.<Object[]>of(new Object[]{"ios", 100.0, 5.0, 25.0}));
        lenient().when(adAnalysisRepo.getAdPerformanceByNetwork(eq("g"), any(), any())).thenReturn(new java.util.ArrayList<>());
        lenient().when(sessionAnalysisRepo.getSessionTrends(eq("g"), any(), any())).thenReturn(new java.util.ArrayList<>());
        lenient().when(performanceMetricRepo.getPerformanceSummary(eq("g"), any(), any())).thenReturn(new java.util.ArrayList<>());
        lenient().when(performanceMetricRepo.getCrashGroups("g")).thenReturn(List.of());
        lenient().when(socialAnalyticsRepo.getSocialTrends(eq("g"), any(), any())).thenReturn(new java.util.ArrayList<>());
        lenient().when(socialAnalyticsRepo.getSocialRetentionImpact(eq("g"), any())).thenReturn(new Object[]{1, 2, 3, 4});

        Map<String, Object> overview = analyticsService.getRevenueOverview("g", d, d);
        assertEquals(100.0, overview.get("totalRevenue"));
        assertEquals(1, overview.get("days"));

        assertNotNull(analyticsService.getArpuTrends("g", d, d));
        assertNotNull(analyticsService.getRevenueByPlatform("g", d));
        assertNotNull(analyticsService.getAdPerformanceOverview("g", d, d));
        assertNotNull(analyticsService.getAdPerformanceByNetwork("g", d, d));
        assertNotNull(analyticsService.getSessionOverview("g", d, d));
        assertNotNull(analyticsService.getSessionTrends("g", d, d));
        assertNotNull(analyticsService.getPerformanceOverview("g", LocalDateTime.now(), LocalDateTime.now()));
        assertNotNull(analyticsService.getCrashGroups("g"));
        assertNotNull(analyticsService.getSocialOverview("g", d, d));
        assertNotNull(analyticsService.getSocialRetentionImpact("g", d));
    }

    // ===== RiskDashboardService =====

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @InjectMocks
    private RiskDashboardService riskDashboardService;

    @Test
    @DisplayName("风控大屏：概览/趋势/规则/目标/统计组装")
    void riskDashboardAssembly() {
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        lenient().when(riskRuleRepo.findActiveByGameId("g")).thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndTimeRange(eq("g"), any(), any())).thenReturn(List.of());
        lenient().when(riskCaseRepo.findByRiskRuleId(anyString())).thenReturn(List.of());
        lenient().when(riskCaseRepo.findFrequentTargets(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        lenient().when(riskCaseRepo.countByGameIdSince(eq("g"), any())).thenReturn(0L);
        lenient().when(blockListRepo.findActiveBlocks(eq("g"), any())).thenReturn(List.of());
        lenient().when(flinkJobRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(flinkJobRepo.findRunningJobs("g")).thenReturn(List.of());

        assertNotNull(riskDashboardService.getOverview("g", since));
        assertNotNull(riskDashboardService.getRecentCases("g", 20));
        assertNotNull(riskDashboardService.getRiskTrends("g", since, 24));
        assertNotNull(riskDashboardService.getHighRiskTargets("g", since, 10));
        assertNotNull(riskDashboardService.getRulePerformance("g"));
        assertNotNull(riskDashboardService.getBlockStats("g"));
        assertNotNull(riskDashboardService.getJobStats("g"));
        Map<String, Object> dashboard = riskDashboardService.getDashboard("g", since);
        assertNotNull(dashboard);
    }

    // ===== WebhookService =====

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @InjectMocks
    private WebhookService webhookService;

    @Test
    @DisplayName("Webhook：配置查询/日志/统计与测试触发组装")
    void webhookAssembly() {
        lenient().when(webhookConfigRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(webhookLogRepo.findByWebhookConfigId("c1")).thenReturn(List.of());

        assertNotNull(webhookService.getGameConfigs("g"));
        io.oddsmaker.control.jpa.WebhookConfigEntity config = new io.oddsmaker.control.jpa.WebhookConfigEntity();
        config.id = "c1";
        config.gameId = "g";
        lenient().when(webhookConfigRepo.findById("c1")).thenReturn(java.util.Optional.of(config));
        assertNotNull(webhookService.getConfig("c1"));
        assertNotNull(webhookService.getWebhookStats("g"));
        assertNotNull(webhookService.getWebhookLogs("c1"));
        assertNotNull(webhookService.getWebhookStats("g"));
    }

    // ===== ReviewQueueService =====

    @Mock
    private ReviewQueueRepo reviewQueueRepo;

    @InjectMocks
    private ReviewQueueService reviewQueueService;

    @Test
    @DisplayName("审核队列：队列查询与统计组装")
    void reviewQueueAssembly() {
        lenient().when(reviewQueueRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(reviewQueueRepo.findPendingByGameId("g")).thenReturn(List.of());
        lenient().when(reviewQueueRepo.findHighPriority(eq("g"), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of());

        assertNotNull(reviewQueueService.getGameQueue("g"));
        assertNotNull(reviewQueueService.getPendingItems("g"));
        assertNotNull(reviewQueueService.getHighPriorityItems("g", 70));
        assertNotNull(reviewQueueService.getQueueStats("g"));
    }
}
