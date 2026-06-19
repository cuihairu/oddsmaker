package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RiskManagementService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RiskManagementService 单元测试")
class RiskManagementServiceTest {

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RiskManagementService riskManagementService;

    private RiskRuleEntity testRule;
    private RiskCaseEntity testCase;

    @BeforeEach
    void setUp() {
        testRule = new RiskRuleEntity();
        testRule.id = "rule_test123";
        testRule.gameId = "game_test123";
        testRule.environmentId = "env_test123";
        testRule.ruleName = "Test Rule";
        testRule.ruleStatus = RiskRuleEntity.RuleStatus.ACTIVE;
        testRule.testMode = false;
        testRule.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        testRule.riskScore = 80;
        testRule.actionType = RiskRuleEntity.ActionType.BLOCK;
        testRule.enableAutoBlock = true;
        testRule.triggerCount = 0L;
        testRule.blockCount = 0L;
        testRule.createdAt = LocalDateTime.now();

        testCase = new RiskCaseEntity();
        testCase.id = "rc_test123";
        testCase.riskRuleId = "rule_test123";
        testCase.gameId = "game_test123";
        testCase.environmentId = "env_test123";
        testCase.caseNumber = "CASE-2024-001";
        testCase.targetType = "user";
        testCase.targetId = "user_123";
        testCase.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        testCase.riskScore = 80;
        testCase.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        testCase.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        testCase.createdAt = LocalDateTime.now();
    }

    @Test
    @DisplayName("评估事件 - 匹配规则")
    void evaluateEvent_MatchingRule() {
        // Given
        List<RiskRuleEntity> rules = Arrays.asList(testRule);
        when(riskRuleRepo.findByGameIdAndEnvironment("game_test123", "env_test123")).thenReturn(rules);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event_type", "payment");
        eventData.put("amount", 1000);

        // When
        List<RiskManagementService.RiskAction> actions = riskManagementService.evaluateEvent(
            "game_test123", "env_test123", "payment", eventData, "user_123", "user");

        // Then
        assertThat(actions).isNotEmpty();
        assertThat(actions.get(0).getActionType()).isEqualTo(RiskRuleEntity.ActionType.BLOCK);
    }

    @Test
    @DisplayName("评估事件 - 不匹配规则")
    void evaluateEvent_NoMatchingRule() {
        // Given
        when(riskRuleRepo.findByGameIdAndEnvironment("game_test123", "env_test123")).thenReturn(Arrays.asList());

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event_type", "login");

        // When
        List<RiskManagementService.RiskAction> actions = riskManagementService.evaluateEvent(
            "game_test123", "env_test123", "login", eventData, "user_123", "user");

        // Then
        assertThat(actions).isEmpty();
    }

    @Test
    @DisplayName("评估事件 - 测试模式规则被过滤")
    void evaluateEvent_TestModeRuleFiltered() {
        // Given
        testRule.testMode = true;
        List<RiskRuleEntity> rules = Arrays.asList(testRule);
        when(riskRuleRepo.findByGameIdAndEnvironment("game_test123", "env_test123")).thenReturn(rules);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event_type", "payment");

        // When
        List<RiskManagementService.RiskAction> actions = riskManagementService.evaluateEvent(
            "game_test123", "env_test123", "payment", eventData, "user_123", "user");

        // Then
        assertThat(actions).isEmpty();
    }

    @Test
    @DisplayName("评估事件 - 非活跃规则被过滤")
    void evaluateEvent_InactiveRuleFiltered() {
        // Given
        testRule.ruleStatus = RiskRuleEntity.RuleStatus.DISABLED;
        List<RiskRuleEntity> rules = Arrays.asList(testRule);
        when(riskRuleRepo.findByGameIdAndEnvironment("game_test123", "env_test123")).thenReturn(rules);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event_type", "payment");

        // When
        List<RiskManagementService.RiskAction> actions = riskManagementService.evaluateEvent(
            "game_test123", "env_test123", "payment", eventData, "user_123", "user");

        // Then
        assertThat(actions).isEmpty();
    }

