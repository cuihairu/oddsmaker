package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AdAnalysisRepo;
import io.oddsmaker.control.jpa.PerformanceMetricRepo;
import io.oddsmaker.control.jpa.RevenueAnalysisEntity;
import io.oddsmaker.control.jpa.RevenueAnalysisRepo;
import io.oddsmaker.control.jpa.SessionAnalysisRepo;
import io.oddsmaker.control.jpa.SocialAnalyticsRepo;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：收入概览/ARPU 趋势/平台分布/广告概览与网络明细、会话概览趋势/性能概览/崩溃分组/社交概览与留存影响。与 AnalyticsServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesAnalyticsTest {

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
    @DisplayName("分析：收入概览/ARPU 趋势/平台分布与广告概览、网络明细")
    void analyticsRevenueAndAd() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        lenient().when(revenueAnalysisRepo.getDailyRevenueSummary("g", start, end)).thenReturn(List.of(
            new Object[]{start, 100.0, 60.0, 30.0},
            new Object[]{end, null, null, null}));

        Map<String, Object> overview = analyticsService.getRevenueOverview("g", start, end);
        assertEquals(100.0, overview.get("totalRevenue"));
        assertEquals(60.0, overview.get("iapRevenue"));
        assertEquals(30.0, overview.get("adRevenue"));
        assertEquals(10.0, overview.get("subscriptionRevenue"));
        assertEquals(2, overview.get("days"));

        RevenueAnalysisEntity rec = new RevenueAnalysisEntity();
        rec.gameId = "g";
        rec.analysisDate = start;
        rec.arpu = 1.5;
        rec.arppu = 15.0;
        rec.payingUsers = 10L;
        rec.totalUsers = 100L;
        lenient().when(revenueAnalysisRepo.findByGameIdAndAnalysisDateBetween("g", start, end)).thenReturn(List.of(rec));
        List<Map<String, Object>> trends = analyticsService.getArpuTrends("g", start, end);
        assertEquals(1, trends.size());
        assertEquals(1.5, trends.get(0).get("arpu"));
        assertEquals(10L, trends.get(0).get("payingUsers"));

        lenient().when(revenueAnalysisRepo.getRevenueByPlatform("g", start)).thenReturn(List.of(
            new Object[]{"ios", 80.0, 1.0, 10.0},
            new Object[]{"android", 20.0, 0.5, 5.0}));
        List<Map<String, Object>> byPlatform = analyticsService.getRevenueByPlatform("g", start);
        assertEquals(2, byPlatform.size());
        assertEquals("ios", byPlatform.get(0).get("platform"));

        lenient().when(adAnalysisRepo.getAdPerformanceByNetwork("g", start, end)).thenReturn(List.of(
            new Object[]{"admob", 50.0, 1000L, 5.0, 0.9},
            new Object[]{"unity", null, null, null, null}));
        Map<String, Object> adOverview = analyticsService.getAdPerformanceOverview("g", start, end);
        assertEquals(50.0, adOverview.get("totalRevenue"));
        assertEquals(1000L, adOverview.get("totalImpressions"));
        assertEquals(2, adOverview.get("networkCount"));
        assertEquals(2.5, adOverview.get("avgEcpm"));

        List<Map<String, Object>> networks = analyticsService.getAdPerformanceByNetwork("g", start, end);
        assertEquals(2, networks.size());
        assertEquals("admob", networks.get(0).get("network"));
        assertEquals(1000L, networks.get(0).get("impressions"));
    }

    @Test
    @DisplayName("分析：会话概览与趋势/性能概览/崩溃分组/社交概览与留存影响")
    void analyticsSessionPerformanceSocial() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 2);
        LocalDateTime tsStart = start.atStartOfDay();
        LocalDateTime tsEnd = end.atStartOfDay().plusDays(1);

        lenient().when(sessionAnalysisRepo.getSessionTrends("g", start, end)).thenReturn(List.of(
            new Object[]{start, 30.0, 12.0, 0.2},
            new Object[]{end, 60.0, 24.0, null}));
        Map<String, Object> sessionOverview = analyticsService.getSessionOverview("g", start, end);
        assertEquals(2, sessionOverview.get("days"));
        assertEquals(45.0, sessionOverview.get("avgSessionDuration"));
        assertEquals(18.0, sessionOverview.get("avgEventsPerSession"));
        assertEquals(0.1, sessionOverview.get("avgBounceRate"));

        List<Map<String, Object>> sessionTrends = analyticsService.getSessionTrends("g", start, end);
        assertEquals(2, sessionTrends.size());
        assertEquals(30.0, sessionTrends.get(0).get("avgDuration"));

        lenient().when(performanceMetricRepo.getPerformanceSummary("g", tsStart, tsEnd)).thenReturn(List.of(
            new Object[]{"fps", 55.5, 100L},
            new Object[]{"memory", null, null}));
        Map<String, Object> perf = analyticsService.getPerformanceOverview("g", tsStart, tsEnd);
        Map<?, ?> fps = (Map<?, ?>) perf.get("fps");
        assertEquals(55.5, fps.get("avgValue"));
        assertEquals(100L, fps.get("count"));
        Map<?, ?> memory = (Map<?, ?>) perf.get("memory");
        assertEquals(0.0, memory.get("avgValue"));

        lenient().when(performanceMetricRepo.getCrashGroups("g")).thenReturn(List.<Object[]>of(
            new Object[]{"hash-1", 10L, tsStart, tsEnd}));
        List<Map<String, Object>> crashes = analyticsService.getCrashGroups("g");
        assertEquals(1, crashes.size());
        assertEquals("hash-1", crashes.get(0).get("crashHash"));

        lenient().when(socialAnalyticsRepo.getSocialTrends("g", start, end)).thenReturn(List.of(
            new Object[]{start, 100L, 10L, 0.5},
            new Object[]{end, 50L, 5L, 0.3}));
        Map<String, Object> social = analyticsService.getSocialOverview("g", start, end);
        assertEquals(150L, social.get("totalFriendships"));
        assertEquals(15L, social.get("totalGuilds"));
        assertEquals(0.4, (double) social.get("avgViralCoefficient"), 1e-9);

        lenient().when(socialAnalyticsRepo.getSocialRetentionImpact("g", start)).thenReturn(new Object[]{0.4, 0.25});
        Map<String, Object> impact = analyticsService.getSocialRetentionImpact("g", start);
        assertEquals(0.4, impact.get("socialUsersD7Retention"));
        assertEquals(0.25, impact.get("nonSocialUsersD7Retention"));
        assertEquals(0.15, (double) impact.get("retentionLift"), 1e-9);
    }

}
