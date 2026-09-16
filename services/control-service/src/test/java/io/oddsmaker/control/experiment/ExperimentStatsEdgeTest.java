package io.oddsmaker.control.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计引擎边角分支：空样本 ArmStat、基线为零的 relativeLift、小样本 welch、
 * mean 类指标经 compare 走 welch 路径、SRM 配置外变体跳过。
 */
@DisplayName("实验统计边角分支测试")
class ExperimentStatsEdgeTest {

    private ExperimentStatsService stats;

    @BeforeEach
    void setUp() {
        stats = new ExperimentStatsService();
    }

    private static ExperimentStatsService.ArmStat arm(long count, double sum, double sumSquares, long successes) {
        ExperimentStatsService.ArmStat a = new ExperimentStatsService.ArmStat();
        a.variant = "v";
        a.count = count;
        a.sum = sum;
        a.sumSquares = sumSquares;
        a.successes = successes;
        return a;
    }

    @Test
    @DisplayName("ArmStat：count=0 时 mean/rate 为 0，count<2 时 variance 为 0")
    void armStatDegenerateCases() {
        ExperimentStatsService.ArmStat empty = arm(0, 10, 100, 5);
        assertEquals(0.0, empty.mean());
        assertEquals(0.0, empty.rate());
        ExperimentStatsService.ArmStat single = arm(1, 5, 25, 0);
        assertEquals(0.0, single.variance());
        // 正常方差
        ExperimentStatsService.ArmStat pair = arm(2, 10, 52, 0);
        assertEquals(2.0, pair.variance(), 1e-9);
    }

    @Test
    @DisplayName("compare：均值类指标（successes 全 0）走 welch 路径")
    void compareMeanLikeUsesWelch() {
        Map<String, ExperimentStatsService.ArmStat> arms = new LinkedHashMap<>();
        arms.put("control", arm(100, 500, 2600, 0));
        arms.put("t1", arm(100, 600, 3700, 0));
        var out = stats.compare("revenue", "control", arms);
        assertEquals(1, out.size());
        assertEquals("welch_t_test", out.get(0).testType);
        assertEquals(5.0, out.get(0).controlValue, 1e-9);
        assertEquals(6.0, out.get(0).treatmentValue, 1e-9);
        // 成功数超出样本数 → 非转化率口径，同样走 welch
        Map<String, ExperimentStatsService.ArmStat> invalid = new LinkedHashMap<>();
        invalid.put("control", arm(10, 0, 0, 20)); // successes > count
        invalid.put("t1", arm(10, 0, 0, 20));
        assertEquals("welch_t_test", stats.compare("m", "control", invalid).get(0).testType);
    }

    @Test
    @DisplayName("转化类基线比例为 0：relativeLift 为 null")
    void proportionLiftNullWhenControlRateZero() {
        var c = stats.proportionTest("cvr", arm(50, 0, 0, 0), arm(50, 0, 0, 10));
        assertNotNull(c);
        assertNull(c.relativeLift);
        assertEquals(0.0, c.controlValue, 1e-12);
        assertEquals(0.2, c.treatmentValue, 1e-12);
    }

    @Test
    @DisplayName("welch：基线均值为 0 时 relativeLift 为 null；样本 <2 只标记 lowPower")
    void welchDegenerateBranches() {
        var zeroBase = stats.welchTest("rev", arm(30, 0, 0, 0), arm(30, 90, 300, 0));
        assertNull(zeroBase.relativeLift);

        var tiny = stats.welchTest("rev", arm(1, 3, 9, 0), arm(30, 60, 200, 0));
        assertTrue(tiny.lowPowerHint);
        assertEquals(0.0, tiny.pValue, 1e-12); // 样本不足不计算 p 值
    }

    @Test
    @DisplayName("SRM：配置外变体（历史遗留桶）不计入卡方")
    void srmSkipsUnconfiguredVariant() {
        Map<String, Long> observed = new LinkedHashMap<>();
        observed.put("control", 500L);
        observed.put("t1", 500L);
        observed.put("legacy_bucket", 1L); // expectedWeights 无此变体
        Map<String, Integer> weights = Map.of("control", 1, "t1", 1);
        ExperimentStatsService.SrmResult r = stats.srm(observed, weights);
        // control/t1 相对期望 500.5 仍均衡，legacy 自身不产生卡方贡献 → 不检出
        assertFalse(r.detected);
        assertEquals(1001, r.totalSamples); // legacy 计入总量
        assertTrue(r.pValue > 0.05);
    }
}
