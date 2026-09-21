package io.oddsmaker.control.experiment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验统计引擎分支对侧补充（BRANCH 收口）：
 * 空样本/se=0 的比例检验、小样本均值检验、SRM 非法权重/空配置/检出侧、
 * gamma 级数与连分式 200 轮不收敛侧（大参数域直调）、erf 负数侧。
 */
@DisplayName("实验统计引擎分支测试")
class ExperimentStatsBranchTopUpTest {

    private final ExperimentStatsService svc = new ExperimentStatsService();

    private static ExperimentStatsService.ArmStat arm(String variant, long count, double sum,
                                                      double sumSquares, long successes) {
        ExperimentStatsService.ArmStat a = new ExperimentStatsService.ArmStat();
        a.variant = variant;
        a.count = count;
        a.sum = sum;
        a.sumSquares = sumSquares;
        a.successes = successes;
        return a;
    }

    @Test
    @DisplayName("compare：controlVariant 为 null / 不在 arms 中 → 空结果")
    void compareRejectsMissingControl() {
        Map<String, ExperimentStatsService.ArmStat> arms = Map.of(
            "a", arm("a", 100, 0, 0, 10),
            "b", arm("b", 100, 0, 0, 20));
        assertTrue(svc.compare("cvr", null, arms).isEmpty());
        assertTrue(svc.compare("cvr", "ghost", arms).isEmpty());
    }

    @Test
    @DisplayName("比例检验：control/treatment 零样本侧与 pooled=0 时 se=0 侧")
    void proportionTestZeroSampleSides() {
        // control.count = 0 → n1 > 0 false 短路
        ExperimentStatsService.Comparison c1 = svc.proportionTest("cvr",
            arm("a", 0, 0, 0, 0), arm("b", 100, 0, 0, 20));
        assertEquals(0.0, c1.pValue);
        assertEquals(0.0, c1.ciLow);

        // treatment.count = 0 → n2 > 0 false 侧
        ExperimentStatsService.Comparison c2 = svc.proportionTest("cvr",
            arm("a", 100, 0, 0, 20), arm("b", 0, 0, 0, 0));
        assertEquals(0.0, c2.pValue);

        // 双方零成功 → pooled=0 → se=0 侧（pValue 保持 0，CI 仍计算）
        ExperimentStatsService.Comparison c3 = svc.proportionTest("cvr",
            arm("a", 100, 0, 0, 0), arm("b", 100, 0, 0, 0));
        assertEquals(0.0, c3.pValue);
        assertEquals(0.0, c3.absoluteDiff);
    }

    @Test
    @DisplayName("均值检验：样本 <2 走 lowPowerHint 分支；<30 触发低功效提示两侧")
    void welchTestSmallSampleSides() {
        // n1=1 → 整段统计跳过（n1 >= 2 false 侧），lowPowerHint=true
        ExperimentStatsService.Comparison tiny = svc.welchTest("arpu",
            arm("a", 1, 5, 25, 0), arm("b", 50, 100, 300, 0));
        assertTrue(tiny.lowPowerHint);
        assertEquals(0.0, tiny.pValue);

        // n1=2（<30 → true 侧）
        ExperimentStatsService.Comparison smallControl = svc.welchTest("arpu",
            arm("a", 2, 10, 60, 0), arm("b", 100, 500, 3000, 0));
        assertTrue(smallControl.lowPowerHint);

        // n2=2（cond1 false、cond2 true 侧）
        ExperimentStatsService.Comparison smallTreatment = svc.welchTest("arpu",
            arm("a", 100, 500, 3000, 0), arm("b", 2, 10, 60, 0));
        assertTrue(smallTreatment.lowPowerHint);
    }

    @Test
    @DisplayName("SRM：null/非正权重、空样本、单变体、空权重配置均返回空结果")
    void srmRejectsInvalidInputs() {
        Map<String, Long> observed = new LinkedHashMap<>();
        observed.put("a", 100L);
        observed.put("b", 100L);

        // 权重含 null 值 → 直接返回
        Map<String, Integer> nullWeight = new HashMap<>();
        nullWeight.put("a", null);
        nullWeight.put("b", 1);
        ExperimentStatsService.SrmResult r1 = svc.srm(observed, nullWeight);
        assertEquals(0.0, r1.chiSquare);

        // 权重含 0/负值 → 直接返回
        ExperimentStatsService.SrmResult r2 = svc.srm(observed, Map.of("a", 0, "b", 1));
        assertEquals(0.0, r2.chiSquare);
        ExperimentStatsService.SrmResult r2n = svc.srm(observed, Map.of("a", -1, "b", 1));
        assertEquals(0.0, r2n.chiSquare);

        // 总样本为 0 → 返回
        ExperimentStatsService.SrmResult r3 = svc.srm(Map.of("a", 0L, "b", 0L), Map.of("a", 1, "b", 1));
        assertEquals(0.0, r3.chiSquare);

        // 单变体 → size < 2 返回
        ExperimentStatsService.SrmResult r4 = svc.srm(Map.of("a", 10L), Map.of("a", 1));
        assertEquals(0.0, r4.chiSquare);

        // 空权重配置 → weightSum <= 0 返回
        ExperimentStatsService.SrmResult r5 = svc.srm(observed, Map.of());
        assertEquals(0.0, r5.chiSquare);
        assertFalse(r5.detected);
    }

