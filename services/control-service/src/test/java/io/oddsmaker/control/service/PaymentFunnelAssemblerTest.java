package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 付费漏斗行映射纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("付费漏斗行映射")
class PaymentFunnelAssemblerTest {

    @Test
    void toCohortPoints_mergesAndComputesRatesWithMaturity() {
        String matureCutoff = LocalDate.now().minusDays(31).toString();
        String matureCohort = LocalDate.now().minusDays(60).toString();
        String freshCohort = LocalDate.now().minusDays(5).toString();
        List<Map<String, Object>> funnelRows = List.of(
                Map.of("cohort", Date.valueOf(matureCohort), "registered", 100L,
                        "first_pay", 20L, "second_pay", 8L),
                Map.of("cohort", Date.valueOf(freshCohort), "registered", 50L,
                        "first_pay", 10L, "second_pay", 2L));
        // 月留存行仅覆盖成熟 cohort
        List<Map<String, Object>> retainedRows = List.of(
                Map.of("cohort", Date.valueOf(matureCohort), "retained_30", 35L));

        List<Map<String, Object>> points =
                PaymentFunnelAssembler.toCohortPoints(funnelRows, retainedRows, matureCutoff);

        assertEquals(2, points.size());
        Map<String, Object> mature = points.get(0);
        assertEquals(100L, mature.get("registered"));
        assertEquals(20L, mature.get("firstPay"));
        assertEquals(8L, mature.get("secondPay"));
        assertEquals(35L, mature.get("retained30"));
        assertEquals(0.2, mature.get("firstPayRate"));
        assertEquals(0.08, mature.get("secondPayRate"));
        assertEquals(0.35, mature.get("retained30Rate"));
        assertTrue((Boolean) mature.get("mature"));
        Map<String, Object> fresh = points.get(1);
        assertEquals(0L, fresh.get("retained30"));
        assertEquals(false, fresh.get("mature"));
    }

    @Test
    void toFunnel_aggregatesAndMaturesRetained30Base() {
        String matureCutoff = LocalDate.now().minusDays(31).toString();
        List<Map<String, Object>> points = List.of(
                Map.of("cohort", "2026-07-01", "registered", 100L, "firstPay", 20L, "secondPay", 8L,
                        "retained30", 35L, "mature", true),
                Map.of("cohort", "2026-08-30", "registered", 50L, "firstPay", 10L, "secondPay", 2L,
                        "retained30", 0L, "mature", false));

        Map<String, Object> funnel = PaymentFunnelAssembler.toFunnel(points);

        assertEquals(150L, funnel.get("registered"));
        assertEquals(30L, funnel.get("firstPay"));
        assertEquals(10L, funnel.get("secondPay"));
        // retained30 只对成熟 cohort 计入分母
        assertEquals(35L, funnel.get("retained30"));
        assertEquals(100L, funnel.get("retained30Base"));
        assertEquals(0.2, funnel.get("firstPayRate"));
        assertEquals(Math.round((10.0 / 150.0) * 10000.0) / 10000.0, funnel.get("secondPayRate"));
        assertEquals(0.35, funnel.get("retained30Rate"));
        assertEquals(Math.round((10.0 / 30.0) * 10000.0) / 10000.0, funnel.get("firstToSecondRate"));
    }

    @Test
    void toFunnel_zeroBaseYieldsZeroRates() {
        Map<String, Object> funnel = PaymentFunnelAssembler.toFunnel(List.of());

        assertEquals(0L, funnel.get("registered"));
        assertEquals(0.0, funnel.get("firstPayRate"));
        assertEquals(0.0, funnel.get("retained30Rate"));
    }

    @Test
    void stepMetadata_exposesFourSteps() {
        List<Map<String, Object>> steps = PaymentFunnelAssembler.stepMetadata();
        assertEquals(4, steps.size());
        assertEquals("registered", steps.get(0).get("key"));
        assertEquals("注册", steps.get(0).get("label"));
        assertEquals("retained30", steps.get(3).get("key"));
        assertEquals("月留存", steps.get(3).get("label"));
    }
}
