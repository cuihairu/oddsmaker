package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 财务报表行映射与 CSV 生成纯逻辑测试（输入为 ClickHouse queryForList 行）。
 */
@DisplayName("财务报表行映射与 CSV")
class FinanceMetricsAssemblerTest {

    @Test
    void toRows_mergesAndComputesDerivedMetrics() {
        List<Map<String, Object>> activityRows = List.of(
                Map.of("stat_date", Date.valueOf("2026-09-08"), "dau", 100L,
                        "revenue", new BigDecimal("250.00"), "payers", 10L, "orders", 15L));
        List<Map<String, Object>> newUserRows = List.of(
                Map.of("stat_date", Date.valueOf("2026-09-08"), "new_users", 40L),
                // 仅有新增、无活跃/收入行（当日尚无事件回流的边界）
                Map.of("stat_date", Date.valueOf("2026-09-09"), "new_users", 5L));

        List<Map<String, Object>> rows = FinanceMetricsAssembler.toRows(activityRows, newUserRows);

        assertEquals(2, rows.size());
        Map<String, Object> r = rows.get(0);
        assertEquals("2026-09-08", r.get("statDate"));
        assertEquals(40L, r.get("newUsers"));
        assertEquals(100L, r.get("dau"));
        assertEquals(250.0, r.get("revenue"));
        assertEquals(10L, r.get("payers"));
        assertEquals(15L, r.get("orders"));
        assertEquals(2.5, r.get("arpu"));          // 250/100
        assertEquals(25.0, r.get("arppu"));        // 250/10
        assertEquals(0.1, r.get("paymentRate"));   // 10/100
        assertEquals(0L, rows.get(1).get("dau"));
        assertEquals(0.0, rows.get(1).get("arpu"));
    }

    @Test
    void toSummary_aggregatesWeightedMetrics() {
        List<Map<String, Object>> rows = List.of(
                Map.of("newUsers", 40L, "dau", 100L, "revenue", 250.0, "payers", 10L, "orders", 15L),
                Map.of("newUsers", 60L, "dau", 200L, "revenue", 750.0, "payers", 30L, "orders", 45L));

        Map<String, Object> summary = FinanceMetricsAssembler.toSummary(rows);

        assertEquals(2, summary.get("periods"));
        assertEquals(100L, summary.get("totalNewUsers"));
        assertEquals(150.0, summary.get("avgDau"));
        assertEquals(1000.0, summary.get("totalRevenue"));
        assertEquals(60L, summary.get("totalOrders"));
        assertEquals(3.33, summary.get("arpu"));           // 1000/300
        assertEquals(25.0, summary.get("arppu"));          // 1000/40
        assertEquals(0.1333, summary.get("paymentRate"));  // 40/300
    }

    @Test
    void toCsv_rendersHeaderAndEscapesCells() {
        List<Map<String, Object>> rows = List.of(
                Map.of("statDate", "2026-09-08", "newUsers", 40L, "dau", 100L,
                        "revenue", 250.0, "payers", 10L, "orders", 15L,
                        "arpu", 2.5, "arppu", 25.0, "paymentRate", 0.1));

        String csv = FinanceMetricsAssembler.toCsv("game_demo", "day", rows);

        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertEquals("stat_date,new_users,dau,revenue,payers,orders,arpu,arppu,payment_rate", lines[0]);
        assertEquals("2026-09-08,40,100,250.0,10,15,2.5,25.0,0.1", lines[1]);
    }

    @Test
    void csvCell_escapesQuotesCommasAndNewlines() {
        assertEquals("plain", FinanceMetricsAssembler.csvCell("plain"));
        assertEquals("\"a,b\"", FinanceMetricsAssembler.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", FinanceMetricsAssembler.csvCell("say \"hi\""));
        assertEquals("\"line1\nline2\"", FinanceMetricsAssembler.csvCell("line1\nline2"));
        assertEquals("", FinanceMetricsAssembler.csvCell(null));
    }

    @Test
    void toCsv_escapesInjectedStatDate() {
        List<Map<String, Object>> rows = List.of(
                Map.of("statDate", "bad,\"date", "newUsers", 1L, "dau", 1L, "revenue", 0.0,
                        "payers", 0L, "orders", 0L, "arpu", 0.0, "arppu", 0.0, "paymentRate", 0.0));
        String csv = FinanceMetricsAssembler.toCsv("g", "day", rows);
        assertTrue(csv.contains("\"bad,\"\"date\""));
    }
}
