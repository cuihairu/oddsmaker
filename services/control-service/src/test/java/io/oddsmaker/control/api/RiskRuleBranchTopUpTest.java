package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控规则控制器分支对侧补充（BRANCH 收口）：
 * gameId null/空白跳过守卫、create 缺 gameId 两侧、update 回落 existing.gameId
 * 与 update/setStatus 返回 null 的 404 侧、toResp 枚举全 null 投影。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("风控规则控制器分支测试")
class RiskRuleBranchTopUpTest {

    @Mock
    private RiskRuleService riskRuleService;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private RiskRuleController controller;

    private RiskRuleEntity rule() {
        RiskRuleEntity r = new RiskRuleEntity();
        r.id = "rr_1";
        r.gameId = "g";
        return r;
    }

    @Test
    @DisplayName("list：gameId null/空白跳过游戏守卫，非空才校验")
    void listBlankGameIdSkipsGuard() {
        lenient().when(riskRuleService.list(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        controller.list(null, null, null, null, null, 0, 20);
        controller.list("   ", null, null, null, null, 0, 20);
        verify(accessGuard, never()).requireGamePermission(anyString(), anyString());

        controller.list("g", null, null, null, null, 0, 20);
        verify(accessGuard).requireGamePermission("g", "risk_rule:read");
    }

    @Test
    @DisplayName("create：gameId null 与空白均拒绝（400 语义由异常处理器转）")
    void createRequiresGameId() {
        assertThrows(IllegalArgumentException.class, () -> controller.create(new RiskRuleEntity()));
        RiskRuleEntity blank = new RiskRuleEntity();
        blank.gameId = "   ";
        assertThrows(IllegalArgumentException.class, () -> controller.create(blank));
        verify(riskRuleService, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("update：req.gameId null/空白回落 existing.gameId；服务返回 null → 404")
    void updateFallsBackToExistingGameId() {
        RiskRuleEntity existing = rule();
        when(riskRuleService.get("rr_1")).thenReturn(existing);
        when(riskRuleService.update(eq("rr_1"), any(), anyString())).thenReturn(existing);

        controller.update("rr_1", new RiskRuleEntity());
        verify(accessGuard).requireGamePermission("g", "risk_rule:update");

        RiskRuleEntity blank = new RiskRuleEntity();
        blank.gameId = "  ";
        controller.update("rr_1", blank);
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission("g", "risk_rule:update");

        // req 显式携带 gameId → 守卫用 req 的
        RiskRuleEntity other = new RiskRuleEntity();
        other.gameId = "g2";
        controller.update("rr_1", other);
        verify(accessGuard).requireGamePermission("g2", "risk_rule:update");

        // 服务层返回 null（并发删除）→ 404
        when(riskRuleService.update(eq("rr_gone"), any(), anyString())).thenReturn(null);
        when(riskRuleService.get("rr_gone")).thenReturn(existing);
        assertEquals(404, controller.update("rr_gone", new RiskRuleEntity()).getStatusCode().value());
    }

    @Test
    @DisplayName("启停：setStatus 返回 null → 404（并发已删侧）")
    void toggleNullUpdateReturns404() {
        RiskRuleEntity existing = rule();
        when(riskRuleService.get("rr_1")).thenReturn(existing);
        when(riskRuleService.setStatus(eq("rr_1"), anyBoolean(), anyString())).thenReturn(null);

        assertEquals(404, controller.enable("rr_1").getStatusCode().value());
        assertEquals(404, controller.disable("rr_1").getStatusCode().value());
    }

    @Test
    @DisplayName("toResp 投影：枚举全 null 输出 null，status 决定 enabled 两态")
    void toRespNullEnumsAndStatusSides() {
        // 枚举字段显式置 null（实体有非 null 默认值）
        RiskRuleEntity sparse = rule();
        sparse.category = null;
        sparse.ruleType = null;
        sparse.riskLevel = null;
        sparse.actionType = null;
        sparse.status = null;
        when(riskRuleService.get("rr_1")).thenReturn(sparse);

        RiskRuleController.RiskRuleResp resp = controller.get("rr_1").getBody();
        assertNull(resp.category);
        assertNull(resp.type);
        assertNull(resp.riskLevel);
        assertNull(resp.actionType);
        assertNull(resp.status);
        assertEquals(false, resp.enabled);   // null != ACTIVE

        // ACTIVE → enabled true；非 ACTIVE（INACTIVE）→ false
        RiskRuleEntity active = rule();
        active.status = RiskRuleEntity.RuleStatus.ACTIVE;
        when(riskRuleService.get("rr_on")).thenReturn(active);
        assertEquals(true, controller.get("rr_on").getBody().enabled);

        RiskRuleEntity inactive = rule();
        inactive.status = RiskRuleEntity.RuleStatus.PAUSED;
        when(riskRuleService.get("rr_off")).thenReturn(inactive);
        assertEquals(false, controller.get("rr_off").getBody().enabled);
    }
}
