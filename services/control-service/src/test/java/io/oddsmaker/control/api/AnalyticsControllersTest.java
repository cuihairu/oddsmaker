package io.oddsmaker.control.api;

import io.oddsmaker.control.service.AnalyticsService;
import io.oddsmaker.control.service.CohortService;
import io.oddsmaker.control.service.FunnelConfigService;
import io.oddsmaker.control.service.IdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分析域 Controller 测试：商业化/广告/会话（Analytics）、Cohort、漏斗、Identity。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("分析域 Controller 测试")
class AnalyticsControllersTest {

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private AnalyticsController controller;

    @Test
    @DisplayName("Analytics：11 个指标端点全部委托")
    void analyticsEndpoints() {
        LocalDate d = LocalDate.of(2026, 9, 1);
        LocalDateTime dt = LocalDateTime.of(2026, 9, 1, 0, 0);
        when(analyticsService.getCrashGroups("g")).thenReturn(java.util.List.of());

        assertEquals(200, controller.getRevenueOverview("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getArpuTrends("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getRevenueByPlatform("g", d).getStatusCode().value());
        assertEquals(200, controller.getAdPerformanceOverview("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getAdPerformanceByNetwork("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getSessionOverview("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getSessionTrends("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getPerformanceOverview("g", dt, dt).getStatusCode().value());
        assertEquals(200, controller.getCrashGroups("g").getStatusCode().value());
        assertEquals(200, controller.getSocialOverview("g", d, d).getStatusCode().value());
        assertEquals(200, controller.getSocialRetentionImpact("g", d).getStatusCode().value());
        verify(analyticsService).getRevenueOverview("g", d, d);
    }

    // ===== Cohort =====

    @Mock
    private CohortService cohortService;

    @InjectMocks
    private CohortController cohortController;

    @Test
    @DisplayName("Cohort：9 个端点委托")
    void cohortEndpoints() {
        assertEquals(200, cohortController.createCohort(new CohortController.CohortRequest()).getStatusCode().value());
        assertEquals(200, cohortController.getCohort("c1", "g").getStatusCode().value());
        assertEquals(200, cohortController.getGameCohorts("g").getStatusCode().value());
        assertEquals(200, cohortController.getCompletedCohorts("g").getStatusCode().value());
        assertEquals(200, cohortController.calculateCohort("c1", "g").getStatusCode().value());
        assertEquals(200, cohortController.getCohortResults("c1", "g").getStatusCode().value());
        assertEquals(200, cohortController.getCohortStats("g").getStatusCode().value());
        assertEquals(200, cohortController.searchCohorts("g", "q").getStatusCode().value());
        assertEquals(200, cohortController.getRecentCohorts("g").getStatusCode().value());
        verify(cohortService).getGameCohorts("g");
    }

    // ===== 漏斗 =====

    @Mock
    private FunnelConfigService funnelConfigService;

    @InjectMocks
    private FunnelController funnelController;

    @Test
    @DisplayName("漏斗：13 个端点委托")
    void funnelEndpoints() {
        assertEquals(200, funnelController.createFunnel(new io.oddsmaker.control.jpa.FunnelConfigEntity()).getStatusCode().value());
        assertEquals(200, funnelController.listFunnels("g", 0, 20, "createdAt", "desc").getStatusCode().value());
        assertEquals(200, funnelController.getFunnel("f1").getStatusCode().value());
        assertEquals(200, funnelController.updateFunnel("f1", new io.oddsmaker.control.jpa.FunnelConfigEntity()).getStatusCode().value());
        assertEquals(200, funnelController.deleteFunnel("f1").getStatusCode().value());
        FunnelController.ToggleRequest toggle = new FunnelController.ToggleRequest();
        assertEquals(200, funnelController.toggleFunnel("f1", toggle).getStatusCode().value());
        assertEquals(200, funnelController.searchFunnels("g", "q", 0, 20).getStatusCode().value());
        assertEquals(200, funnelController.getEnabledFunnels("g").getStatusCode().value());
        assertEquals(200, funnelController.getFunnelsByType("g", io.oddsmaker.control.jpa.FunnelConfigEntity.FunnelType.STANDARD).getStatusCode().value());
        assertEquals(200, funnelController.addStep("f1", new io.oddsmaker.control.jpa.FunnelStepEntity()).getStatusCode().value());
        assertEquals(200, funnelController.updateStep(1L, new io.oddsmaker.control.jpa.FunnelStepEntity()).getStatusCode().value());
        assertEquals(200, funnelController.deleteStep(1L).getStatusCode().value());
        assertEquals(200, funnelController.getFunnelStatistics("g").getStatusCode().value());
        verify(funnelConfigService).findEnabledByGameId("g");
    }

    // ===== Identity =====

    @Mock
    private IdentityService identityService;

    @InjectMocks
    private IdentityController identityController;

    @Test
    @DisplayName("Identity：6 个端点委托")
    void identityEndpoints() {
        assertEquals(404, identityController.getIdentity("g", "i1").getStatusCode().value());
        assertEquals(404, identityController.findByDevice("g", "d1").getStatusCode().value());
        assertEquals(404, identityController.findByPlayer("g", "p1").getStatusCode().value());
        assertEquals(0, identityController.findByUser("g", "u1").size());
        assertEquals(0, identityController.findByIdentifier("g", "device", "d1").size());
        assertEquals(0, identityController.getLinks("i1", "g").size());
        verify(identityService).findByPlayer("g", "p1");
    }

    // ===== 异常处理 =====

    @InjectMocks
    private PermissionExceptionHandler exceptionHandler;

    @Test
    @DisplayName("异常处理：SecurityException→403、非法参数→400")
    void exceptionMappings() {
        assertEquals(403, exceptionHandler.handleSecurity(new SecurityException("denied")).getStatusCode().value());
        assertEquals(400, exceptionHandler.handleBadRequest(new IllegalArgumentException("bad")).getStatusCode().value());
    }
}
