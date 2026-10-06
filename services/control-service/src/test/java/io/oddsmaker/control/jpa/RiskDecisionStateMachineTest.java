package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 验收钉子（计划书 §5.2）：RiskCase 判定状态机合法/非法流转表。
 *
 * 状态机 {@code OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED}：
 * 严格升层单向推进（可跨带直判，如 OPEN→BLOCK）；OPEN 不直达 RESOLVED（没有判定的案子不允许直接结案）；
 * 同层改判（REVIEW→ALERT、THROTTLE→BLOCK）、任何回退、终态复活（RESOLVED→*）均非法。
 */
@DisplayName("B6 判定状态机：tier 分带、合法/非法流转表、transitionTo、completeReview 结案接线")
class RiskDecisionStateMachineTest {

    private static RiskCaseEntity caseIn(RiskCaseEntity.DecisionStatus status) {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = "rc_test";
        rc.riskRuleId = "rr_1";
        rc.gameId = "g1";
        rc.caseNumber = "CASE_1";
        rc.targetType = "player_id";
        rc.targetId = "u1";
        rc.status = status;
        return rc;
    }

    // ===== tier 分带 =====

    @Test
    @DisplayName("tier：OPEN=0，REVIEW/ALERT/MARK=1，THROTTLE/BLOCK=2，RESOLVED=3")
    void tierBands() {
        assertEquals(0, RiskCaseEntity.DecisionStatus.OPEN.tier());
        assertEquals(1, RiskCaseEntity.DecisionStatus.REVIEW.tier());
        assertEquals(1, RiskCaseEntity.DecisionStatus.ALERT.tier());
        assertEquals(1, RiskCaseEntity.DecisionStatus.MARK.tier());
        assertEquals(2, RiskCaseEntity.DecisionStatus.THROTTLE.tier());
        assertEquals(2, RiskCaseEntity.DecisionStatus.BLOCK.tier());
        assertEquals(3, RiskCaseEntity.DecisionStatus.RESOLVED.tier());
    }

    // ===== 合法流转 =====

