package io.oddsmaker.control.service;

import io.oddsmaker.control.api.RiskDashboardController;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.dto.RiskEventDto;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：RiskActionRecorder——只写 risk_actions 一行（risk_scores 回写已移除）、写失败不外抛；RiskDashboardController——getActiveRules 字段映射含空枚举回退、9 端点委托与 10 次 game:read 行内鉴权。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2RiskTest {

    @Mock
    private ClickHouseClient clickHouseClient;

    @Mock
    private RiskDashboardService riskDashboardService;

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @InjectMocks
    private RiskDashboardController riskDashboardController;

    private RiskActionRecorder riskActionRecorder;

    @BeforeEach
    void setUp() {
        riskActionRecorder = new RiskActionRecorder(clickHouseClient);
    }

    @Test
    @DisplayName("风控归档：只写 risk_actions 一行，risk_scores 回写已移除（累计分归 risk-job 评估产出），写失败不外抛")
    void riskActionRecorderWritesActionRowOnly() {
        lenient().when(clickHouseClient.isAvailable()).thenReturn(true);
        lenient().when(clickHouseClient.update(anyString(), any(Object[].class))).thenReturn(1);

        RiskEventDto event = new RiskEventDto();
        event.gameId = "game_demo";
        event.environment = "prod";
        event.ts = 1_730_000_000_000L;
        event.riskEventId = "re_001";
        event.ruleId = "rr_1";
        event.subjectType = "DEVICE";
        event.subjectId = "dev_abc";
        event.score = 0.95f;

        riskActionRecorder.record(event, "block", "blocked", "rc_1");

        verify(clickHouseClient, times(1)).update(anyString(), any(Object[].class));
        // B6 起主体累计分由 risk-job 评估随事件产出（§5.2），动作侧回写单规则分会覆盖累计语义
        verify(clickHouseClient, never()).execute(any(ConnectionCallback.class));

        doThrow(new RuntimeException("ch down"))
            .when(clickHouseClient).update(anyString(), any(Object[].class));
        assertDoesNotThrow(() -> riskActionRecorder.record(event, "alert", "logged", null));
    }

    @Test
    @DisplayName("风控大屏：getActiveRules 字段映射（含空枚举回退）与 9 个端点委托")
    void riskDashboardActiveRulesMapping() {
        RiskRuleEntity rich = new RiskRuleEntity();
        rich.id = "rr_1";
        rich.name = "Speed";
        rich.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        rich.ruleConditions = "{}";
        rich.triggerThreshold = 5;
        rich.timeWindowMinutes = 10;
        rich.riskScore = 80;
        rich.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        rich.actionType = RiskRuleEntity.ActionType.BLOCK;
        RiskRuleEntity bare = new RiskRuleEntity();
        bare.id = "rr_2";
        bare.name = "Bare";
        bare.triggerThreshold = 1;
        bare.timeWindowMinutes = 2;
        bare.riskScore = 3;
        lenient().when(riskRuleRepo.findActiveByGameId("g")).thenReturn(List.of(rich, bare));

        List<Map<String, Object>> body = riskDashboardController.getActiveRules("g").getBody();
        assertEquals(2, body.size());
        Map<String, Object> first = body.get(0);
        assertEquals("rr_1", first.get("id"));
        assertEquals("Speed", first.get("name"));
        assertEquals("THRESHOLD", first.get("ruleType"));
        assertEquals("{}", first.get("ruleConditions"));
        assertEquals(5, first.get("triggerThreshold"));
        assertEquals(10, first.get("timeWindowMinutes"));
        assertEquals(80, first.get("riskScore"));
        assertEquals("HIGH", first.get("riskLevel"));
        assertEquals("BLOCK", first.get("actionType"));
        assertEquals("THRESHOLD", body.get(1).get("ruleType"));   // 实体默认枚举
        assertEquals("MEDIUM", body.get(1).get("riskLevel"));
        assertEquals("ALERT", body.get(1).get("actionType"));

        LocalDateTime since = LocalDateTime.now().minusHours(6);
        riskDashboardController.getOverview("g", since);
        riskDashboardController.getTrends("g", since, 12);
        riskDashboardController.getHighRiskTargets("g", since, 5);
        riskDashboardController.getRulePerformance("g");
        riskDashboardController.getBlockStats("g");
        riskDashboardController.getJobStats("g");
        riskDashboardController.getDashboard("g", null);
        riskDashboardController.getRecentCases("g", 7);
        riskDashboardController.getReviewQueueStats("g");
        // 10 端点全 game:read（第二实例化点验证行内鉴权接线）
        org.mockito.Mockito.verify(accessGuard, org.mockito.Mockito.times(10))
            .requireGamePermission("g", "game:read");

        verify(riskDashboardService).getOverview("g", since);
        verify(riskDashboardService).getRiskTrends("g", since, 12);
        verify(riskDashboardService).getHighRiskTargets("g", since, 5);
        verify(riskDashboardService).getRulePerformance("g");
        verify(riskDashboardService).getBlockStats("g");
        verify(riskDashboardService).getJobStats("g");
        verify(riskDashboardService).getDashboard("g", null);
        verify(riskDashboardService).getRecentCases("g", 7);
        verify(riskDashboardService).getReviewQueueStats("g");
    }

}
