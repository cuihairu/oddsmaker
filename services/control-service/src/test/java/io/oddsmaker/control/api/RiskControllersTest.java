package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.BlockListService;
import io.oddsmaker.control.service.ReviewQueueService;
import io.oddsmaker.control.service.RiskDashboardService;
import io.oddsmaker.control.service.RiskMetricsService;
import io.oddsmaker.control.service.RiskRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控域 Controller 测试：规则 CRUD/大屏/审核队列/黑名单/风控指标。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风控域 Controller 测试")
class RiskControllersTest {

    @Mock
    private AccessGuard accessGuard;

    // ===== 风控规则 =====

    @Mock
    private RiskRuleService riskRuleService;

    @InjectMocks
    private RiskRuleController riskRuleController;

    @Test
    @DisplayName("风控规则：列表/详情/增改/启停/删除与不存在 404")
    void riskRuleEndpoints() {
        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "rr_1";
        rule.gameId = "g";
        lenient().when(riskRuleService.list(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(org.springframework.data.domain.Page.empty());
        lenient().when(riskRuleService.get("rr_1")).thenReturn(rule);
        lenient().when(riskRuleService.get("nope")).thenReturn(null);
        lenient().when(riskRuleService.create(any(), anyString())).thenReturn(rule);
        lenient().when(riskRuleService.update(eq("rr_1"), any(), anyString())).thenReturn(rule);
        lenient().when(riskRuleService.setStatus(eq("rr_1"), anyBoolean(), anyString())).thenReturn(rule);
        lenient().when(riskRuleService.delete(eq("rr_1"), anyString())).thenReturn(true);
        lenient().when(riskRuleService.delete(eq("rr_2"), anyString())).thenReturn(false);
        lenient().when(riskRuleService.get("rr_2")).thenReturn(rule);

        assertEquals(200, riskRuleController.list("g", null, null, null, null, 0, 20).getStatusCode().value());
        assertEquals(200, riskRuleController.list(null, null, null, null, null, 0, 20).getStatusCode().value());
        assertEquals(200, riskRuleController.get("rr_1").getStatusCode().value());
        assertEquals(404, riskRuleController.get("nope").getStatusCode().value());
        assertEquals(200, riskRuleController.create(rule).getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> riskRuleController.create(new RiskRuleEntity()));  // gameId 缺失由异常处理器转 400
        assertEquals(200, riskRuleController.update("rr_1", rule).getStatusCode().value());
        assertEquals(404, riskRuleController.update("nope", rule).getStatusCode().value());
        assertEquals(200, riskRuleController.enable("rr_1").getStatusCode().value());
        assertEquals(200, riskRuleController.disable("rr_1").getStatusCode().value());
        assertEquals(404, riskRuleController.enable("nope").getStatusCode().value());
        assertEquals(204, riskRuleController.delete("rr_1").getStatusCode().value());
        assertEquals(404, riskRuleController.delete("nope").getStatusCode().value());
        assertEquals(404, riskRuleController.delete("rr_2").getStatusCode().value());  // 删除未生效（并发已删）
    }

    // ===== 风控大屏 =====

    @Mock
    private RiskDashboardService riskDashboardService;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @InjectMocks
    private RiskDashboardController riskDashboardController;

    @Test
    @DisplayName("风控大屏：10 个端点委托")
    void riskDashboardEndpoints() {
        assertEquals(200, riskDashboardController.getActiveRules("g").getStatusCode().value());
        assertEquals(200, riskDashboardController.getOverview("g", null).getStatusCode().value());
        assertEquals(200, riskDashboardController.getTrends("g", java.time.LocalDateTime.now(), 24).getStatusCode().value());
        assertEquals(200, riskDashboardController.getHighRiskTargets("g", java.time.LocalDateTime.now(), 10).getStatusCode().value());
        assertEquals(200, riskDashboardController.getRulePerformance("g").getStatusCode().value());
        assertEquals(200, riskDashboardController.getBlockStats("g").getStatusCode().value());
        assertEquals(200, riskDashboardController.getJobStats("g").getStatusCode().value());
        assertEquals(200, riskDashboardController.getDashboard("g", null).getStatusCode().value());
        assertEquals(200, riskDashboardController.getRecentCases("g", 20).getStatusCode().value());
        assertEquals(200, riskDashboardController.getReviewQueueStats("g").getStatusCode().value());
        verify(riskRuleRepo).findActiveByGameId("g");
        // 10 端点全 game:read（含原本无注解的 getActiveRules 补齐）
        verify(accessGuard, org.mockito.Mockito.times(10)).requireGamePermission("g", "game:read");
    }

    // ===== 审核队列 =====

    @Mock
    private ReviewQueueService reviewQueueService;

    @InjectMocks
    private ReviewQueueController reviewQueueController;

    @Test
    @DisplayName("审核队列：11 个端点委托")
    void reviewQueueEndpoints() {
        assertEquals(200, reviewQueueController.getGameQueue("g").getStatusCode().value());
        assertEquals(200, reviewQueueController.getPendingItems("g").getStatusCode().value());
        assertEquals(200, reviewQueueController.getHighPriorityItems("g", 70).getStatusCode().value());
        assertEquals(200, reviewQueueController.assignReviewer("q1", "g", new ReviewQueueController.AssignRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.claimItem("q1", "g", new ReviewQueueController.ClaimRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.startReview("q1", "g", new ReviewQueueController.StartReviewRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.completeReview("q1", "g", new ReviewQueueController.CompleteReviewRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.escalateItem("q1", "g", new ReviewQueueController.EscalateRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.cancelItem("q1", "g", new ReviewQueueController.CancelRequest()).getStatusCode().value());
        assertEquals(200, reviewQueueController.getReviewerItems("r1", "g").getStatusCode().value());
        assertEquals(200, reviewQueueController.getQueueStats("g").getStatusCode().value());
        verify(reviewQueueService).getGameQueue("g");
        // 查询 4 → game:read；管理 3（assign/escalate/cancel）→ risk:manage；评审 4 → risk:review
        verify(accessGuard, org.mockito.Mockito.times(4)).requireGamePermission("g", "game:read");
        verify(accessGuard, org.mockito.Mockito.times(3)).requireGamePermission("g", "risk:manage");
        verify(accessGuard, org.mockito.Mockito.times(4)).requireGamePermission("g", "risk:review");
    }

    // ===== 黑名单 =====

    @Mock
    private BlockListService blockListService;

    @InjectMocks
    private BlockListController blockListController;

    @InjectMocks
    private InternalBlockListController internalBlockListController;

    @Test
    @DisplayName("黑名单：10 个运营端点 + 内部批量检查")
    void blockListEndpoints() {
        when(blockListService.isBlocked("g", "device", "d1")).thenReturn(true);
        // getBlock 反查实体后按其 gameId 鉴权（原注解引用签名中不存在的 #gameId，SpEL 求值即抛）
        BlockListEntity stored = new BlockListEntity();
        stored.gameId = "g";
        when(blockListService.getBlock("b1")).thenReturn(stored);

        assertEquals(200, blockListController.checkBlock("g", "device", "d1").getStatusCode().value());
        assertEquals(Boolean.TRUE, blockListController.checkBlock("g", "device", "d1").getBody().get("blocked"));
        assertEquals(200, blockListController.getActiveBlocks("g").getStatusCode().value());
        assertEquals(200, blockListController.getBlock("b1").getStatusCode().value());
        assertEquals(200, blockListController.addBlock(new BlockListController.BlockRequest()).getStatusCode().value());
        assertEquals(200, blockListController.unblock("b1", "g", new BlockListController.UnblockRequest()).getStatusCode().value());
        assertEquals(200, blockListController.batchUnblock("g", new BlockListController.BatchUnblockRequest()).getStatusCode().value());
        assertEquals(200, blockListController.createFromRiskCase("rc1", "g", "ops").getStatusCode().value());
        assertEquals(200, blockListController.getBlockStats("g").getStatusCode().value());
        assertEquals(200, blockListController.searchBlocks("g", "q").getStatusCode().value());
        assertEquals(200, blockListController.getBlocksByType("g", "device").getStatusCode().value());

        InternalBlockListController.BatchCheckRequest req = new InternalBlockListController.BatchCheckRequest();
        req.gameId = "g";
        InternalBlockListController.BatchCheckTarget target = new InternalBlockListController.BatchCheckTarget();
        target.targetType = "device";
        target.targetValue = "d1";
        req.targets = List.of(target);
        assertEquals(200, internalBlockListController.batchCheck(req).getStatusCode().value());
        // 缺参 400
        assertEquals(400, internalBlockListController.batchCheck(new InternalBlockListController.BatchCheckRequest()).getStatusCode().value());
        // game:read 7（checkBlock×2/active/getBlock/stats/search/byType）+ risk:manage 4（add 为 null gameId + unblock/batch/fromRiskCase）
        verify(accessGuard, org.mockito.Mockito.times(7)).requireGamePermission("g", "game:read");
        verify(accessGuard, org.mockito.Mockito.times(3)).requireGamePermission("g", "risk:manage");
        verify(accessGuard, org.mockito.Mockito.times(1)).requireGamePermission(null, "risk:manage");
    }

    // ===== 风控指标 =====

    @Mock
    private RiskMetricsService riskMetricsService;

    @InjectMocks
    private RiskMetricsController riskMetricsController;

    @Test
    @DisplayName("风控指标：4 个端点委托与鉴权")
    void riskMetricsEndpoints() {
        Map<String, Object> resp = Map.of("available", true);
        when(riskMetricsService.trend("g", null, 24)).thenReturn(resp);
        when(riskMetricsService.ruleHits("g", "prod", 48)).thenReturn(resp);
        when(riskMetricsService.severity("g", null, null)).thenReturn(resp);
        when(riskMetricsService.actions("g", null, null)).thenReturn(resp);

        assertEquals(resp, riskMetricsController.trend("g", null, 24).getBody());
        assertEquals(resp, riskMetricsController.ruleHits("g", "prod", 48).getBody());
        assertEquals(resp, riskMetricsController.severity("g", null, null).getBody());
        assertEquals(resp, riskMetricsController.actions("g", null, null).getBody());
        verify(accessGuard, org.mockito.Mockito.times(4)).requireGamePermission("g", "risk_rule:read");
    }

    @Test
    @DisplayName("分支对侧：封禁显式 SOFT/永久、内部检查 targets 缺失或空、大屏规则 null 枚举字段")
    void blockListAndDashboardCounterSides() {
        BlockListController.BlockRequest req = new BlockListController.BlockRequest();
        req.gameId = "g";
        req.blockType = "SOFT";
        req.isPermanent = true;
        assertEquals(200, blockListController.addBlock(req).getStatusCode().value());
        verify(blockListService).addBlock(eq("g"), isNull(), isNull(), isNull(), isNull(), isNull(),
            eq(BlockListEntity.BlockType.SOFT), eq(true), isNull(), isNull(), isNull(), isNull());

        InternalBlockListController.BatchCheckRequest noTargets = new InternalBlockListController.BatchCheckRequest();
        noTargets.gameId = "g";
        assertEquals(400, internalBlockListController.batchCheck(noTargets).getStatusCode().value());
        InternalBlockListController.BatchCheckRequest emptyTargets = new InternalBlockListController.BatchCheckRequest();
        emptyTargets.gameId = "g";
        emptyTargets.targets = List.of();
        assertEquals(400, internalBlockListController.batchCheck(emptyTargets).getStatusCode().value());

        io.oddsmaker.control.jpa.RiskRuleEntity rule = new io.oddsmaker.control.jpa.RiskRuleEntity();
        rule.id = "rr_1";
        rule.name = "r";
        rule.ruleType = null;   // 实体有默认初始化器，须显式置 null 才走三元 null 侧
        rule.riskLevel = null;
        rule.actionType = null;
        when(riskRuleRepo.findActiveByGameId("g")).thenReturn(List.of(rule));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules =
            (List<Map<String, Object>>) riskDashboardController.getActiveRules("g").getBody();
        assertEquals("rr_1", rules.get(0).get("id"));
        org.junit.jupiter.api.Assertions.assertNull(rules.get(0).get("ruleType"));
        org.junit.jupiter.api.Assertions.assertNull(rules.get(0).get("riskLevel"));
        org.junit.jupiter.api.Assertions.assertNull(rules.get(0).get("actionType"));
    }

}