    @Test
    @DisplayName("合法：OPEN 直判五档（含跨带 OPEN→BLOCK）")
    void legalFromOpen() {
        for (RiskCaseEntity.DecisionStatus target : RiskCaseEntity.DecisionStatus.values()) {
            if (target == RiskCaseEntity.DecisionStatus.OPEN || target == RiskCaseEntity.DecisionStatus.RESOLVED) {
                continue;
            }
            assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.OPEN, target),
                "OPEN -> " + target + " 应合法");
        }
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.OPEN, RiskCaseEntity.DecisionStatus.OPEN));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.OPEN, RiskCaseEntity.DecisionStatus.RESOLVED));
    }

    @Test
    @DisplayName("合法：一级判定升二级（REVIEW/MARK → THROTTLE/BLOCK）")
    void legalTierOneToTwo() {
        assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.REVIEW, RiskCaseEntity.DecisionStatus.THROTTLE));
        assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.REVIEW, RiskCaseEntity.DecisionStatus.BLOCK));
        assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.MARK, RiskCaseEntity.DecisionStatus.THROTTLE));
        assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.MARK, RiskCaseEntity.DecisionStatus.BLOCK));
        assertTrue(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.ALERT, RiskCaseEntity.DecisionStatus.BLOCK));
    }

    @Test
    @DisplayName("合法：任一已判定态可结案（REVIEW/ALERT/MARK/THROTTLE/BLOCK → RESOLVED）")
    void legalResolveFromDecided() {
        for (RiskCaseEntity.DecisionStatus from : RiskCaseEntity.DecisionStatus.values()) {
            if (from == RiskCaseEntity.DecisionStatus.OPEN || from == RiskCaseEntity.DecisionStatus.RESOLVED) {
                continue;
            }
            assertTrue(RiskCaseEntity.canTransition(from, RiskCaseEntity.DecisionStatus.RESOLVED),
                from + " -> RESOLVED 应合法");
        }
    }

    // ===== 非法流转 =====

    @Test
    @DisplayName("非法：OPEN 不直达 RESOLVED（没有判定的案子不允许直接结案）")
    void openCannotResolveDirectly() {
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.OPEN, RiskCaseEntity.DecisionStatus.RESOLVED));
    }

    @Test
    @DisplayName("非法：同层横向改判（REVIEW↔ALERT↔MARK、THROTTLE↔BLOCK）")
    void sameTierHorizontalIllegal() {
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.REVIEW, RiskCaseEntity.DecisionStatus.ALERT));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.ALERT, RiskCaseEntity.DecisionStatus.REVIEW));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.REVIEW, RiskCaseEntity.DecisionStatus.MARK));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.MARK, RiskCaseEntity.DecisionStatus.ALERT));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.THROTTLE, RiskCaseEntity.DecisionStatus.BLOCK));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.BLOCK, RiskCaseEntity.DecisionStatus.THROTTLE));
    }

    @Test
    @DisplayName("非法：任何回退与终态复活（*→OPEN、RESOLVED→*）")
    void backwardAndResurrectionIllegal() {
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.REVIEW, RiskCaseEntity.DecisionStatus.OPEN));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.THROTTLE, RiskCaseEntity.DecisionStatus.REVIEW));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.BLOCK, RiskCaseEntity.DecisionStatus.OPEN));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.RESOLVED, RiskCaseEntity.DecisionStatus.REVIEW));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.RESOLVED, RiskCaseEntity.DecisionStatus.BLOCK));
    }

    @Test
    @DisplayName("非法：null 入参一律拒绝")
    void nullArgsIllegal() {
        assertFalse(RiskCaseEntity.canTransition(null, RiskCaseEntity.DecisionStatus.REVIEW));
        assertFalse(RiskCaseEntity.canTransition(RiskCaseEntity.DecisionStatus.OPEN, null));
        assertFalse(RiskCaseEntity.canTransition(null, null));
    }

    // ===== transitionTo =====

    @Test
    @DisplayName("transitionTo：合法流转推进 status，非法流转抛 IllegalStateException 且状态不变")
    void transitionToEnforcesTable() {
        RiskCaseEntity legal = caseIn(RiskCaseEntity.DecisionStatus.OPEN);
        legal.transitionTo(RiskCaseEntity.DecisionStatus.BLOCK);
        assertEquals(RiskCaseEntity.DecisionStatus.BLOCK, legal.status);

        RiskCaseEntity escalate = caseIn(RiskCaseEntity.DecisionStatus.REVIEW);
        escalate.transitionTo(RiskCaseEntity.DecisionStatus.RESOLVED);
        assertEquals(RiskCaseEntity.DecisionStatus.RESOLVED, escalate.status);

        RiskCaseEntity illegal = caseIn(RiskCaseEntity.DecisionStatus.OPEN);
        assertThrows(IllegalStateException.class, () -> illegal.transitionTo(RiskCaseEntity.DecisionStatus.RESOLVED));
        assertEquals(RiskCaseEntity.DecisionStatus.OPEN, illegal.status, "非法流转后状态保持原状");

        RiskCaseEntity sameTier = caseIn(RiskCaseEntity.DecisionStatus.THROTTLE);
        assertThrows(IllegalStateException.class, () -> sameTier.transitionTo(RiskCaseEntity.DecisionStatus.BLOCK));
        assertEquals(RiskCaseEntity.DecisionStatus.THROTTLE, sameTier.status);
    }

    // ===== completeReview 结案接线 =====

    @Test
    @DisplayName("completeReview：已判定案件（REVIEW/BLOCK）结案同步走到 RESOLVED")
    void completeReviewResolvesDecidedCases() {
        RiskCaseEntity reviewed = caseIn(RiskCaseEntity.DecisionStatus.REVIEW);
        reviewed.completeReview("op_1", "核实作弊", "confirmed_fraud");
        assertEquals(RiskCaseEntity.DecisionStatus.RESOLVED, reviewed.status);
        assertNotNull(reviewed.resolvedAt);
        assertEquals("confirmed_fraud", reviewed.disposition);

        RiskCaseEntity blocked = caseIn(RiskCaseEntity.DecisionStatus.BLOCK);
        blocked.completeReview("op_1", "误封解除", "confirmed_benign");
        assertEquals(RiskCaseEntity.DecisionStatus.RESOLVED, blocked.status);
    }

    @Test
    @DisplayName("completeReview：存量 OPEN 行不强行跳层（状态保持 OPEN，兼容历史数据）")
    void completeReviewKeepsLegacyOpenRows() {
        RiskCaseEntity legacy = caseIn(RiskCaseEntity.DecisionStatus.OPEN);
        legacy.completeReview("op_1", "历史案件手工结案", "inconclusive");
        assertEquals(RiskCaseEntity.DecisionStatus.OPEN, legacy.status);
        assertNotNull(legacy.resolvedAt, "审核元数据照常落位");
    }
}
