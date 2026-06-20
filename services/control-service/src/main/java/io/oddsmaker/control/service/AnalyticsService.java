package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 分析服务
 * 提供收入、广告、会话、性能、社交分析功能
 */
@Service
@Transactional
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);

    @Autowired
    private RevenueAnalysisRepo revenueAnalysisRepo;

    @Autowired
    private AdAnalysisRepo adAnalysisRepo;

    @Autowired
    private SessionAnalysisRepo sessionAnalysisRepo;

    @Autowired
    private PerformanceMetricRepo performanceMetricRepo;

    @Autowired
    private SocialAnalyticsRepo socialAnalyticsRepo;

    // ==================== 收入分析 ====================

    /**
     * 获取收入概览
     */
    public Map<String, Object> getRevenueOverview(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> dailyRevenue = revenueAnalysisRepo.getDailyRevenueSummary(gameId, startDate, endDate);

        double totalRevenue = 0;
        double totalIapRevenue = 0;
        double totalAdRevenue = 0;

        for (Object[] row : dailyRevenue) {
            totalRevenue += row[1] != null ? ((Number) row[1]).doubleValue() : 0;
            totalIapRevenue += row[2] != null ? ((Number) row[2]).doubleValue() : 0;
            totalAdRevenue += row[3] != null ? ((Number) row[3]).doubleValue() : 0;
        }

        Map<String, Object> overview = new HashMap<>();
        overview.put("totalRevenue", totalRevenue);
        overview.put("iapRevenue", totalIapRevenue);
        overview.put("adRevenue", totalAdRevenue);
        overview.put("subscriptionRevenue", totalRevenue - totalIapRevenue - totalAdRevenue);
        overview.put("days", dailyRevenue.size());
        return overview;
    }

    /**
     * 获取 ARPU/ARPPU 趋势
     */
    public List<Map<String, Object>> getArpuTrends(String gameId, LocalDate startDate, LocalDate endDate) {
        List<RevenueAnalysisEntity> records = revenueAnalysisRepo.findByGameIdAndAnalysisDateBetween(
            gameId, startDate, endDate);

        List<Map<String, Object>> trends = new ArrayList<>();
        for (RevenueAnalysisEntity record : records) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", record.analysisDate);
            point.put("arpu", record.arpu);
            point.put("arppu", record.arppu);
            point.put("payingUsers", record.payingUsers);
            point.put("totalUsers", record.totalUsers);
            trends.add(point);
        }
        return trends;
    }

    /**
     * 获取按平台收入分布
     */
    public List<Map<String, Object>> getRevenueByPlatform(String gameId, LocalDate date) {
        List<Object[]> results = revenueAnalysisRepo.getRevenueByPlatform(gameId, date);

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("platform", row[0]);
            item.put("revenue", row[1]);
            item.put("arpu", row[2]);
            item.put("arppu", row[3]);
            distribution.add(item);
        }
        return distribution;
    }

    // ==================== 广告分析 ====================

    /**
     * 获取广告性能概览
     */
    public Map<String, Object> getAdPerformanceOverview(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> networkPerformance = adAnalysisRepo.getAdPerformanceByNetwork(
            gameId, startDate, endDate);

        double totalRevenue = 0;
        long totalImpressions = 0;
        double avgEcpm = 0;
        double avgFillRate = 0;
        int networkCount = 0;

        for (Object[] row : networkPerformance) {
            totalRevenue += row[1] != null ? ((Number) row[1]).doubleValue() : 0;
            totalImpressions += row[2] != null ? ((Number) row[2]).longValue() : 0;
            avgEcpm += row[3] != null ? ((Number) row[3]).doubleValue() : 0;
            avgFillRate += row[4] != null ? ((Number) row[4]).doubleValue() : 0;
            networkCount++;
        }

        Map<String, Object> overview = new HashMap<>();
        overview.put("totalRevenue", totalRevenue);
        overview.put("totalImpressions", totalImpressions);
        overview.put("avgEcpm", networkCount > 0 ? avgEcpm / networkCount : 0);
        overview.put("avgFillRate", networkCount > 0 ? avgFillRate / networkCount : 0);
        overview.put("networkCount", networkCount);
        return overview;
    }

    /**
     * 获取按广告网络性能
     */
    public List<Map<String, Object>> getAdPerformanceByNetwork(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> results = adAnalysisRepo.getAdPerformanceByNetwork(gameId, startDate, endDate);

        List<Map<String, Object>> performance = new ArrayList<>();
        for (Object[] row : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("network", row[0]);
            item.put("revenue", row[1]);
            item.put("impressions", row[2]);
            item.put("ecpm", row[3]);
            item.put("fillRate", row[4]);
            performance.add(item);
        }
        return performance;
    }

    // ==================== 会话分析 ====================

    /**
     * 获取会话概览
     */
    public Map<String, Object> getSessionOverview(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> sessionTrends = sessionAnalysisRepo.getSessionTrends(gameId, startDate, endDate);

        double avgDuration = 0;
        double avgEvents = 0;
        double avgBounceRate = 0;
        int days = sessionTrends.size();

        for (Object[] row : sessionTrends) {
            avgDuration += row[1] != null ? ((Number) row[1]).doubleValue() : 0;
            avgEvents += row[2] != null ? ((Number) row[2]).doubleValue() : 0;
            avgBounceRate += row[3] != null ? ((Number) row[3]).doubleValue() : 0;
        }

        Map<String, Object> overview = new HashMap<>();
        overview.put("avgSessionDuration", days > 0 ? avgDuration / days : 0);
        overview.put("avgEventsPerSession", days > 0 ? avgEvents / days : 0);
        overview.put("avgBounceRate", days > 0 ? avgBounceRate / days : 0);
        overview.put("days", days);
        return overview;
    }

    /**
     * 获取会话趋势
     */
    public List<Map<String, Object>> getSessionTrends(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> trends = sessionAnalysisRepo.getSessionTrends(gameId, startDate, endDate);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : trends) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", row[0]);
            point.put("avgDuration", row[1]);
            point.put("avgEvents", row[2]);
            point.put("bounceRate", row[3]);
            result.add(point);
        }
        return result;
    }

    // ==================== 性能监控 ====================

    /**
     * 获取性能概览
     */
    public Map<String, Object> getPerformanceOverview(String gameId, LocalDateTime startDate, LocalDateTime endDate) {
        List<Object[]> summary = performanceMetricRepo.getPerformanceSummary(gameId, startDate, endDate);

        Map<String, Object> overview = new HashMap<>();
        for (Object[] row : summary) {
            String metricType = row[0].toString();
            double avgValue = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
            long count = row[2] != null ? ((Number) row[2]).longValue() : 0;

            Map<String, Object> metric = new HashMap<>();
            metric.put("avgValue", avgValue);
            metric.put("count", count);
            overview.put(metricType, metric);
        }
        return overview;
    }

    /**
     * 获取崩溃分组
     */
    public List<Map<String, Object>> getCrashGroups(String gameId) {
        List<Object[]> groups = performanceMetricRepo.getCrashGroups(gameId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : groups) {
            Map<String, Object> group = new HashMap<>();
            group.put("crashHash", row[0]);
            group.put("count", row[1]);
            group.put("firstSeen", row[2]);
            group.put("lastSeen", row[3]);
            result.add(group);
        }
        return result;
    }

    // ==================== 社交分析 ====================

    /**
     * 获取社交概览
     */
    public Map<String, Object> getSocialOverview(String gameId, LocalDate startDate, LocalDate endDate) {
        List<Object[]> trends = socialAnalyticsRepo.getSocialTrends(gameId, startDate, endDate);

        long totalFriendships = 0;
        long totalGuilds = 0;
        double avgViralCoefficient = 0;
        int days = trends.size();

        for (Object[] row : trends) {
            totalFriendships += row[1] != null ? ((Number) row[1]).longValue() : 0;
            totalGuilds += row[2] != null ? ((Number) row[2]).longValue() : 0;
            avgViralCoefficient += row[3] != null ? ((Number) row[3]).doubleValue() : 0;
        }

        Map<String, Object> overview = new HashMap<>();
        overview.put("totalFriendships", totalFriendships);
        overview.put("totalGuilds", totalGuilds);
        overview.put("avgViralCoefficient", days > 0 ? avgViralCoefficient / days : 0);
        overview.put("days", days);
        return overview;
    }

    /**
     * 获取社交对留存的影响
     */
    public Map<String, Object> getSocialRetentionImpact(String gameId, LocalDate date) {
        Object[] impact = socialAnalyticsRepo.getSocialRetentionImpact(gameId, date);

        Map<String, Object> result = new HashMap<>();
        result.put("socialUsersD7Retention", impact[0] != null ? ((Number) impact[0]).doubleValue() : 0);
        result.put("nonSocialUsersD7Retention", impact[1] != null ? ((Number) impact[1]).doubleValue() : 0);

        double socialRet = result.get("socialUsersD7Retention") instanceof Number ?
            ((Number) result.get("socialUsersD7Retention")).doubleValue() : 0;
        double nonSocialRet = result.get("nonSocialUsersD7Retention") instanceof Number ?
            ((Number) result.get("nonSocialUsersD7Retention")).doubleValue() : 0;
        result.put("retentionLift", socialRet - nonSocialRet);
        return result;
    }
}
