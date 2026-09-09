package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Crash 指标行映射纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("Crash 指标行映射")
class CrashMetricsAssemblerTest {

    @Test
    void toTopGroups_sortsAndMapsFields() {
        List<Map<String, Object>> rows = List.of(
                Map.of("crash_group", "abc123", "sample_message", "NPE", "occurrences", 10L,
                        "affected_devices", 4L, "first_seen", Date.valueOf("2026-09-01"),
                        "last_seen", Date.valueOf("2026-09-08")),
                Map.of("crash_group", "def456", "sample_message", "", "occurrences", 30L,
                        "affected_devices", 9L, "first_seen", Date.valueOf("2026-09-02"),
                        "last_seen", Date.valueOf("2026-09-09")));

        List<Map<String, Object>> groups = CrashMetricsAssembler.toTopGroups(rows, 10);

        assertEquals(2, groups.size());
        assertEquals("def456", groups.get(0).get("crashGroup"));
        assertEquals(30L, groups.get(0).get("occurrences"));
        assertEquals("2026-09-02", groups.get(0).get("firstSeen"));
        assertEquals("abc123", groups.get(1).get("crashGroup"));
        assertEquals("NPE", groups.get(1).get("sampleMessage"));
    }

    @Test
    void toTopGroups_limitTruncates() {
        List<Map<String, Object>> rows = List.of(
                Map.of("crash_group", "a", "occurrences", 3L),
                Map.of("crash_group", "b", "occurrences", 2L),
                Map.of("crash_group", "c", "occurrences", 1L));

        assertEquals(2, CrashMetricsAssembler.toTopGroups(rows, 2).size());
    }

    @Test
    void toTrend_ordersByDateAndMapsFields() {
        List<Map<String, Object>> rows = List.of(
                Map.of("bucket", Date.valueOf("2026-09-08"), "crashes", 12L, "affected_devices", 5L),
                Map.of("bucket", Date.valueOf("2026-09-07"), "crashes", 8L, "affected_devices", 3L));

        List<Map<String, Object>> points = CrashMetricsAssembler.toTrend(rows);

        assertEquals(2, points.size());
        assertEquals("2026-09-07", points.get(0).get("date"));
        assertEquals(8L, points.get(0).get("crashes"));
        assertEquals(5L, points.get(1).get("affectedDevices"));
    }

    @Test
    void toVersionRates_keepsLatestDayAndAveragesRate() {
        List<Map<String, Object>> rows = List.of(
                Map.of("app_version", "1.2.0", "event_date", Date.valueOf("2026-09-08"),
                        "crash_devices", 10L, "active_devices", 100L, "crash_rate", 0.10),
                Map.of("app_version", "1.2.0", "event_date", Date.valueOf("2026-09-09"),
                        "crash_devices", 8L, "active_devices", 100L, "crash_rate", 0.08),
                Map.of("app_version", "1.1.0", "event_date", Date.valueOf("2026-09-09"),
                        "crash_devices", 50L, "active_devices", 100L, "crash_rate", 0.50));

        List<Map<String, Object>> versions = CrashMetricsAssembler.toVersionRates(rows);

        assertEquals(2, versions.size());
        Map<String, Object> v120 = versions.get(0);
        assertEquals("1.2.0", v120.get("appVersion"));
        // 最近一天口径
        assertEquals("2026-09-09", v120.get("lastDate"));
        assertEquals(8L, v120.get("crashDevices"));
        assertEquals(100L, v120.get("activeDevices"));
        // 窗口平均崩溃率
        assertEquals(0.09, v120.get("avgCrashRate"));
        assertEquals(0.5, versions.get(1).get("avgCrashRate"));
    }

    @Test
    void toVersionRates_emptyVersionNormalizedToUnknown() {
        List<Map<String, Object>> versions = CrashMetricsAssembler.toVersionRates(List.of(
                Map.of("app_version", "", "event_date", Date.valueOf("2026-09-09"),
                        "crash_devices", 1L, "active_devices", 10L, "crash_rate", 0.1)));
        assertEquals("unknown", versions.get(0).get("appVersion"));
    }
}
