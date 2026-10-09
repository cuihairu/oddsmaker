package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.BlockListService;
import io.oddsmaker.control.service.RiskCaseService;
import io.oddsmaker.control.service.RiskScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控案例回看测试：Controller 鉴权与参数校验 + Service 过滤/详情/解除封禁联动。
 * ObjectMapper 用真实实例构造（避免泛型 readValue 桩定义），其余依赖 @Mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风控案例回看测试")
class RiskCaseApiTest {

    private static final String GAME = "game_demo";
    private static final String CASE_ID = "rc_1";

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RiskCaseService riskCaseService;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private BlockListService blockListService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private RiskScoreService riskScoreService;

    private RiskCaseController controller;
    private RiskCaseService service;

    @BeforeEach
    void setUp() {
        controller = new RiskCaseController(riskCaseService, accessGuard);
        service = new RiskCaseService(riskCaseRepo, blockListRepo, blockListService, auditLogService,
            riskScoreService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    // ===== Controller =====

    @Test
    @DisplayName("列表：game:read 鉴权并透传过滤参数（含策略实验室下钻的 ruleId/disposition）")
    void listPassthrough() {
        when(riskCaseService.list(eq(GAME), eq("BLOCK"), eq("HIGH"), eq("rr_1"), eq("confirmed_benign"), eq(50)))
            .thenReturn(List.of(Map.of("id", CASE_ID)));

        var resp = controller.list(GAME, "BLOCK", "HIGH", "rr_1", "confirmed_benign", 50);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(CASE_ID, resp.getBody().get(0).get("id"));
        verify(accessGuard).requireGamePermission(GAME, "game:read");
    }

    @Test
    @DisplayName("详情：存在 200，不存在 404")
    void detailNotFound() {
        Map<String, Object> detail = Map.of("id", CASE_ID);
        when(riskCaseService.detail(GAME, CASE_ID)).thenReturn(detail);
        when(riskCaseService.detail(GAME, "missing")).thenReturn(null);

        assertEquals(200, controller.detail(GAME, CASE_ID).getStatusCode().value());
        assertEquals(404, controller.detail(GAME, "missing").getStatusCode().value());
    }

    @Test
    @DisplayName("解除封禁：缺 reason 拒绝，risk:manage 鉴权")
    void unblockRequiresReason() {
        assertThrows(IllegalArgumentException.class, () -> controller.unblock(GAME, CASE_ID, null));
        assertThrows(IllegalArgumentException.class,
            () -> controller.unblock(GAME, CASE_ID, new RiskCaseController.UnblockReq()));

        RiskCaseController.UnblockReq req = new RiskCaseController.UnblockReq();
        req.reason = "误杀，已核实为正常玩家";
        var resp = controller.unblock(GAME, CASE_ID, req);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("unblocked"));
        // 两次拒绝 + 一次成功，鉴权均先行（拒绝发生在 reason 校验，同样过 guard）
        verify(accessGuard, org.mockito.Mockito.times(3)).requireGamePermission(GAME, "risk:manage");
        verify(riskCaseService).unblock(eq(GAME), eq(CASE_ID), anyString(), eq(req.reason));
    }

    // ===== Service: list =====

    @Test
    @DisplayName("列表过滤组合路由到对应派生查询，limit 上限 500")
    void listFilterRouting() {
        Pageable defaultPage = PageRequest.of(0, 100);
        Pageable clamped = PageRequest.of(0, 500);

        service.list(GAME, null, null, null, null, 100);
        verify(riskCaseRepo).findByGameIdOrderByCreatedAtDesc(GAME, defaultPage);

        service.list(GAME, "OPEN", null, null, null, 10);
        verify(riskCaseRepo).findByGameIdAndStatusOrderByCreatedAtDesc(
            eq(GAME), eq(RiskCaseEntity.DecisionStatus.OPEN), eq(PageRequest.of(0, 10)));

        service.list(GAME, null, "CRITICAL", null, null, 10);
        verify(riskCaseRepo).findByGameIdAndRiskLevelOrderByCreatedAtDesc(
            eq(GAME), eq(RiskCaseEntity.RiskLevel.CRITICAL), eq(PageRequest.of(0, 10)));

        service.list(GAME, "BLOCK", "HIGH", null, null, 99999);
        verify(riskCaseRepo).findByGameIdAndStatusAndRiskLevelOrderByCreatedAtDesc(
            eq(GAME), eq(RiskCaseEntity.DecisionStatus.BLOCK), eq(RiskCaseEntity.RiskLevel.HIGH), eq(clamped));

        assertThrows(IllegalArgumentException.class, () -> service.list(GAME, "NOPE", null, null, null, 10));
        assertThrows(IllegalArgumentException.class, () -> service.list(GAME, null, "NOPE", null, null, 10));
    }

    @Test
    @DisplayName("ruleId/disposition 后过滤：先取最近 2000 条，内存过滤后截断到 limit")
    void listPostFilterForLab() {
        RiskCaseEntity benign = reviewedCase("rc_b", "rr_1", "confirmed_benign");
        RiskCaseEntity fraud = reviewedCase("rc_f", "rr_1", "confirmed_fraud");
        RiskCaseEntity otherRule = reviewedCase("rc_o", "rr_2", "confirmed_benign");
        RiskCaseEntity open = reviewedCase("rc_n", "rr_1", null);
        when(riskCaseRepo.findByGameIdOrderByCreatedAtDesc(eq(GAME), eq(PageRequest.of(0, 2000))))
            .thenReturn(List.of(benign, fraud, otherRule, open));

        List<Map<String, Object>> rows = service.list(GAME, null, null, "rr_1", "confirmed_benign", 100);

        assertEquals(List.of("rc_b"), rows.stream().map(r -> r.get("id")).toList());

        // 只带 ruleId（不过滤处置）：rr_1 的三条按序返回
        List<Map<String, Object>> byRule = service.list(GAME, null, null, "rr_1", null, 100);
        assertEquals(List.of("rc_b", "rc_f", "rc_n"), byRule.stream().map(r -> r.get("id")).toList());

        // limit 截断发生在过滤之后
        List<Map<String, Object>> capped = service.list(GAME, null, null, "rr_1", null, 2);
        assertEquals(List.of("rc_b", "rc_f"), capped.stream().map(r -> r.get("id")).toList());
    }

    private RiskCaseEntity reviewedCase(String id, String ruleId, String disposition) {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = id;
        rc.gameId = GAME;
        rc.caseNumber = "CASE_" + id.toUpperCase();
        rc.riskRuleId = ruleId;
        rc.targetType = "player_id";
        rc.targetId = "p_" + id;
        rc.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        rc.status = RiskCaseEntity.DecisionStatus.RESOLVED;
        rc.actionTaken = RiskCaseEntity.ActionType.ALERT;
        rc.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        rc.disposition = disposition;
        return rc;
    }

    // ===== Service: detail =====

    @Test
    @DisplayName("详情：跨游戏/不存在返回 null；证据与上下文 JSON 解析为对象")
    void detailParsesEvidence() {
        RiskCaseEntity rc = blockedCase();
        rc.evidenceData = "{\"ip\":\"1.2.3.4\",\"speed\":120}";
        rc.contextData = "not-json{{{";
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(rc));

        Map<String, Object> detail = service.detail(GAME, CASE_ID);
        assertEquals(CASE_ID, detail.get("id"));
        assertEquals(Map.of("ip", "1.2.3.4", "speed", 120), detail.get("evidence"));
        assertEquals("not-json{{{", detail.get("context"));

        assertNull(service.detail("other_game", CASE_ID));
        when(riskCaseRepo.findById("missing")).thenReturn(Optional.empty());
        assertNull(service.detail(GAME, "missing"));
        assertNull(service.detail(GAME, null));
    }

