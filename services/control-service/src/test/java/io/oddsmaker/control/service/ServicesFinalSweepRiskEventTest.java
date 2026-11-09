package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：风险事件无动作跳过、未知动作告警、封禁时长矩阵与默认原因、身份扩展链接玩家封禁、审核无效级别回退、webhook 失败不阻断处置。与 RiskEventConsumerTest / BlockListServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风险事件消费 Service 深度测试（处置矩阵/链接封禁）")
class ServicesFinalSweepRiskEventTest {

    @Mock
    private AuditLogService auditLog;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private BlockListService blockListService;

    @Mock
    private WebhookService webhookService;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private RiskActionRecorder riskActionRecorder;

    private RiskEventConsumer riskEventConsumer;

@BeforeEach
void setUp() {
        riskEventConsumer = new RiskEventConsumer();
        ReflectionTestUtils.setField(riskEventConsumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(riskEventConsumer, "blockListService", blockListService);
        ReflectionTestUtils.setField(riskEventConsumer, "webhookService", webhookService);
        ReflectionTestUtils.setField(riskEventConsumer, "auditLogService", auditLog);
        ReflectionTestUtils.setField(riskEventConsumer, "identityLinkRepo", identityLinkRepo);
        ReflectionTestUtils.setField(riskEventConsumer, "riskCaseRepo", riskCaseRepo);
        ReflectionTestUtils.setField(riskEventConsumer, "reviewQueueService", reviewQueueService);
        ReflectionTestUtils.setField(riskEventConsumer, "riskActionRecorder", riskActionRecorder);
}

    private String riskEventJson(String action, String severity, String subjectType, String subjectId, String reason) {
        StringBuilder sb = new StringBuilder("{")
            .append("\"game_id\":\"game_demo\"")
            .append(",\"environment\":\"prod\"")
            .append(",\"risk_event_id\":\"re_sweep\"")
            .append(",\"source_event_id\":\"evt_sweep\"")
            .append(",\"rule_id\":\"rr_1\"")
            .append(",\"risk_type\":\"amount_threshold\"")
            .append(",\"subject_type\":\"").append(subjectType).append("\"")
            .append(",\"subject_id\":\"").append(subjectId).append("\"")
            .append(",\"score\":0.9")
            .append(",\"trust_level\":\"HIGH\"")   // B6 §4.3 BLOCK 门槛：默认高信任，BLOCK 才放行
            .append(",\"action\":").append(action == null ? "null" : "\"" + action + "\"");
        if (severity != null) {
            sb.append(",\"severity\":\"").append(severity).append("\"");
        }
        if (reason != null) {
            sb.append(",\"reason\":\"").append(reason).append("\"");
        }
        return sb.append("}").toString();
    }

    @Test
    @DisplayName("风控消费：action 缺失或空白时静默跳过")
    void riskEventSkipsWithoutAction() {
        riskEventConsumer.onRiskEvent(riskEventJson(null, "HIGH", "DEVICE", "dev_1", "r"));
        riskEventConsumer.onRiskEvent(riskEventJson("  ", "HIGH", "DEVICE", "dev_1", "r"));

        verifyNoInteractions(blockListService, webhookService, auditLog,
            riskActionRecorder, riskCaseRepo, reviewQueueService);
    }

    @Test
    @DisplayName("风控消费：未知 action 走 SECURITY_ALERT 审计并以小写动作名归档")
    void riskEventUnknownActionLoggedAsAlert() {
        riskEventConsumer.onRiskEvent(riskEventJson("QUARANTINE", "HIGH", "PLAYER", "u1", "manual"));

        verify(auditLog).log(
            eq(AuditLogEntity.AuditAction.SECURITY_ALERT), eq("risk_event"), eq("re_sweep"),
            any(), any(), eq(AuditLogEntity.AuditResult.SUCCESS), eq("risk-automation"),
            isNull(), isNull(), isNull(), isNull(), any());
        verify(riskActionRecorder).record(any(), eq("quarantine"), eq("logged"), isNull());
        verify(blockListService, never()).addBlock(
            any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(webhookService, never()).sendCustomWebhook(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("风控消费：BLOCK 时长矩阵 LOW=60 分钟、未知 severity=1440 分钟、reason 缺省")
    void riskEventBlockDurationMatrixAndDefaultReason() {
        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "LOW", "PLAYER", "u1", null));
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("player_id"), eq("u1"),
            eq("Risk automation: amount_threshold"),
            eq("fraud"), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(60), eq("risk-automation"), isNull(), isNull());

        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "EXTREME", "DEVICE", "dev_1", "abuse"));
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("device_id"), eq("dev_1"),
            eq("abuse"), eq("fraud"), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), eq("abuse"));
    }

    @Test
    @DisplayName("风控消费：身份扩散封禁只覆盖 player_id/user_id 关联身份")
    void riskEventIdentityExtendBlocksLinkedPlayers() {
        ReflectionTestUtils.setField(riskEventConsumer, "identityExtend", true);

        IdentityLinkEntity deviceLink = new IdentityLinkEntity();
        deviceLink.identityId = "idA";
        deviceLink.linkedIdentityType = "device_id";
        deviceLink.linkedId = "dev_1";
        IdentityLinkEntity playerLink = new IdentityLinkEntity();
        playerLink.identityId = "idA";
        playerLink.linkedIdentityType = "player_id";
        playerLink.linkedId = "player_9";
        IdentityLinkEntity characterLink = new IdentityLinkEntity();
        characterLink.identityId = "idA";
        characterLink.linkedIdentityType = "character_id";
        characterLink.linkedId = "char_1";

        lenient().when(identityLinkRepo.findByTypeAndId("device_id", "dev_1")).thenReturn(List.of(deviceLink));
        lenient().when(identityLinkRepo.findByIdentityId("idA"))
            .thenReturn(List.of(playerLink, characterLink));

        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "HIGH", "DEVICE", "dev_1", "cheat"));

        // 1 次主封禁 + 1 次扩散（character_id 不扩散）
        verify(blockListService, times(2)).addBlock(
            any(), any(), any(), any(), any(), any(),
            eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            anyBoolean(), any(), any(), any(), any());
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("player_id"), eq("player_9"),
            contains("identity-linked"), eq("fraud"),
            eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), eq("cheat"));
    }

    @Test
    @DisplayName("风控消费：REVIEW 非法 severity 回退 MEDIUM 并入普通优先级队列")
    void riskEventReviewInvalidSeverityFallsBackToMedium() {
        lenient().when(riskCaseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        riskEventConsumer.onRiskEvent(riskEventJson("REVIEW", "EXTREME", "PLAYER", "u1", "check"));

        ArgumentCaptor<io.oddsmaker.control.jpa.RiskCaseEntity> captor =
            ArgumentCaptor.forClass(io.oddsmaker.control.jpa.RiskCaseEntity.class);
        verify(riskCaseRepo).save(captor.capture());
        assertEquals(io.oddsmaker.control.jpa.RiskCaseEntity.RiskLevel.MEDIUM,
            captor.getValue().riskLevel);
        verify(reviewQueueService).addToQueue(any(), eq(2), eq("risk_automation"), eq("fraud"));
    }

    @Test
    @DisplayName("风控消费：risk_action webhook 失败不阻断本地封禁与归档")
    void riskEventWebhookFailureDoesNotBlockDisposition() {
        lenient().doThrow(new RuntimeException("network down"))
            .when(webhookService).sendCustomWebhook(anyString(), anyString(), any());

        assertDoesNotThrow(() ->
            riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "HIGH", "DEVICE", "dev_1", "cheat")));

        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("device_id"), eq("dev_1"),
            anyString(), anyString(), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), anyString());
        // B6 Decision 先行：BLOCK 判定先建案，归档携带案件 id
        ArgumentCaptor<String> caseId = ArgumentCaptor.forClass(String.class);
        verify(riskActionRecorder).record(any(), eq("block"), eq("blocked"), caseId.capture());
        assertTrue(caseId.getValue().startsWith("rc_"), caseId.getValue());
    }

}
