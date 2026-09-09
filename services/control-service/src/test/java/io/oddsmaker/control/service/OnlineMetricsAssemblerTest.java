package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实时在线指标行映射纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("实时在线指标行映射")
class OnlineMetricsAssemblerTest {

    @Test
    void toDimensionBreakdown_sortsDescAndNormalizesUnknown() {
        List<Map<String, Object>> rows = List.of(
                Map.of("dim", "ios", "online", 120L),
                Map.of("dim", "android", "online", 300L),
                Map.of("dim", "", "online", 5L));

        List<Map<String, Object>> breakdown = OnlineMetricsAssembler.toDimensionBreakdown(rows);

        assertEquals(3, breakdown.size());
        assertEquals("android", breakdown.get(0).get("key"));
        assertEquals(300L, breakdown.get(0).get("online"));
        assertEquals("ios", breakdown.get(1).get("key"));
        assertEquals("unknown", breakdown.get(2).get("key"));
    }

    @Test
    void toTrendPoints_sortsByBucketAscAndSums() {
        Timestamp b1 = Timestamp.from(Instant.parse("2026-09-09T08:01:00Z"));
        Timestamp b2 = Timestamp.from(Instant.parse("2026-09-09T08:02:00Z"));
        List<Map<String, Object>> rows = List.of(
                Map.of("bucket", b2, "online", 30L),
                Map.of("bucket", b1, "online", 10L));

        List<Map<String, Object>> points = OnlineMetricsAssembler.toTrendPoints(rows);

        assertEquals(2, points.size());
        assertEquals("2026-09-09T08:01:00Z", points.get(0).get("ts"));
        assertEquals(10L, points.get(0).get("online"));
        assertEquals("2026-09-09T08:02:00Z", points.get(1).get("ts"));
        assertEquals(30L, points.get(1).get("online"));
    }

    @Test
    void toTotal_readsFirstRowAndHandlesEmpty() {
        assertEquals(42L, OnlineMetricsAssembler.toTotal(List.of(Map.of("online", 42L))));
        assertEquals(0L, OnlineMetricsAssembler.toTotal(List.of()));
    }
}