    @Test
    @DisplayName("详情：主体累计分快照注入——found=true 回传，未落分/CH 不可用/查询异常降级 null 不阻断回看")
    void detailSubjectRiskScore() {
        RiskCaseEntity rc = blockedCase();
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(rc));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("found", true);
        snapshot.put("score", 85);
        snapshot.put("reasons", List.of(Map.of("ruleId", "rr_1", "contribution", 40)));
        snapshot.put("updatedAt", "2026-10-10 12:00:00");
        when(riskScoreService.latest(GAME, "player_id", "p_100")).thenReturn(snapshot);
        assertEquals(snapshot, service.detail(GAME, CASE_ID).get("subjectRiskScore"));

        when(riskScoreService.latest(GAME, "player_id", "p_100")).thenReturn(Map.of("found", false));
        assertNull(service.detail(GAME, CASE_ID).get("subjectRiskScore"));

        when(riskScoreService.latest(GAME, "player_id", "p_100"))
            .thenThrow(new IllegalStateException("ch down"));
        assertNull(service.detail(GAME, CASE_ID).get("subjectRiskScore"));
        assertEquals(CASE_ID, service.detail(GAME, CASE_ID).get("id"));
    }

    // ===== Service: unblock =====

    @Test
    @DisplayName("解除封禁：仅 BLOCK 已执行且未解除可解，联动释放活跃封禁名单并审计")
    void unblockHappyPathAndGuards() {
        RiskCaseEntity rc = blockedCase();
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(rc));

        // 未解除的活跃封禁两条 + 已解除一条（ isActive=false 语义由 isActive() 表达 ）
        BlockListEntity active1 = block("bl_1", true);
        BlockListEntity active2 = block("bl_2", true);
        BlockListEntity released = block("bl_3", false);
        when(blockListRepo.findByRiskCaseId(CASE_ID)).thenReturn(List.of(active1, active2, released));

        RiskCaseEntity result = service.unblock(GAME, CASE_ID, "op_1", "误杀，已核实");

        assertEquals("op_1", result.unblockedBy);
        assertEquals("误杀，已核实", result.unblockReason);
        verify(blockListService).unblock("bl_1", "op_1", "误杀，已核实");
        verify(blockListService).unblock("bl_2", "op_1", "误杀，已核实");
        verify(blockListService, never()).unblock(eq("bl_3"), anyString(), anyString());
        verify(riskCaseRepo).save(rc);

        // 服务调用的是 AuditLogService 12 参重载；按参数逐位校验（审计落主体维度，对齐 BlockListService 调用形状）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UNBLOCK), eq("player_id"), eq("p_100"),
            eq("CASE_20261009_0001"), eq("误杀，已核实"), eq(AuditLogEntity.AuditResult.SUCCESS),
            eq("op_1"), isNull(), isNull(), isNull(), isNull(), metaCaptor.capture());
        assertEquals(GAME, metaCaptor.getValue().get("gameId"));
        assertEquals(CASE_ID, metaCaptor.getValue().get("riskCaseId"));
        assertEquals(2, metaCaptor.getValue().get("cascadedBlocks"));
    }

    @Test
    @DisplayName("解除封禁守卫：不存在/跨游戏/未封禁/已解除均拒绝")
    void unblockGuards() {
        when(riskCaseRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.unblock(GAME, "missing", "op", "r"));

        RiskCaseEntity foreign = blockedCase();
        foreign.gameId = "other_game";
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(foreign));
        assertThrows(IllegalArgumentException.class, () -> service.unblock(GAME, CASE_ID, "op", "r"));

        RiskCaseEntity openCase = blockedCase();
        openCase.actionTaken = RiskCaseEntity.ActionType.ALERT;
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(openCase));
        assertThrows(IllegalStateException.class, () -> service.unblock(GAME, CASE_ID, "op", "r"));

        RiskCaseEntity pendingBlock = blockedCase();
        pendingBlock.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(pendingBlock));
        assertThrows(IllegalStateException.class, () -> service.unblock(GAME, CASE_ID, "op", "r"));

        RiskCaseEntity alreadyUnblocked = blockedCase();
        alreadyUnblocked.unblock("op_0", "先前已解除");
        when(riskCaseRepo.findById(CASE_ID)).thenReturn(Optional.of(alreadyUnblocked));
        assertThrows(IllegalStateException.class, () -> service.unblock(GAME, CASE_ID, "op", "r"));

        verify(blockListService, never()).unblock(anyString(), anyString(), anyString());
        verify(riskCaseRepo, never()).save(any());
    }

    // ===== 辅助 =====

    private RiskCaseEntity blockedCase() {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = CASE_ID;
        rc.gameId = GAME;
        rc.caseNumber = "CASE_20261009_0001";
        rc.riskRuleId = "rr_1";
        rc.targetType = "player_id";
        rc.targetId = "p_100";
        rc.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        rc.status = RiskCaseEntity.DecisionStatus.BLOCK;
        rc.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        rc.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        return rc;
    }

    private BlockListEntity block(String id, boolean active) {
        BlockListEntity b = new BlockListEntity();
        b.id = id;
        b.gameId = GAME;
        b.targetType = "player_id";
        b.targetValue = "p_100";
        b.riskCaseId = CASE_ID;
        if (active) {
            b.blockedAt = java.time.LocalDateTime.now();
        } else {
            // 已解除：unblockedAt 非空
            b.unblock("op_0", "先前已解除");
        }
        return b;
    }
}
