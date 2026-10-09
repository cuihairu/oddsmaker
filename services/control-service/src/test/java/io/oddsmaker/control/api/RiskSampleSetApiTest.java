package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RiskSampleSetEntity;
import io.oddsmaker.control.jpa.RiskSampleSetRepo;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.RiskSampleSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 样本集 API：读走 game:read、写/删走 risk:manage，鉴权与委托对齐实验室其余端点。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("策略实验室样本集 API 测试")
class RiskSampleSetApiTest {

    private static final String GAME = "game_demo";

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RiskSampleSetRepo repo;

    @Mock
    private AuditLogService auditLog;

    private RiskSampleSetService service;
    private RiskSampleSetController controller;

    @BeforeEach
    void setUp() {
        service = new RiskSampleSetService(repo, new com.fasterxml.jackson.databind.ObjectMapper(), auditLog);
        controller = new RiskSampleSetController(service, accessGuard);
    }

    private RiskSampleSetEntity stored() {
        RiskSampleSetEntity e = new RiskSampleSetEntity();
        e.id = "rss_0123456789abcdef01234567";
        e.gameId = GAME;
        e.name = "基线样本";
        e.samples = "[{\"eventId\":\"evt-1\",\"amount\":150000}]";
        e.sampleCount = 1;
        e.createdBy = "op1";
        e.createdAt = LocalDateTime.parse("2026-10-09T10:00:00");
        return e;
    }

    @Test
    void listGuardsGameRead() {
        when(repo.findByGameIdOrderByCreatedAtDesc(GAME)).thenReturn(List.of(stored()));

        List<Map<String, Object>> rows = controller.list(GAME);

        verify(accessGuard).requireGamePermission(GAME, "game:read");
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).containsKey("samples"));
    }

    @Test
    void createGuardsRiskManageAndStores() {
        RiskSampleSetController.CreateRequest req = new RiskSampleSetController.CreateRequest();
        req.name = "基线样本";
        req.samples = List.of(Map.of("eventId", "evt-1", "amount", 150000));

        Map<String, Object> created = controller.create(GAME, req);

        verify(accessGuard).requireGamePermission(GAME, "risk:manage");
        assertEquals("基线样本", created.get("name"));
        assertTrue(created.get("id").toString().startsWith("rss_"));
        verify(repo).save(any());
        verify(auditLog).log(eq(io.oddsmaker.control.jpa.AuditLogEntity.AuditAction.CREATE),
                eq("risk_sample_set"), anyString(), eq("基线样本"), anyString(),
                eq(io.oddsmaker.control.jpa.AuditLogEntity.AuditResult.SUCCESS),
                eq("api"), isNull(), isNull(), isNull(), isNull(), anyMap());
    }

    @Test
    void getGuardsGameReadAndReturnsSamples() {
        when(repo.findById("rss_0123456789abcdef01234567"))
                .thenReturn(java.util.Optional.of(stored()));

        Map<String, Object> detail = controller.get(GAME, "rss_0123456789abcdef01234567");

        verify(accessGuard).requireGamePermission(GAME, "game:read");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) detail.get("samples");
        assertEquals(1, samples.size());
        assertEquals("evt-1", samples.get(0).get("eventId"));
    }

    @Test
    void deleteGuardsRiskManage() {
        when(repo.findById("rss_0123456789abcdef01234567"))
                .thenReturn(java.util.Optional.of(stored()));

        Map<String, Object> res = controller.delete(GAME, "rss_0123456789abcdef01234567");

        verify(accessGuard).requireGamePermission(GAME, "risk:manage");
        assertEquals(Boolean.TRUE, res.get("deleted"));
        verify(repo).delete(any());
    }
}
