package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pLTV 乘数拟合纯逻辑测试。
 */
@DisplayName("pLTV 乘数拟合")
class LtvForecastAssemblerTest {

    private static final String TODAY = "2026-09-09";

    private static Map<String, Object> ltv(String cohort, int ageDay, double revenue) {
        return Map.of("cohort", java.sql.Date.valueOf(cohort), "age_day", ageDay, "revenue", revenue);
    }

    private static Map<String, Object> size(String cohort, long cohortSize) {
        return Map.of("cohort", java.sql.Date.valueOf(cohort), "cohort_size", cohortSize);
    }

    @Test
    void forecast_fitsMultiplierFromMatureCohortsOnly() {
        // 成熟 cohort（≤2026-08-10）：D7=100 D30=200 → 比率 2.0；D7=50 D30=120 → 2.4 → 乘数 2.2
        List<Map<String, Object>> ltvRows = List.of(
                ltv("2026-07-01", 0, 60.0), ltv("2026-07-01", 3, 40.0),      // cum7=100
                ltv("2026-07-01", 10, 60.0), ltv("2026-07-01", 25, 40.0),    // cum30=200 → 比率 2.0
                ltv("2026-08-01", 2, 50.0),                                   // cum7=50
                ltv("2026-08-01", 12, 70.0));                                 // cum30=120 → 比率 2.4
        // 未成熟 cohort：仅 D7 观测
        ltvRows = new java.util.ArrayList<>(ltvRows);
        ltvRows.add(ltv("2026-09-01", 0, 30.0));
        ltvRows.add(ltv("2026-09-01", 5, 20.0));                              // cum7=50

        Map<String, Object> out = LtvForecastAssembler.forecast(
                ltvRows,
                List.of(size("2026-07-01", 100), size("2026-08-01", 50), size("2026-09-01", 40)),
                TODAY);

        assertEquals(2.2, out.get("multiplier"));
        assertEquals(2, out.get("basedOnCohorts"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) out.get("points");
        assertEquals(3, points.size());

        Map<String, Object> july = points.get(0);
        assertTrue((Boolean) july.get("mature"));
        assertEquals(1.0, july.get("obsD7Arpu"));      // 100/100
        assertEquals(2.0, july.get("obsD30Arpu"));     // 200/100
        assertEquals(2.0, july.get("predictedD30Arpu"));

        Map<String, Object> september = points.get(2);
        assertFalse((Boolean) september.get("mature"));
        assertNull(september.get("obsD30Arpu"));
        assertEquals(1.25, september.get("obsD7Arpu"));             // 50/40
        assertEquals(2.75, september.get("predictedD30Arpu"));      // 1.25 × 2.2
    }

    @Test
    void forecast_noMatureCohortsYieldsZeroMultiplier() {
        Map<String, Object> out = LtvForecastAssembler.forecast(
                List.of(ltv("2026-09-01", 0, 10.0)),
                List.of(size("2026-09-01", 10)),
                TODAY);
        assertEquals(0.0, out.get("multiplier"));
        assertEquals(0, out.get("basedOnCohorts"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) out.get("points");
        assertEquals(0.0, points.get(0).get("predictedD30Arpu"));
    }

    @Test
    void forecast_skipsZeroSizeCohorts() {
        Map<String, Object> out = LtvForecastAssembler.forecast(
                List.of(), List.of(size("2026-09-01", 0)), TODAY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) out.get("points");
        assertTrue(points.isEmpty());
    }

    @Test
    void cumulate_buildsRunningTotalsPerCohort() {
        Map<String, TreeMap<Integer, Double>> cum = LtvForecastAssembler.cumulate(List.of(
                ltv("2026-09-01", 0, 10.0), ltv("2026-09-01", 1, 5.0), ltv("2026-09-01", 3, 5.0)));
        TreeMap<Integer, Double> cohort = cum.get("2026-09-01");
        assertEquals(3, cohort.size());
        assertEquals(10.0, LtvForecastAssembler.cumThrough(cohort, 0));
        assertEquals(15.0, LtvForecastAssembler.cumThrough(cohort, 2));  // floor 到 day1
        assertEquals(20.0, LtvForecastAssembler.cumThrough(cohort, 29));
        assertEquals(0.0, LtvForecastAssembler.cumThrough(cohort, -1));
    }

    @Test
    void dateHelpers() {
        assertEquals("2026-08-10", LtvForecastAssembler.minusDays(TODAY, 30));
        assertEquals(8, LtvForecastAssembler.ageDays("2026-09-01", TODAY));
        assertEquals(0, LtvForecastAssembler.ageDays("bad-date", TODAY));
    }
}
