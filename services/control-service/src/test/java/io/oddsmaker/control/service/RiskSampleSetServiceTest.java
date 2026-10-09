package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.RiskSampleSetEntity;
import io.oddsmaker.control.jpa.RiskSampleSetRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RiskSampleSetService：留档写入校验与 dry-run 同款（amount/features 可数值化）、
 * 同游戏同名唯一、列表不带样本、详情带解析后 samples、删除审计。
 */
class RiskSampleSetServiceTest {

    private RiskSampleSetRepo repo;
    private AuditLogService auditLog;
    private RiskSampleSetService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(RiskSampleSetRepo.class);
        auditLog = mock(AuditLogService.class);
        service = new RiskSampleSetService(repo, objectMapper, auditLog);
    }

    private Map<String, Object> sample(String eventId, Object amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("amount", amount);
        return m;
    }

    private RiskSampleSetEntity stored() {
        RiskSampleSetEntity e = new RiskSampleSetEntity();
        e.id = "rss_0123456789abcdef01234567";
        e.gameId = "g1";
        e.name = "大额充值基线";
        e.description = "改阈值前留档";
        e.samples = "[{\"eventId\":\"evt-1\",\"amount\":150000}]";
        e.sampleCount = 1;
        e.createdBy = "op1";
        e.createdAt = LocalDateTime.parse("2026-10-09T10:00:00");
        return e;
    }

    @Test
    void createPersistsValidatedSamplesAndAudits() {
        when(repo.existsByGameIdAndName("g1", "基线样本")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> created = service.create("g1", "基线样本", "说明",
                List.of(sample("evt-1", 150000), sample("evt-2", "2000")), "op1");

        assertEquals("基线样本", created.get("name"));
        assertEquals(2, created.get("sampleCount"));
        // 预置时间戳保证响应 createdAt 非空（@CreationTimestamp 不回填内存值）
        assertNotNull(created.get("createdAt"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) created.get("samples");
        assertEquals(2, samples.size());
        assertEquals(150000, samples.get(0).get("amount"));

        ArgumentCaptor<RiskSampleSetEntity> captor = ArgumentCaptor.forClass(RiskSampleSetEntity.class);
        verify(repo).save(captor.capture());
        RiskSampleSetEntity e = captor.getValue();
        assertTrue(e.id.startsWith("rss_"));
        assertEquals("g1", e.gameId);
        assertNotNull(e.samples);
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.CREATE), eq("risk_sample_set"),
                eq(e.id), eq("基线样本"), anyString(), eq(AuditLogEntity.AuditResult.SUCCESS),
                eq("op1"), isNull(), isNull(), isNull(), isNull(), anyMap());
    }

    @Test
    void createRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("g1", "  ", null, List.of(sample("e1", 1)), "op1"));
        verifyNoInteractions(repo, auditLog);
    }

    @Test
    void createRejectsDuplicateName() {
        when(repo.existsByGameIdAndName("g1", "基线样本")).thenReturn(true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("g1", "基线样本", null, List.of(sample("e1", 1)), "op1"));
        assertTrue(ex.getMessage().contains("同名样本集已存在"));
        verify(repo, never()).save(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void createRejectsInvalidSamplesWithDryRunMessages() {
        // amount 非数值 / 条数超限 / 空列表：报错文案与 dry-run 校验同款
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> service.create("g1", "s", null, List.of(sample("e1", "abc")), "op1"));
        assertTrue(e1.getMessage().contains("amount 非数值"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> service.create("g1", "s", null, null, "op1"));
        assertEquals("samples 不能为空", e2.getMessage());

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(sample("e" + i, 1));
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> service.create("g1", "s", null, tooMany, "op1"));
        assertTrue(e3.getMessage().contains("超过单次上限"));
        verify(repo, never()).save(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    void listOmitsSamplesPayload() {
        when(repo.findByGameIdOrderByCreatedAtDesc("g1")).thenReturn(List.of(stored()));

        List<Map<String, Object>> rows = service.list("g1");

        assertEquals(1, rows.size());
        assertEquals("rss_0123456789abcdef01234567", rows.get(0).get("id"));
        assertEquals("大额充值基线", rows.get(0).get("name"));
        assertEquals(1, rows.get(0).get("sampleCount"));
        assertEquals(LocalDateTime.parse("2026-10-09T10:00:00"), rows.get(0).get("createdAt"));
        assertFalse(rows.get(0).containsKey("samples"));
    }

    @Test
    void getParsesSamples() {
        when(repo.findById("rss_0123456789abcdef01234567")).thenReturn(java.util.Optional.of(stored()));

        Map<String, Object> detail = service.get("g1", "rss_0123456789abcdef01234567");

        assertEquals("大额充值基线", detail.get("name"));
        assertEquals(1, detail.get("sampleCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) detail.get("samples");
        assertEquals(1, samples.size());
        assertEquals("evt-1", samples.get(0).get("eventId"));
        assertEquals(150000, samples.get(0).get("amount"));
    }

    @Test
    void getRejectsUnknownOrOtherGame() {
        when(repo.findById("rss_missing")).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.get("g1", "rss_missing"));

        RiskSampleSetEntity other = stored();
        other.gameId = "g2";
        when(repo.findById("rss_0123456789abcdef01234567")).thenReturn(java.util.Optional.of(other));
        assertThrows(IllegalArgumentException.class,
                () -> service.get("g1", "rss_0123456789abcdef01234567"));
    }

    @Test
    void deleteAuditsAndReturnsTrue() {
        when(repo.findById("rss_0123456789abcdef01234567")).thenReturn(java.util.Optional.of(stored()));

        assertTrue(service.delete("g1", "rss_0123456789abcdef01234567", "op1"));

        verify(repo).delete(any());
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.DELETE), eq("risk_sample_set"),
                eq("rss_0123456789abcdef01234567"), eq("大额充值基线"), anyString(),
                eq(AuditLogEntity.AuditResult.SUCCESS), eq("op1"), isNull(), isNull(), isNull(),
                isNull(), anyMap());
    }

    @Test
    void deleteReturnsFalseWhenMissingOrOtherGame() {
        when(repo.findById("rss_missing")).thenReturn(java.util.Optional.empty());
        assertFalse(service.delete("g1", "rss_missing", "op1"));

        RiskSampleSetEntity other = stored();
        other.gameId = "g2";
        when(repo.findById("rss_0123456789abcdef01234567")).thenReturn(java.util.Optional.of(other));
        assertFalse(service.delete("g1", "rss_0123456789abcdef01234567", "op1"));

        verify(repo, never()).delete(any());
        verifyNoInteractions(auditLog);
    }
}
