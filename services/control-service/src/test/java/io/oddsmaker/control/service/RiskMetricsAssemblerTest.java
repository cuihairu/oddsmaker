package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 风控大屏指标行映射纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("风控大屏指标行映射")
class RiskMetricsAssemblerTest {

    @Test
    void bucketFunction_shortWindowUsesHour_longWindowUsesDay() {
        assertEquals("toStartOfHour", RiskMetricsAssembler.bucketFunction(24));
        assertEquals("toStartOfHour", RiskMetricsAssembler.bucketFunction(48));
        assertEquals("toStartOfDay", RiskMetricsAssembler.bucketFunction(49));
        assertEquals("toStartOfDay", RiskMetricsAssembler.bucketFunction(24 * 30));
    }

    @Test
    void pivotTrend_fillsMissingSeveritiesWithZero() {
        Timestamp bucket = Timestamp.from(Instant.parse("2026-09-08T09:00:00Z"));
        List<Map<String, Object>> rows = List.of(
                Map.of("bucket", bucket, "severity", "CRITICAL", "c", 2L),
                Map.of("bucket", bucket, "severity", "medium", "c", 7L));

        List<Map<String, Object>> points = RiskMetricsAssembler.pivotTrend(rows);

        assertEquals(1, points.size());
        Map<String, Object> point = points.get(0);
        assertEquals("2026-09-08T09:00:00Z", point.get("ts"));
        assertEquals(9L, point.get("total"));
        assertEquals(2L, point.get("critical"));
        assertEquals(0L, point.get("high"));
        assertEquals(7L, point.get("medium"));
        assertEquals(0L, point.get("low"));
    }

    @Test
    void pivotTrend_keepsBucketsOrderedAndSumsTotals() {
        Timestamp b1 = Timestamp.from(Instant.parse("2026-09-08T08:00:00Z"));
        Timestamp b2 = Timestamp.from(Instant.parse("2026-09-08T09:00:00Z"));
        List<Map<String, Object>> rows = List.of(
                Map.of("bucket", b2, "severity", "high", "c", 3L),
                Map.of("bucket", b1, "severity", "high", "c", 1L),
                Map.of("bucket", b2, "severity", "low", "c", 4L));

        List<Map<String, Object>> points = RiskMetricsAssembler.pivotTrend(rows);

        assertEquals(2, points.size());
        assertEquals("2026-09-08T08:00:00Z", points.get(0).get("ts"));
        assertEquals(1L, points.get(0).get("total"));
        assertEquals("2026-09-08T09:00:00Z", points.get(1).get("ts"));
        assertEquals(7L, points.get(1).get("total"));
    }

    @Test
    void toRuleHits_mapsFieldsAndDefaultsEmptyRuleToUnknown() {
        Timestamp lastHit = Timestamp.from(Instant.parse("2026-09-08T09:30:00Z"));
        List<Map<String, Object>> rows = List.of(
                Map.of("rule_id", "rr_freq", "risk_type", "FREQUENCY", "hits", 42L,
                        "subjects", 13L, "avg_score", 0.87d, "last_hit_at", lastHit),
                Map.of("rule_id", "", "risk_type", "THRESHOLD", "hits", 5L,
                        "subjects", 2L, "avg_score", 0.5d, "last_hit_at", lastHit));

        List<Map<String, Object>> rules = RiskMetricsAssembler.toRuleHits(rows);

        assertEquals(2, rules.size());
        assertEquals("rr_freq", rules.get(0).get("ruleId"));
        assertEquals("FREQUENCY", rules.get(0).get("riskType"));
        assertEquals(42L, rules.get(0).get("hits"));
        assertEquals(13L, rules.get(0).get("subjects"));
        assertEquals(0.87d, (double) rules.get(0).get("avgScore"), 0.0001);
        assertEquals("2026-09-08T09:30:00Z", rules.get(0).get("lastHitAt"));
        assertEquals("unknown", rules.get(1).get("ruleId"));
    }

    @Test
    void toBreakdown_sortsByCountDescAndNormalizesKeys() {
        List<Map<String, Object>> rows = List.of(
                Map.of("severity", "LOW", "c", 10L),
                Map.of("severity", "CRITICAL", "c", 2L),
                Map.of("severity", "", "c", 5L));

        List<Map<String, Object>> breakdown = RiskMetricsAssembler.toBreakdown(rows, "severity");

        assertEquals(3, breakdown.size());
        assertEquals("low", breakdown.get(0).get("severity"));
        assertEquals(10L, breakdown.get(0).get("count"));
        assertEquals("unknown", breakdown.get(1).get("severity"));
        assertEquals(5L, breakdown.get(1).get("count"));
        assertEquals("critical", breakdown.get(2).get("severity"));
        assertEquals(17L, RiskMetricsAssembler.sumCounts(breakdown));
    }

    @Test
    void toRecentActions_mapsAllFields() {
        Timestamp ts = Timestamp.from(Instant.parse("2026-09-08T09:00:00Z"));
        List<Map<String, Object>> rows = List.of(Map.of(
                "ts", ts,
                "risk_event_id", "re_1",
                "risk_case_id", "rc_1",
                "rule_id", "rr_x",
                "action", "block",
                "state", "blocked",
                "subject_type", "PLAYER",
                "subject_id", "user_9",
                "severity", "HIGH"));

        List<Map<String, Object>> recent = RiskMetricsAssembler.toRecentActions(rows);

        assertEquals(1, recent.size());
        Map<String, Object> action = recent.get(0);
        assertEquals("2026-09-08T09:00:00Z", action.get("ts"));
        assertEquals("re_1", action.get("riskEventId"));
        assertEquals("rc_1", action.get("riskCaseId"));
        assertEquals("rr_x", action.get("ruleId"));
        assertEquals("block", action.get("action"));
        assertEquals("blocked", action.get("state"));
        assertEquals("PLAYER", action.get("subjectType"));
        assertEquals("user_9", action.get("subjectId"));
        assertEquals("HIGH", action.get("severity"));
    }

    @Test
    void toIso_handlesNullAsEmptyString() {
        assertEquals("", RiskMetricsAssembler.toIso(null));
    }
}