    @Test
    @DisplayName("SRM：极端比例失衡触发 detected；配置外变体不参与计算")
    void srmDetectsExtremeImbalance() {
        Map<String, Long> observed = new LinkedHashMap<>();
        observed.put("a", 1_000_000L);
        observed.put("b", 1L);
        ExperimentStatsService.SrmResult r = svc.srm(observed, Map.of("a", 1, "b", 1));
        assertTrue(r.chiSquare > 1000);
        assertTrue(r.detected);   // p 值钳到 1e-300，p < 0.001
        assertEquals(1, r.degreesOfFreedom);

        // 配置外变体（权重缺省 0）→ expected=0 → continue 不参与
        Map<String, Long> withGhost = new LinkedHashMap<>();
        withGhost.put("a", 100L);
        withGhost.put("b", 100L);
        withGhost.put("c", 50L);
        ExperimentStatsService.SrmResult ghost = svc.srm(withGhost, Map.of("a", 1, "b", 1));
        assertFalse(ghost.detected);   // a/b 均衡，c 被忽略
    }

    @Test
    @DisplayName("gamma 级数/连分式 200 轮不收敛侧：大参数域直调（srm 的 df 域内不可达）")
    void gammaLoopsNeverConvergeSide() {
        // 级数分支（x < a+1）：a=10000 时项比收敛极慢，200 轮内 |del|<|sum|*1e-14 不成立
        Double series = ReflectionTestUtils.invokeMethod(ExperimentStatsService.class,
            "upperGammaQ", 10000.0, 10000.5);
        assertNotNull(series);
        assertTrue(series >= 0.0 && series <= 1.0);

        // 连分式分支（x >= a+1）：a=100000、x=a+2 时 del 收敛到 1 极慢
        Double cf = ReflectionTestUtils.invokeMethod(ExperimentStatsService.class,
            "upperGammaQ", 100000.0, 100002.0);
        assertNotNull(cf);
        assertTrue(cf >= 0.0 && cf <= 1.0);
    }

    @Test
    @DisplayName("normalCdf 负数侧：erf 的 x<0 分支（twoTailedNormalP 只喂 abs，直调覆盖）")
    void normalCdfNegativeSide() {
        double below = ExperimentStatsService.normalCdf(-1.0);
        double above = ExperimentStatsService.normalCdf(1.0);
        assertEquals(1.0, below + above, 1e-12);
        assertTrue(below < 0.5);

        assertEquals(1.0, ExperimentStatsService.chiSquareSurvival(0.0, 3), 1e-12);
    }

    @Test
    @DisplayName("aggregate：快照按指标/变体合并")
    void aggregateMergesSnapshots() {
        ExperimentMetricSnapshotEntity s1 = new ExperimentMetricSnapshotEntity();
        s1.metricName = "cvr";
        s1.variant = "a";
        s1.count = 10;
        s1.sum = 5;
        s1.sumSquares = 5;
        s1.successes = 3;
        ExperimentMetricSnapshotEntity s2 = new ExperimentMetricSnapshotEntity();
        s2.metricName = "cvr";
        s2.variant = "a";
        s2.count = 15;
        s2.sum = 7;
        s2.sumSquares = 9;
        s2.successes = 4;
        ExperimentMetricSnapshotEntity s3 = new ExperimentMetricSnapshotEntity();
        s3.metricName = "arpu";
        s3.variant = "b";
        s3.count = 100;
        s3.sum = 500;
        s3.sumSquares = 3000;
        s3.successes = 0;

        Map<String, Map<String, ExperimentStatsService.ArmStat>> byMetric =
            ExperimentStatsService.aggregate(List.of(s1, s2, s3));
        assertEquals(2, byMetric.size());
        ExperimentStatsService.ArmStat merged = byMetric.get("cvr").get("a");
        assertEquals(25, merged.count);
        assertEquals(12, merged.sum, 1e-9);
        assertEquals(7, merged.successes);
    }
}
