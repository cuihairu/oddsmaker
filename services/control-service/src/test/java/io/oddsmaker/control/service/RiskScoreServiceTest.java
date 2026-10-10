package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RiskScoreService：CH 最新快照读取（ORDER BY updated_at DESC LIMIT 1）、
 * reasons JSON 数组解析为 {ruleId,contribution}、CH 未配置抛 CH_UNAVAILABLE。
 */
class RiskScoreServiceTest {

    private ClickHouseClient clickHouseClient;
    private RiskScoreService service;

    @BeforeEach
    void setUp() {
        clickHouseClient = mock(ClickHouseClient.class);
        service = new RiskScoreService(clickHouseClient, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private void stubRow(Object score, Object reasons, Object updatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject_type", "player_id");
        row.put("subject_id", "p_100");
        row.put("score", score);
        row.put("reasons", reasons);
        row.put("updated_at", updatedAt);
        when(clickHouseClient.query(anyString(), any(), any(), any()))
                .thenReturn(List.of(row));
    }

    @Test
    void latestParsesSnapshotAndReasons() {
        when(clickHouseClient.isAvailable()).thenReturn(true);
        stubRow(85, List.of("{\"rule_id\":\"rr_a\",\"contribution\":60}",
                "{\"rule_id\":\"rr_b\",\"contribution\":85}"), "2026-10-10 03:00:00");

        Map<String, Object> res = service.latest("g1", "player_id", "p_100");

        assertEquals(Boolean.TRUE, res.get("found"));
        assertEquals(85, res.get("score"));
        assertEquals("2026-10-10 03:00:00", res.get("updatedAt"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) res.get("reasons");
        assertEquals(2, reasons.size());
        assertEquals("rr_a", reasons.get(0).get("ruleId"));
        assertEquals(60, reasons.get(0).get("contribution"));
        assertEquals("rr_b", reasons.get(1).get("ruleId"));
        assertEquals(85, reasons.get(1).get("contribution"));
        // 最新快照语义：ORDER BY updated_at DESC LIMIT 1
        verify(clickHouseClient).query(contains("ORDER BY updated_at DESC LIMIT 1"),
                eq("g1"), eq("player_id"), eq("p_100"));
    }

    @Test
    void latestReturnsFoundFalseWhenNoRows() {
        when(clickHouseClient.isAvailable()).thenReturn(true);
        when(clickHouseClient.query(anyString(), any(), any(), any())).thenReturn(List.of());

        Map<String, Object> res = service.latest("g1", "player_id", "p_missing");

        assertEquals(Boolean.FALSE, res.get("found"));
        assertFalse(res.containsKey("score"));
    }

    @Test
    void latestThrowsWhenChUnavailable() {
        when(clickHouseClient.isAvailable()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.latest("g1", "player_id", "p_100"));
        assertTrue(ex.getMessage().startsWith("CH_UNAVAILABLE"));
        verify(clickHouseClient, never()).query(anyString(), any(), any(), any());
    }

    @Test
    void latestRejectsBlankSubject() {
        assertThrows(IllegalArgumentException.class, () -> service.latest("g1", " ", "p_100"));
        assertThrows(IllegalArgumentException.class, () -> service.latest("g1", "player_id", null));
        verifyNoInteractions(clickHouseClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void latestParsesSqlArrayReasons() throws Exception {
        when(clickHouseClient.isAvailable()).thenReturn(true);
        // CH JDBC 数组列可能以 java.sql.Array 返回
        Array arr = mock(Array.class);
        when(arr.getArray()).thenReturn(new String[]{"{\"rule_id\":\"rr_c\",\"contribution\":40}"});
        stubRow(40, arr, "2026-10-10 04:00:00");

        Map<String, Object> res = service.latest("g1", "player_id", "p_100");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) res.get("reasons");
        assertEquals(1, reasons.size());
        assertEquals("rr_c", reasons.get(0).get("ruleId"));
        assertEquals(40, reasons.get(0).get("contribution"));
    }

    @Test
    void latestSkipsBadReasonEntries() {
        when(clickHouseClient.isAvailable()).thenReturn(true);
        stubRow(60, List.of("not-json", "{\"rule_id\":\"rr_d\",\"contribution\":60}"), "2026-10-10 05:00:00");

        Map<String, Object> res = service.latest("g1", "player_id", "p_100");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) res.get("reasons");
        assertEquals(1, reasons.size());
        assertEquals("rr_d", reasons.get(0).get("ruleId"));
    }

    @Test
    @DisplayName("不加 Spring 事务：CH 读抛异常不得把调用方 joined 事务标 rollback-only（案例详情降级 500 防回归）")
    void noSpringTransactionBoundary() throws NoSuchMethodException {
        assertFalse(AnnotatedElementUtils.hasAnnotation(RiskScoreService.class, Transactional.class));
        var latest = RiskScoreService.class.getMethod("latest", String.class, String.class, String.class);
        assertFalse(AnnotatedElementUtils.hasAnnotation(latest, Transactional.class));
    }
}
