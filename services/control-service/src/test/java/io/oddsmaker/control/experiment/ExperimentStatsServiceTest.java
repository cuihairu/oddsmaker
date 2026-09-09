package io.oddsmaker.control.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实验统计引擎测试：比例 z 检验与 Welch t 检验的已知值行为。
 */
@DisplayName("实验统计引擎测试")
class ExperimentStatsServiceTest {

    private final ExperimentStatsService stats = new ExperimentStatsService();

    private static ExperimentStatsService.ArmStat arm(String variant, long count, long successes) {
        ExperimentStatsService.ArmStat a = new ExperimentStatsService.ArmStat();
        a.variant = variant;
        a.count = count;
        a.successes = successes;
        a.sum = successes;  // 比例类指标 sum=successes 保持一致
        return a;
    }

    private static ExperimentStatsService.ArmStat armMean(String variant, long count, double sum, double sumSquares) {
        ExperimentStatsService.ArmStat a = new ExperimentStatsService.ArmStat();
        a.variant = variant;
        a.count = count;
        a.sum = sum;
        a.sumSquares = sumSquares;
        return a;
    }

    @Test
    @DisplayName("完全相同的转化率不显著")
    void identicalRatesNotSignificant() {
        ExperimentStatsService.Comparison c = stats.proportionTest("conversion",
            arm("control", 10_000, 1_000),
            arm("treatment", 10_000, 1_000));
        // z=0 时双尾 p=1（无差异的完美证据）；erf 近似固有误差 ~1e-8
        assertEquals(1.0, c.pValue, 1e-6);
        assertFalse(c.significant);
        assertEquals(0.0, c.absoluteDiff, 1e-12);
        assertEquals(0.0, c.relativeLift, 1e-12);
    }

    @Test
    @DisplayName("显著提升被检出（10% → 11%，n=10000）")
    void significantUpliftDetected() {
        ExperimentStatsService.Comparison c = stats.proportionTest("conversion",
            arm("control", 10_000, 1_000),
            arm("treatment", 10_000, 1_100));
        // 双侧 p ≈ 0.026 < 0.05
        assertTrue(c.pValue < 0.05, "p=" + c.pValue);
        assertTrue(c.significant);
        assertEquals(0.10, c.controlValue, 1e-12);
        assertEquals(0.11, c.treatmentValue, 1e-12);
        assertEquals(0.10, c.relativeLift, 0.001);
        // CI 不含 0
        assertTrue(c.ciLow > 0 && c.ciHigh > 0);
        assertEquals("proportion_z_test", c.testType);
    }

    @Test
    @DisplayName("小差异大样本边缘不显著（10% → 10.4%）")
    void marginalDiffNotSignificant() {
        ExperimentStatsService.Comparison c = stats.proportionTest("conversion",
            arm("control", 10_000, 1_000),
            arm("treatment", 10_000, 1_040));
        // diff=0.4pp，z≈0.93 → p≈0.35
        assertEquals(0.35, c.pValue, 0.03);
        assertFalse(c.significant);
        assertTrue(c.ciLow < 0 && c.ciHigh > 0);  // CI 跨 0
    }

    @Test
    @DisplayName("显著下降 lift 为负且被检出")
    void significantDropDetected() {
        ExperimentStatsService.Comparison c = stats.proportionTest("retention",
            arm("control", 5_000, 1_500),
            arm("treatment", 5_000, 1_200));
        assertTrue(c.significant);
        assertTrue(c.absoluteDiff < 0);
        assertTrue(c.relativeLift < 0);
        assertTrue(c.ciHigh < 0);
    }

