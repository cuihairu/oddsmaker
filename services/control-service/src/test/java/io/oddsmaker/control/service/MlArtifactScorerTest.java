package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ml 产物线性打分测试。
 * golden case 期望值由 ml/（Python）侧按 serving 公式计算后写死：
 * Java 与 Python 同输入必须同结果（对照验收）。
 */
@DisplayName("ml 产物线性打分测试")
class MlArtifactScorerTest {

    /** Python: z = 0.8*2 - 0.3*5 - 0.002*120 - 0.01*25 + (-1.5) = -1.89 → sigmoid = 0.131244469439 */
    private static final double[] CHURN_COEFS = {0.8, -0.3, -0.002, -0.01};
    private static final double[] CHURN_FEATURES = {2.0, 5.0, 120.0, 25.0};

    /** Python: z = 0.5*2 + 0.4*1 + 0.3*0 + 0.2*3 + 0.1*2 + (-2.0) = 0.2 → sigmoid = 0.549833997312 */
    private static final double[] RISK_COEFS = {0.5, 0.4, 0.3, 0.2, 0.1};
    private static final double[] RISK_FEATURES = {2.0, 1.0, 0.0, 3.0, 2.0};

    @Test
    @DisplayName("churn golden case：与 Python sigmoid(dot(w,x)+b) 同结果")
    void churnParityWithPython() {
        assertEquals(0.131244469439, MlArtifactScorer.score(CHURN_COEFS, -1.5, CHURN_FEATURES), 1e-9);
    }

    @Test
    @DisplayName("risk golden case：与 Python 同结果")
    void riskParityWithPython() {
        assertEquals(0.549833997312, MlArtifactScorer.score(RISK_COEFS, -2.0, RISK_FEATURES), 1e-9);
    }

    @Test
    @DisplayName("长度不一致抛 IllegalArgumentException（调用方回落启发式）")
    void lengthMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MlArtifactScorer.score(CHURN_COEFS, 0.0, RISK_FEATURES));
    }

    @Test
    @DisplayName("sigmoid 边界：0 → 0.5，大负值 → 0（exp 溢出为 Inf），大正值 → 1（exp 下溢为 0）")
    void sigmoidBoundaries() {
        assertEquals(0.5, MlArtifactScorer.sigmoid(0.0), 1e-12);
        assertEquals(0.0, MlArtifactScorer.sigmoid(-800.0), 0.0);
        assertEquals(1.0, MlArtifactScorer.sigmoid(800.0), 0.0);
    }

    @Test
    @DisplayName("parseFeatureNames / parseCoefficients：合法与非法输入")
    void parsing() {
        assertEquals(List.of("a", "b"), MlArtifactScorer.parseFeatureNames("[\"a\",\"b\"]"));
        assertTrue(MlArtifactScorer.parseFeatureNames("[1,2]").size() == 2);  // 非字符串元素按文本取

        // 非法输入
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseFeatureNames("not-json"));
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseFeatureNames("{}"));
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseFeatureNames("[]"));
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseCoefficients("not-json"));
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseCoefficients("{\"x\":1}"));
        assertThrows(IllegalArgumentException.class, () -> MlArtifactScorer.parseCoefficients("[1,\"x\"]"));
    }
}
