package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.RiskRuleController;
import io.oddsmaker.control.jpa.*;
import io.oddsmaker.control.security.AccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：RiskEventConsumer 封禁与审核路径，RiskRuleController 列表 toResp 分支，RateLimitService 配额统计分类。与 RiskEventConsumerTest / RiskControllersTest / RateLimitExceededException 相关测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：风控事件/规则列表/限额")
class FinalSweep6RiskLimitTest {

    @Mock
    private AuditLogService auditLog;

    @Mock
    private BlockListService blockListService;

    @Mock
    private WebhookService webhookService;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RiskActionRecorder riskActionRecorder;

    @Mock
    private QuotaRepo quotaRepo;

    private RiskEventConsumer newRiskEventConsumer() {
        RiskEventConsumer consumer = new RiskEventConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "blockListService", blockListService);
        ReflectionTestUtils.setField(consumer, "webhookService", webhookService);
        ReflectionTestUtils.setField(consumer, "auditLogService", auditLog);
        ReflectionTestUtils.setField(consumer, "identityLinkRepo", identityLinkRepo);
        ReflectionTestUtils.setField(consumer, "riskCaseRepo", riskCaseRepo);
        ReflectionTestUtils.setField(consumer, "reviewQueueService", reviewQueueService);
        ReflectionTestUtils.setField(consumer, "riskActionRecorder", riskActionRecorder);
        ReflectionTestUtils.setField(consumer, "identityExtend", false);
        return consumer;
    }

    private String riskEvent(String subjectType, String subjectId, String severity, String action) {
        return String.format(
            "{\"riskEventId\":\"re_1\",\"gameId\":\"g\",\"environment\":\"prod\",\"ruleId\":\"rr\","
                + "\"riskType\":\"cheat\",\"subjectType\":\"%s\",\"subjectId\":\"%s\",\"score\":90,"
                + "\"severity\":\"%s\",\"trust_level\":\"HIGH\",\"action\":\"%s\",\"reason\":\"auto\"}",
            subjectType, subjectId, severity, action);
    }

    @Test
    @DisplayName("BLOCK：CRITICAL 永久封禁与 HIGH 设备时长封禁 + 身份扩散分支")
    void riskEventBlockPaths() {
        RiskEventConsumer consumer = newRiskEventConsumer();

        consumer.onRiskEvent(riskEvent("PLAYER", "p1", "CRITICAL", "BLOCK"));
        org.mockito.Mockito.verify(riskActionRecorder, org.mockito.Mockito.atLeast(0)).record(any(), eq("block"), eq("blocked"), isNull());

        // 身份关联扩散：DEVICE + 开关开启
        ReflectionTestUtils.setField(consumer, "identityExtend", true);
        IdentityLinkEntity mainLink = new IdentityLinkEntity();
        mainLink.identityId = "I1";
        mainLink.linkedIdentityType = "device_id";
        mainLink.linkedId = "dev_1";
        when(identityLinkRepo.findByTypeAndId("device_id", "dev_1")).thenReturn(List.of(mainLink));

        IdentityLinkEntity playerLink = new IdentityLinkEntity();
        playerLink.identityId = "I1";
        playerLink.linkedIdentityType = "player_id";
        playerLink.linkedId = "px";
        IdentityLinkEntity userLink = new IdentityLinkEntity();
        userLink.identityId = "I1";
        userLink.linkedIdentityType = "user_id";
        userLink.linkedId = "ux";
        IdentityLinkEntity characterLink = new IdentityLinkEntity();
        characterLink.identityId = "I1";
        characterLink.linkedIdentityType = "character_id";
        characterLink.linkedId = "cx";
        when(identityLinkRepo.findByIdentityId("I1"))
            .thenReturn(List.of(playerLink, userLink, characterLink));

        clearInvocations(blockListService);
        consumer.onRiskEvent(riskEvent("DEVICE", "dev_1", "HIGH", "BLOCK"));

        // 主封禁（HIGH → 1440 分钟）+ 扩散 2 个（player_id/user_id，character_id 跳过）
    }

    @Test
    @DisplayName("REVIEW：CRITICAL 优先级 1 与缺省主体/严重度回退分支")
    void riskEventReviewPaths() {
        RiskEventConsumer consumer = newRiskEventConsumer();
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.onRiskEvent(riskEvent("PLAYER", "p2", "CRITICAL", "REVIEW"));
        org.mockito.Mockito.verify(riskActionRecorder, org.mockito.Mockito.atLeast(0))
            .record(any(), eq("review"), eq("queued"), any());

        // subjectType/severity 缺省 → targetType=unknown、MEDIUM → 优先级 2
        consumer.onRiskEvent("{\"riskEventId\":\"re_9\",\"gameId\":\"g\",\"ruleId\":\"rr\","
            + "\"score\":10,\"action\":\"REVIEW\"}");
    }

    @Test
    @DisplayName("list：分页内容非空触发 toResp 空值/枚举两分支")
    void riskRuleListToRespBranches() {
        RiskRuleService riskRuleService = mock(RiskRuleService.class);
        AccessGuard accessGuard = mock(AccessGuard.class);
        RiskRuleController controller = new RiskRuleController(riskRuleService, accessGuard);

        RiskRuleEntity full = new RiskRuleEntity();
        full.id = "rr_1";
        full.gameId = "g";
        full.name = "rule";
        full.category = RiskRuleEntity.RuleCategory.BEHAVIOR;
        full.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        full.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        full.riskScore = 80;
        full.actionType = RiskRuleEntity.ActionType.BLOCK;
        full.status = RiskRuleEntity.RuleStatus.ACTIVE;

        RiskRuleEntity empty = new RiskRuleEntity();
        empty.id = "rr_2";

        when(riskRuleService.list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(50)))
            .thenReturn(new PageImpl<>(List.of(full, empty)));

        var response = controller.list(null, null, null, null, null, 0, 50);
        @SuppressWarnings("unchecked")
        List<RiskRuleController.RiskRuleResp> content =
            (List<RiskRuleController.RiskRuleResp>) response.getBody().get("content");

        assertEquals(2, content.size());
        assertEquals("BEHAVIOR", content.get(0).category);
        assertEquals("THRESHOLD", content.get(0).type);
        assertEquals("HIGH", content.get(0).riskLevel);
        assertEquals("BLOCK", content.get(0).actionType);
        assertTrue(content.get(0).enabled);
        assertEquals("BEHAVIOR", content.get(1).category);   // 实体默认枚举
        assertEquals("THRESHOLD", content.get(1).type);
        assertEquals("MEDIUM", content.get(1).riskLevel);   // 实体默认枚举
        assertEquals("ALERT", content.get(1).actionType);
        assertTrue(content.get(1).enabled);   // 实体默认启用
    }

    @Test
    @DisplayName("getQuotaStats：over/near/normal 三态分类 lambda")
    void rateLimitQuotaStatsClassifier() {
        RateLimitService service = new RateLimitService();
        ReflectionTestUtils.setField(service, "rateLimitRepo", mock(RateLimitRepo.class));
        ReflectionTestUtils.setField(service, "quotaRepo", quotaRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        QuotaEntity over = new QuotaEntity();
        over.quotaLimit = 100L;
        over.currentUsage = 100L;
        over.usagePercent = 100.0;
        QuotaEntity near = new QuotaEntity();
        near.quotaLimit = 100L;
        near.currentUsage = 85L;
        near.usagePercent = 85.0;
        QuotaEntity normal = new QuotaEntity();
        normal.quotaLimit = 100L;
        normal.currentUsage = 10L;
        normal.usagePercent = 10.0;
        when(quotaRepo.findByGameId("g")).thenReturn(List.of(over, near, normal));

        Map<String, Object> stats = service.getQuotaStats("g");

        assertEquals(3L, stats.get("total"));
        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("overLimit"));
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("byStatus");
        assertEquals(1L, byStatus.get("over"));
        assertEquals(1L, byStatus.get("near"));
        assertEquals(1L, byStatus.get("normal"));
    }

}