    @Test
    @DisplayName("Welch t 检验：均值显著提升")
    void welchTestDetectsMeanShift() {
        // 两组各 1000 样本，均值 100 vs 102，标准差 15 → t≈2.8 显著
        double mean1 = 100, sd1 = 15, n1 = 1000;
        double mean2 = 102, sd2 = 15, n2 = 1000;
        ExperimentStatsService.Comparison c = stats.welchTest("arpu",
            armMean("control", (long) n1, mean1 * n1, (sd1 * sd1 + mean1 * mean1) * n1),
            armMean("treatment", (long) n2, mean2 * n2, (sd2 * sd2 + mean2 * mean2) * n2));
        assertEquals(100.0, c.controlValue, 1e-9);
        assertEquals(102.0, c.treatmentValue, 1e-9);
        assertEquals(0.02, c.relativeLift, 1e-9);
        assertTrue(c.pValue < 0.01, "p=" + c.pValue);
        assertTrue(c.significant);
        assertFalse(c.lowPowerHint);
        assertEquals("welch_t_test", c.testType);
    }

    @Test
    @DisplayName("小样本触发 low power 提示")
    void smallSampleFlagsLowPower() {
        ExperimentStatsService.Comparison c = stats.welchTest("arpu",
            armMean("control", 10, 1000, 100_500),
            armMean("treatment", 10, 1040, 108_500));
        assertTrue(c.lowPowerHint);
    }

    @Test
    @DisplayName("多变体对比：以 control 为基线两两比较")
    void multiVariantCompareAgainstControl() {
        Map<String, ExperimentStatsService.ArmStat> arms = Map.of(
            "control", arm("control", 10_000, 1_000),
            "treatment_a", arm("treatment_a", 10_000, 1_100),
            "treatment_b", arm("treatment_b", 10_000, 1_010));
        List<ExperimentStatsService.Comparison> out = stats.compare("conversion", "control", arms);
        assertEquals(2, out.size());
        for (ExperimentStatsService.Comparison c : out) {
            assertEquals("control", c.control);
            assertNotEquals("control", c.treatment);
        }
    }

    @Test
    @DisplayName("control 不在 arms 中返回空")
    void missingControlReturnsEmpty() {
        Map<String, ExperimentStatsService.ArmStat> arms = Map.of(
            "treatment", arm("treatment", 100, 10));
        assertTrue(stats.compare("m", "control", arms).isEmpty());
    }

    @Test
    @DisplayName("快照聚合：跨窗口合并 count/sum/successes")
    void snapshotAggregationMergesWindows() {
        ExperimentMetricSnapshotEntity s1 = snapshot("conversion", "control", 0L, 500, 50, 50);
        ExperimentMetricSnapshotEntity s2 = snapshot("conversion", "control", 3_600_000L, 500, 60, 60);
        ExperimentMetricSnapshotEntity s3 = snapshot("conversion", "treatment", 0L, 500, 75, 75);

        Map<String, Map<String, ExperimentStatsService.ArmStat>> byMetric =
            ExperimentStatsService.aggregate(List.of(s1, s2, s3));

        assertEquals(1, byMetric.size());
        Map<String, ExperimentStatsService.ArmStat> arms = byMetric.get("conversion");
        assertEquals(2, arms.size());
        assertEquals(1000, arms.get("control").count);
        assertEquals(110, arms.get("control").successes);
        assertEquals(500, arms.get("treatment").count);
    }

    @Test
    @DisplayName("正态 CDF 近似精度（erf 有理近似，误差 ~1e-7）")
    void normalCdfAccuracy() {
        assertEquals(0.9750, ExperimentStatsService.normalCdf(1.959964), 1e-4);
        assertEquals(0.5, ExperimentStatsService.normalCdf(0.0), 1e-6);
        assertEquals(0.8413, ExperimentStatsService.normalCdf(1.0), 1e-4);
        assertEquals(0.0228, ExperimentStatsService.twoTailedNormalP(2.28), 1e-3);
    }

    @Test
    @DisplayName("SRM：均衡样本不检出")
    void srm_balancedSampleNotDetected() {
        ExperimentStatsService.SrmResult r = stats.srm(
            Map.of("control", 50_100L, "treatment", 49_900L),
            Map.of("control", 1, "treatment", 1));
        assertEquals(1, r.degreesOfFreedom);
        assertEquals(100_000L, r.totalSamples);
        assertTrue(r.pValue > 0.001);
        assertFalse(r.detected);
    }

