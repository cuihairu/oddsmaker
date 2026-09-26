package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 付费倾向启发式打分测试：golden 与 Python 侧（oddsmaker_ml.models_propensity.
 * heuristic_propensity_scores）同输入同结果，锁定双端镜像公式不漂移。
 */
@DisplayName("付费倾向启发式打分测试")
class PropensityScorerTest {

    @Test
    @DisplayName("golden：四行输入与 Python heuristic_propensity_scores 同结果")
    void goldenMatchesPython() {
        // X = [[10,20,200,50],[5,3,40,0],[30,0,0,0],[0,60,900,120]] → [0.71, 0.112, 0.01, 0.87]
        assertEquals(0.71, PropensityScorer.score(10, 20, 50).score(), 1e-12);
        assertEquals(0.112, PropensityScorer.score(5, 3, 0).score(), 1e-12);
        assertEquals(0.01, PropensityScorer.score(30, 0, 0).score(), 1e-12);   // 0.12−0.12=0 → 下限 clamp
        assertEquals(0.87, PropensityScorer.score(0, 60, 120).score(), 1e-12); // sessions 60 → 截断 50
    }

    @Test
    @DisplayName("分级界：付费用户恒 high（付费下限 0.55）；未付费跨 MEDIUM=0.25 分界")
    void levels() {
        assertEquals("high", PropensityScorer.score(30, 0, 10).level());  // 0.67−0.12 = 0.55
        assertEquals("medium", PropensityScorer.score(0, 33, 0).level()); // 0.252 ≥ 0.25
        assertEquals("low", PropensityScorer.score(0, 32, 0).level());    // 0.248 < 0.25
        assertEquals("low", PropensityScorer.score(5, 0, 0).level());     // 0.10
    }

    @Test
    @DisplayName("输入截断：负值夹 0，超上限夹 30/50")
    void inputClamped() {
        assertEquals(0.12, PropensityScorer.score(-5, -3, 0).score(), 1e-12);
        assertEquals(0.01, PropensityScorer.score(90, -1, 0).score(), 1e-12); // days 夹 30 → 下限
        assertEquals(0.87, PropensityScorer.score(0, 500, 100).score(), 1e-12);
    }

    @Test
    @DisplayName("可解释原因：按是否历史付费给单一原因")
    void reasons() {
        assertEquals(List.of("历史有付费"), PropensityScorer.score(10, 20, 50).reasons());
        assertEquals(List.of("无付费记录"), PropensityScorer.score(10, 20, 0).reasons());
    }
}