    @Test
    @DisplayName("创建风控案例 - 成功")
    void createRiskCase_Success() {
        // Given
        when(riskRuleRepo.findById("rule_test123")).thenReturn(Optional.of(testRule));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        when(riskRuleRepo.save(any(RiskRuleEntity.class))).thenReturn(testRule);

        Map<String, Object> context = new HashMap<>();
        context.put("event_type", "payment");
        context.put("timestamp", LocalDateTime.now().toString());
        context.put("client_ip", "192.168.1.1");
        context.put("user_agent", "Mozilla/5.0");

        // When
        RiskCaseEntity result = riskManagementService.createRiskCase(
            "rule_test123", "user_123", "user", context);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.targetId).isEqualTo("user_123");
        assertThat(result.targetType).isEqualTo("user");
        verify(riskCaseRepo, times(1)).save(any(RiskCaseEntity.class));
        verify(riskRuleRepo, times(1)).save(any(RiskRuleEntity.class));
    }

    @Test
    @DisplayName("创建风控案例 - 规则不存在")
    void createRiskCase_RuleNotFound() {
        // Given
        when(riskRuleRepo.findById("rule_notexist")).thenReturn(Optional.empty());

        Map<String, Object> context = new HashMap<>();

        // When & Then
        assertThatThrownBy(() -> riskManagementService.createRiskCase(
            "rule_notexist", "user_123", "user", context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Risk rule not found");
    }

    @Test
    @DisplayName("执行动作 - 封禁成功")
    void executeAction_BlockSuccess() {
        // Given
        testCase.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));
        when(riskRuleRepo.findById("rule_test123")).thenReturn(Optional.of(testRule));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        when(riskRuleRepo.save(any(RiskRuleEntity.class))).thenReturn(testRule);

        // When
        riskManagementService.executeAction("rc_test123");

        // Then
        verify(riskCaseRepo, times(1)).save(any(RiskCaseEntity.class));
        verify(riskRuleRepo, times(1)).save(any(RiskRuleEntity.class));
    }

    @Test
    @DisplayName("执行动作 - 案例已执行")
    void executeAction_AlreadyExecuted() {
        // Given
        testCase.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));

        // When
        riskManagementService.executeAction("rc_test123");

        // Then
        verify(riskCaseRepo, never()).save(any(RiskCaseEntity.class));
    }

    @Test
    @DisplayName("执行动作 - 案例不存在")
    void executeAction_CaseNotFound() {
        // Given
        when(riskCaseRepo.findById("rc_notexist")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> riskManagementService.executeAction("rc_notexist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Risk case not found");
    }

    @Test
    @DisplayName("解除封禁 - 成功")
    void unblockTarget_Success() {
        // Given
        testCase.blocked = true;
        testCase.blockedBy = "system";
        testCase.blockedAt = LocalDateTime.now();
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        doNothing().when(auditLogService).logUnblock(anyString(), anyString(), anyString(), anyString(), any());

        // When
        riskManagementService.unblockTarget("rc_test123", "admin_user", "False positive");

        // Then
        verify(riskCaseRepo, times(1)).save(any(RiskCaseEntity.class));
        verify(auditLogService, times(1)).logUnblock(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("解除封禁 - 未封禁案例")
    void unblockTarget_NotBlocked() {
        // Given
        testCase.blocked = false;
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));

        // When & Then
        assertThatThrownBy(() -> riskManagementService.unblockTarget("rc_test123", "admin_user", "False positive"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Risk case is not blocked");
    }

    @Test
    @DisplayName("完成审核 - 成功")
    void completeReview_Success() {
        // Given
        testCase.reviewStatus = RiskCaseEntity.ReviewStatus.PENDING;
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);

        // When
        riskManagementService.completeReview("rc_test123", "reviewer_1", "Looks suspicious", "confirmed_malicious");

        // Then
        verify(riskCaseRepo, times(1)).save(any(RiskCaseEntity.class));
    }

    @Test
    @DisplayName("完成审核 - 误报自动解除封禁")
    void completeReview_BenignAutoUnblock() {
        // Given
        testCase.reviewStatus = RiskCaseEntity.ReviewStatus.PENDING;
        testCase.blocked = true;
        testCase.blockedBy = "system";
        testCase.blockedAt = LocalDateTime.now();
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        doNothing().when(auditLogService).logUnblock(anyString(), anyString(), anyString(), anyString(), any());

        // When
        riskManagementService.completeReview("rc_test123", "reviewer_1", "False positive", "confirmed_benign");

        // Then
        verify(riskCaseRepo, times(2)).save(any(RiskCaseEntity.class)); // 一次审核，一次解除封禁
        verify(auditLogService, times(1)).logUnblock(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("规则触发计数增加")
    void ruleTriggerCount_Incremented() {
        // Given
        when(riskRuleRepo.findById("rule_test123")).thenReturn(Optional.of(testRule));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        when(riskRuleRepo.save(any(RiskRuleEntity.class))).thenReturn(testRule);

        Map<String, Object> context = new HashMap<>();

        // When
        riskManagementService.createRiskCase("rule_test123", "user_123", "user", context);

        // Then
        assertThat(testRule.triggerCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("封禁计数增加")
    void blockCount_Incremented() {
        // Given
        testCase.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        when(riskCaseRepo.findById("rc_test123")).thenReturn(Optional.of(testCase));
        when(riskRuleRepo.findById("rule_test123")).thenReturn(Optional.of(testRule));
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenReturn(testCase);
        when(riskRuleRepo.save(any(RiskRuleEntity.class))).thenReturn(testRule);

        // When
        riskManagementService.executeAction("rc_test123");

        // Then
        assertThat(testRule.blockCount).isEqualTo(1L);
    }
}