    @Test
    @DisplayName("SRM：样本比例失衡检出（60/40 vs 期望 50/50）")
    void srm_skewedSampleDetected() {
        ExperimentStatsService.SrmResult r = stats.srm(
            Map.of("control", 30_000L, "treatment", 20_000L),
            Map.of("control", 1, "treatment", 1));
        // 卡方 = (5000²/25000)×2 = 2000，p 值下溢 clamp 后仍 < 0.001
        assertEquals(2000.0, r.chiSquare, 1e-3);
        assertTrue(r.pValue < 1e-10);
        assertTrue(r.pValue > 0);
        assertTrue(r.detected);
    }

    @Test
    @DisplayName("SRM：非均衡权重（70/30）被正确识别")
    void srm_weightedVariantsRespected() {
        assertFalse(stats.srm(Map.of("a", 7_000L, "b", 3_000L), Map.of("a", 7, "b", 3)).detected);
        assertTrue(stats.srm(Map.of("a", 5_000L, "b", 5_000L), Map.of("a", 7, "b", 3)).detected);
    }

    @Test
    @DisplayName("SRM：单变体/零样本/非法权重返回中性结果")
    void srm_degenerateInputsReturnNeutralResult() {
        assertFalse(stats.srm(Map.of("a", 100L), Map.of("a", 1)).detected);
        ExperimentStatsService.SrmResult empty = stats.srm(Map.of(), Map.of("a", 1));
        assertFalse(empty.detected);
        assertEquals(0.0, empty.chiSquare);
        assertFalse(stats.srm(Map.of("a", 1L, "b", 1L), Map.of("a", 0, "b", -1)).detected);
    }

    @Test
    @DisplayName("卡方生存函数与已知分位值一致")
    void chiSquareSurvival_matchesKnownValues() {
        assertEquals(0.05, ExperimentStatsService.chiSquareSurvival(3.8415, 1), 1e-3);
        assertEquals(0.05, ExperimentStatsService.chiSquareSurvival(5.9915, 2), 1e-3);
        assertEquals(0.0099, ExperimentStatsService.chiSquareSurvival(13.2767, 4), 1e-3);
        assertEquals(1.0, ExperimentStatsService.chiSquareSurvival(0, 3), 1e-12);
    }

    @Test
    @DisplayName("logGamma 与已知值一致（含 x<0.5 反射分支）")
    void logGamma_matchesKnownValues() {
        assertEquals(Math.log(Math.sqrt(Math.PI)), ExperimentStatsService.logGamma(0.5), 1e-9);
        assertEquals(0.0, ExperimentStatsService.logGamma(1.0), 1e-9);
        assertEquals(Math.log(24.0), ExperimentStatsService.logGamma(5.0), 1e-9);
        // 0 < x < 0.5 走反射公式分支：Γ(0.25) ≈ 3.6256099
        assertEquals(1.2880225, ExperimentStatsService.logGamma(0.25), 1e-6);
    }

    @Test
    @DisplayName("compare：control 不在 arms 中返回空列表")
    void compareUnknownControlReturnsEmpty() {
        var arms = Map.of("a", arm("a", 100, 10));
        assertTrue(stats.compare("m", "missing", arms).isEmpty());
    }

    private static ExperimentMetricSnapshotEntity snapshot(String metric, String variant,
                                                           long window, long count, long sum, long successes) {
        ExperimentMetricSnapshotEntity e = new ExperimentMetricSnapshotEntity();
        e.metricName = metric;
        e.variant = variant;
        e.windowStart = window;
        e.count = count;
        e.sum = sum;
        e.sumSquares = sum;  // 比例类口径简化
        e.successes = successes;
        return e;
    }
}
