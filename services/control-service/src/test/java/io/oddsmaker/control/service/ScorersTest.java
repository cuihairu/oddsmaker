package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流失/风险打分器纯逻辑测试。
 */
@DisplayName("流失与风险打分器")
class ScorersTest {

    @Test
    @DisplayName("流失：长期不活跃得高分并给出原因")
    void churnHighRisk() {
        ChurnScorer.Scored scored = ChurnScorer.score(20, 1, 0);
        assertTrue(scored.score() >= 0.7);
        assertEquals("high", scored.level());
        assertTrue(scored.reasons().contains("超过14天未活跃"));
        assertTrue(scored.reasons().contains("30天会话数不足3次"));
    }

    @Test
    @DisplayName("流失：活跃付费用户得低分")
    void churnActivePayerLowRisk() {
        ChurnScorer.Scored scored = ChurnScorer.score(1, 25, 99.0);
        // 1/14*0.7 + (1-25/30)*0.3 - 0.1 ≈ 0.05+0.05-0.1 = 0
        assertEquals(0.0, scored.score(), 1e-9);
        assertEquals("low", scored.level());
        assertTrue(scored.reasons().contains("付费用户权重下调"));
    }

    @Test
    @DisplayName("流失：中等不活跃为 medium，负天数不活跃项归零")
    void churnMediumAndClamped() {
        ChurnScorer.Scored scored = ChurnScorer.score(8, 5, 0);
        // 8/14*0.7 + (1-5/30)*0.3 ≈ 0.4+0.25 = 0.65 → medium
        assertEquals("medium", scored.level());
        // 负天数按 0 处理（不活跃项为 0，仅剩低参与度项 0.3）
        assertEquals(0.3, ChurnScorer.score(-5, 0, 0).score(), 1e-9);
        assertTrue(ChurnScorer.score(999, 0, 0).score() <= 1.0);
    }

    @Test
    @DisplayName("风险：严重度加权归一与分级")
    void riskWeightedScore() {
        // 2×CRITICAL + 1×HIGH = 8+3 = 11 → 11/40 = 0.275 → medium
        RiskScorer.Scored medium = RiskScorer.score(2, 1, 0, 0);
        assertEquals(0.275, medium.score(), 1e-9);
        assertEquals("medium", medium.level());

        // 10×CRITICAL = 40 → 1.0 high 封顶
        RiskScorer.Scored high = RiskScorer.score(10, 0, 0, 0);
        assertEquals(1.0, high.score(), 1e-9);
        assertEquals("high", high.level());

        RiskScorer.Scored low = RiskScorer.score(0, 0, 1, 2);
        assertEquals("low", low.level());
        assertTrue(low.reasons().contains("MEDIUM 命中 1 次"));
        assertTrue(low.reasons().contains("LOW 命中 2 次"));
    }
}
