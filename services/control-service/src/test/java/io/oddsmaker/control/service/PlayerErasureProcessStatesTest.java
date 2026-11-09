package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：process 状态机——全链路、PG 失败 FAILED、CH 失败 PARTIAL、PARTIAL 重入只走 CH、终态拒绝、无 pgDone 拒绝、坏 JSON 容忍、审计失败 PARTIAL。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureProcessStatesTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("process 全链：PENDING → PG 段中间落库 → CH → COMPLETED + webhook + 审计")
    void processFullChain() throws Exception {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubPgDeletes();
        stubChHappyPath(new ArrayList<>());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        assertThat(req.completedAt).isNotNull();
        JsonNode summary = objectMapper.readTree(req.executionSummary);
        assertThat(summary.get("pgDone").asBoolean()).isTrue();
        assertThat(summary.get("pg").get("identities").asInt()).isEqualTo(0);
        assertThat(summary.get("ch").get("status").asText()).isEqualTo("EXECUTED");
        verify(requestRepo, atLeastOnce()).save(req);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).sendCustomWebhook(eq("g1"), eq("player_data_erasure"), payload.capture());
        assertThat(payload.getValue().get("event")).isEqualTo("player_data_erasure");
        assertThat(payload.getValue().get("requestId")).isEqualTo(req.id);
        assertThat(payload.getValue().get("status")).isEqualTo("COMPLETED");
        assertThat(payload.getValue().get("ok")).isEqualTo(true);
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.DELETE),
            eq("player_erasure_request"), eq(req.id), eq("p1"), anyString(),
            eq(AuditLogEntity.AuditResult.SUCCESS), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PG 段异常 → FAILED（图谱已删无法重试）")
    void processPgFailureBecomesFailed() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenThrow(new RuntimeException("disk io error"));

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.FAILED);
        assertThat(req.errorMessage).contains("disk io error");
        verify(webhookService).sendCustomWebhook(eq("g1"), eq("player_data_erasure"), any());
    }

    @Test
    @DisplayName("CH 段异常且 pgDone → PARTIAL（自动重试）")
    void processChFailureBecomesPartial() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubPgDeletes();
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 0L)));
        when(clickHouse.execute(any(ConnectionCallback.class))).thenThrow(new RuntimeException("ch error"));

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        assertThat(req.errorMessage).isNotNull();
    }

    @Test
    @DisplayName("PARTIAL 重入只走 CH 段：PG Repo 零交互")
    void processPartialRetrySkipsPg() throws Exception {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.PARTIAL;
        req.executionSummary = "{\"pgDone\":true,\"retries\":1,\"pg\":{\"identities\":2}}";
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubChHappyPath(new ArrayList<>());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        verifyNoInteractions(identityRepo, identityLinkRepo, loginLogRepo, paymentRepo);
    }

    @Test
    @DisplayName("COMPLETED/PROCESSING 状态不允许 process")
    void processRejectsTerminalStates() {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.COMPLETED;
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        assertThatThrownBy(() -> service.process(req.id))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PARTIAL 且无 pgDone 的请求拒绝重试（防状态错乱）")
    void processPartialWithoutPgDoneRejected() {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.PARTIAL;
        req.executionSummary = "{\"retries\":1}";
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        assertThatThrownBy(() -> service.process(req.id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pgDone");
    }

    @Test
    @DisplayName("resolvedIdentities 坏 JSON → FAILED；executionSummary 坏 JSON 仍可正常处理")
    void processToleratesBadJsonFields() {
        PlayerErasureRequestEntity badResolved = savedRequest();
        badResolved.resolvedIdentities = "{bad json";
        when(requestRepo.findById(badResolved.id)).thenReturn(Optional.of(badResolved));
        when(clickHouse.isAvailable()).thenReturn(false);
        service.process(badResolved.id);
        assertThat(badResolved.status).isEqualTo(PlayerErasureRequestEntity.Status.FAILED);
        assertThat(badResolved.errorMessage).contains("resolvedIdentities");

        PlayerErasureRequestEntity badSummary = savedRequest();
        badSummary.id = "per_second0000000000000000";
        badSummary.executionSummary = "{bad json";
        when(requestRepo.findById(badSummary.id)).thenReturn(Optional.of(badSummary));
        stubPgDeletes();
        service.process(badSummary.id);
        assertThat(badSummary.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
    }

    @Test
    @DisplayName("CH 段成功但审计写失败 → PARTIAL（清洗已落库不回滚，重试幂等）")
    void processAuditFailureAfterChBecomesPartial() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();
        when(auditLog.log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("audit store down"))
            .thenReturn(null);   // catch 块内的失败审计写放行，避免异常从 catch 逃逸

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        assertThat(req.errorMessage).contains("audit store down");
        // 重试计数递增：catch 路径 canRetry=true 分支
        assertThat(req.executionSummary).contains("\"retries\":1");
    }

}
