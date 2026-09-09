package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 留存趋势报表行映射纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("留存趋势报表行映射")
class RetentionMetricsAssemblerTest {

    @Test
    void bucketFunction_mapsGranularityWhitelist() {
        assertEquals("", RetentionMetricsAssembler.bucketFunction("day"));
        assertEquals("", RetentionMetricsAssembler.bucketFunction(null));
        assertEquals("toMonday", RetentionMetricsAssembler.bucketFunction("week"));
        assertEquals("toStartOfMonth", RetentionMetricsAssembler.bucketFunction("month"));
    }

    @Test
    void toTrendPoints_pivotsAndComputesRates() {
        Date c1 = Date.valueOf("2026-09-01");
        Date c2 = Date.valueOf("2026-09-02");
        List<Map<String, Object>> rows = List.of(
                Map.of("cohort", c1, "d", 0, "users", 100L),
                Map.of("cohort", c1, "d", 1, "users", 40L),
                Map.of("cohort", c1, "d", 7, "users", 10L),
                Map.of("cohort", c1, "d", 30, "users", 5L),
                // 同 cohort 拆两行（SummingMergeTree 未合并场景）
                Map.of("cohort", c1, "d", 1, "users", 10L),
                Map.of("cohort", c2, "d", 0, "users", 50L));

        List<Map<String, Object>> points = RetentionMetricsAssembler.toTrendPoints(rows);

        assertEquals(2, points.size());
        Map<String, Object> p1 = points.get(0);
        assertEquals("2026-09-01", p1.get("cohort"));
        assertEquals(100L, p1.get("newUsers"));
        assertEquals(50L, p1.get("d1"));
        assertEquals(0.5, p1.get("d1Rate"));
        assertEquals(10L, p1.get("d7"));
        assertEquals(0.1, p1.get("d7Rate"));
        assertEquals(5L, p1.get("d30"));
        assertEquals(0.05, p1.get("d30Rate"));
        Map<String, Object> p2 = points.get(1);
        assertEquals("2026-09-02", p2.get("cohort"));
        assertEquals(0.0, p2.get("d1Rate"));
    }

    @Test
    void toTrendPoints_zeroNewUsersYieldsZeroRates() {
        List<Map<String, Object>> points = RetentionMetricsAssembler.toTrendPoints(
                List.of(Map.of("cohort", Date.valueOf("2026-09-01"), "d", 1, "users", 3L)));

        assertEquals(1, points.size());
        assertEquals(0L, points.get(0).get("newUsers"));
        assertEquals(0.0, points.get(0).get("d1Rate"));
    }

    @Test
    void toSummary_excludesImmatureCohortsFromD30() {
        String today = LocalDate.now().toString();
        String mature = LocalDate.now().minusDays(40).toString();
        List<Map<String, Object>> points = List.of(
                Map.of("cohort", mature, "newUsers", 100L, "d1Rate", 0.4, "d7Rate", 0.2, "d30Rate", 0.1),
                Map.of("cohort", today, "newUsers", 50L, "d1Rate", 0.6, "d7Rate", 0.3, "d30Rate", 0.0));

        Map<String, Object> summary = RetentionMetricsAssembler.toSummary(points, 30);

        assertEquals(150L, summary.get("totalNewUsers"));
        assertEquals(2, summary.get("cohorts"));
        assertEquals(0.5, summary.get("avgD1Rate"));
        assertEquals(0.25, summary.get("avgD7Rate"));
        // d30 只计成熟 cohort
        assertEquals(0.1, summary.get("avgD30Rate"));
        assertEquals(1, summary.get("matureCohortsD30"));
    }

    @Test
    void asDate_handlesDateAndTimestamp() {
        assertEquals("2026-09-01", RetentionMetricsAssembler.asDate(Date.valueOf("2026-09-01")));
        assertEquals("2026-09-01", RetentionMetricsAssembler.asDate(java.sql.Timestamp.valueOf("2026-09-01 10:00:00")));
        assertEquals("2026-09-01", RetentionMetricsAssembler.asDate(java.time.LocalDate.of(2026, 9, 1)));
        assertEquals("2026-09-01", RetentionMetricsAssembler.asDate(java.time.LocalDateTime.of(2026, 9, 1, 10, 0)));
        assertEquals("fallback", RetentionMetricsAssembler.asDate("fallback"));
        assertEquals("", RetentionMetricsAssembler.asDate(null));
    }
}
