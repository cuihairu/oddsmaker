package io.oddsmaker.control.experiment;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A/B 实验统计引擎。
 *
 * - 转化类指标：两总体比例 z-test（pooled 方差），输出 lift、95% CI、双尾 p 值；
 * - 均值类指标：Welch t 统计量 + 正态近似 p 值（大样本下与 t 分布收敛，样本 < 30 标注 low_power 提示）；
 * - 多变体时以配置中 control_variant（默认第一个变体）为基线两两对比。
 */
@Service
public class ExperimentStatsService {

    /** 单变体样本统计 */
    public static class ArmStat {
        public String variant;
        public long count;
        public double sum;
        public double sumSquares;
        public long successes;

        public double mean() {
            return count > 0 ? sum / count : 0.0;
        }

        public double rate() {
            return count > 0 ? (double) successes / count : 0.0;
        }

        public double variance() {
            if (count < 2) return 0.0;
            double m = mean();
            return Math.max(0.0, (sumSquares - count * m * m) / (count - 1));
        }
    }

    /** 一次对比的检验结果 */
    public static class Comparison {
        public String metric;
        public String control;
        public String treatment;
        public double controlValue;
        public double treatmentValue;
        /** 相对提升（treatment - control）/ control，control 为 0 时为 null */
        public Double relativeLift;
        public double absoluteDiff;
        public double ciLow;
        public double ciHigh;
        public double pValue;
        public boolean significant;   // alpha = 0.05
        public long controlSamples;
        public long treatmentSamples;
        public String testType;       // proportion_z_test | welch_t_test
        public boolean lowPowerHint;  // 样本量不足提示
    }

    /** SRM（Sample Ratio Mismatch）卡方检验结果 */
    public static class SrmResult {
        public double chiSquare;
        public int degreesOfFreedom;
        public long totalSamples;
        public double pValue;
        public boolean detected;      // p < 0.001（业界惯例低阈值防误报）
    }

    private static final double Z_95 = 1.959964;  // 97.5 分位
    private static final double ALPHA = 0.05;
    private static final double SRM_ALPHA = 0.001;

    /**
     * 对一个指标执行多变体对比：其余变体逐个与 control 比较。
     */
    public List<Comparison> compare(String metric, String controlVariant, Map<String, ArmStat> arms) {
        List<Comparison> out = new ArrayList<>();
        if (controlVariant == null || !arms.containsKey(controlVariant)) {
            return out;
        }
        ArmStat control = arms.get(controlVariant);
        boolean rateLike = isRateLike(arms.values());
        for (Map.Entry<String, ArmStat> entry : arms.entrySet()) {
            if (entry.getKey().equals(controlVariant)) continue;
            Comparison c = rateLike
                ? proportionTest(metric, control, entry.getValue())
                : welchTest(metric, control, entry.getValue());
            out.add(c);
        }
        return out;
    }

    /** 转化率对比：pooled 比例 z 检验 */
    public Comparison proportionTest(String metric, ArmStat control, ArmStat treatment) {
        Comparison c = base(metric, control, treatment, "proportion_z_test");
        double p1 = control.rate();
        double p2 = treatment.rate();
        c.controlValue = p1;
        c.treatmentValue = p2;
        c.absoluteDiff = p2 - p1;
        c.relativeLift = p1 > 0 ? (p2 - p1) / p1 : null;

        long n1 = control.count;
        long n2 = treatment.count;
        if (n1 > 0 && n2 > 0) {
            double pooled = (double) (control.successes + treatment.successes) / (n1 + n2);
            double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
            if (se > 0) {
                double z = c.absoluteDiff / se;
                c.pValue = twoTailedNormalP(z);
            }
            // 差异的 95% CI（非 pooled 方差）
            double seCi = Math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2);
            c.ciLow = c.absoluteDiff - Z_95 * seCi;
            c.ciHigh = c.absoluteDiff + Z_95 * seCi;
        }
        finalize(c);
        return c;
    }

    /** 均值对比：Welch t 统计量，正态近似 p 值 */
    public Comparison welchTest(String metric, ArmStat control, ArmStat treatment) {
        Comparison c = base(metric, control, treatment, "welch_t_test");
        double m1 = control.mean();
        double m2 = treatment.mean();
        c.controlValue = m1;
        c.treatmentValue = m2;
        c.absoluteDiff = m2 - m1;
        c.relativeLift = m1 != 0 ? (m2 - m1) / m1 : null;

        long n1 = control.count;
        long n2 = treatment.count;
        if (n1 >= 2 && n2 >= 2) {
            double v1 = control.variance();
            double v2 = treatment.variance();
            double se = Math.sqrt(v1 / n1 + v2 / n2);
            if (se > 0) {
                double t = c.absoluteDiff / se;
                c.pValue = twoTailedNormalP(t);
            }
            c.ciLow = c.absoluteDiff - Z_95 * Math.sqrt(v1 / n1 + v2 / n2);
            c.ciHigh = c.absoluteDiff + Z_95 * Math.sqrt(v1 / n1 + v2 / n2);
            c.lowPowerHint = n1 < 30 || n2 < 30;
        } else {
            c.lowPowerHint = true;
        }
        finalize(c);
        return c;
    }

    /**
     * SRM 卡方检验：实际分桶样本量 vs 配置权重期望比例。
     * H0 = 分桶符合预期；p 值过小说明样本比例失衡（作弊污染/分流故障/数据丢失），
     * 此时应先排查再解读业务指标。
     *
     * @param observedCounts 变体 → 实际样本数
     * @param expectedWeights 变体 → 期望权重（正数，无需归一）
     */
    public SrmResult srm(Map<String, Long> observedCounts, Map<String, Integer> expectedWeights) {
        SrmResult r = new SrmResult();
        r.totalSamples = observedCounts.values().stream().mapToLong(Long::longValue).sum();
        double weightSum = 0;
        for (Integer w : expectedWeights.values()) {
            if (w == null || w <= 0) {
                return r;  // 权重非法：返回 p=0(detected=false) 的空结果
            }
            weightSum += w;
        }
        if (r.totalSamples <= 0 || observedCounts.size() < 2 || weightSum <= 0) {
            return r;
        }
        double chi2 = 0;
        for (Map.Entry<String, Long> entry : observedCounts.entrySet()) {
            Integer weight = expectedWeights.getOrDefault(entry.getKey(), 0);
            double expected = r.totalSamples * weight / weightSum;
            if (expected <= 0) {
                continue;  // 配置外变体（旧数据）不参与
            }
            double diff = entry.getValue() - expected;
            chi2 += diff * diff / expected;
        }
        r.chiSquare = chi2;
        r.degreesOfFreedom = observedCounts.size() - 1;
        r.pValue = chiSquareSurvival(chi2, r.degreesOfFreedom);
        r.detected = r.pValue > 0 && r.pValue < SRM_ALPHA;
        return r;
    }

    /** 卡方分布生存函数 P(X > x)，df 为自由度：正则化不完全 gamma Q(df/2, x/2)；
     *  极端偏离时 Q 下溢为 0，clamp 到 1e-300 保证 detected 判定生效 */
    static double chiSquareSurvival(double x, int df) {
        if (x <= 0) {
            return 1.0;
        }
        return Math.max(upperGammaQ(df / 2.0, x / 2.0), 1e-300);
    }

    /** 正则化不完全 gamma 函数 Q(a,x)（Numerical Recipes 连分式/级数实现） */
    private static double upperGammaQ(double a, double x) {
        if (x < a + 1.0) {
            // 级数计算 P(a,x)，再取补
            double ap = a;
            double sum = 1.0 / a;
            double del = sum;
            for (int i = 0; i < 200; i++) {
                ap++;
                del *= x / ap;
                sum += del;
                if (Math.abs(del) < Math.abs(sum) * 1e-14) {
                    break;
                }
            }
            return 1.0 - sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
        }
        // 连分式直接计算 Q(a,x)
        double b = x + 1.0 - a;
        double c = 1e300;
        double d = 1.0 / b;
        double h = d;
        for (int i = 1; i <= 200; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < 1e-300) {
                d = 1e-300;
            }
            c = b + an / c;
            if (Math.abs(c) < 1e-300) {
                c = 1e-300;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-14) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** gamma 函数对数（Lanczos 近似） */
    static double logGamma(double x) {
        double[] g = {676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        }
        x -= 1;
        double a = 0.99999999999980993;
        double t = x + 7.5;
        for (int i = 0; i < g.length; i++) {
            a += g[i] / (x + i + 1);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    private Comparison base(String metric, ArmStat control, ArmStat treatment, String testType) {
        Comparison c = new Comparison();
        c.metric = metric;
        c.control = control.variant;
        c.treatment = treatment.variant;
        c.controlSamples = control.count;
        c.treatmentSamples = treatment.count;
        c.testType = testType;
        return c;
    }

    private void finalize(Comparison c) {
        c.significant = c.pValue > 0 && c.pValue < ALPHA;
    }

    /** 判定指标口径：任一 arm 的 successes>0 且 successes<=count 视为转化率类 */
    private boolean isRateLike(Iterable<ArmStat> arms) {
        for (ArmStat arm : arms) {
            if (arm.successes > 0) {
                return arm.successes <= arm.count;
            }
        }
        return false;
    }

    /** 标准正态双尾 p 值：erf 有理近似（Abramowitz & Stegun 7.1.26，绝对误差 < 1.5e-7） */
    static double twoTailedNormalP(double z) {
        return 2.0 * (1.0 - normalCdf(Math.abs(z)));
    }

    static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double y = t * Math.exp(-x * x - 1.26551223
            + t * (1.00002368
            + t * (0.37409196
            + t * (0.09678418
            + t * (-0.18628806
            + t * (0.27886807
            + t * (-1.13520398
            + t * (1.48851587
            + t * (-0.82215223
            + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - y : y - 1.0;
    }

    /** 汇总快照为每变体 ArmStat（按指标分组） */
    public static Map<String, Map<String, ArmStat>> aggregate(List<ExperimentMetricSnapshotEntity> snapshots) {
        Map<String, Map<String, ArmStat>> byMetric = new HashMap<>();
        for (ExperimentMetricSnapshotEntity s : snapshots) {
            byMetric.computeIfAbsent(s.metricName, k -> new HashMap<>())
                .merge(s.variant, armOf(s), ExperimentStatsService::merge);
        }
        return byMetric;
    }

    private static ArmStat armOf(ExperimentMetricSnapshotEntity s) {
        ArmStat a = new ArmStat();
        a.variant = s.variant;
        a.count = s.count;
        a.sum = s.sum;
        a.sumSquares = s.sumSquares;
        a.successes = s.successes;
        return a;
    }

    private static ArmStat merge(ArmStat a, ArmStat b) {
        a.count += b.count;
        a.sum += b.sum;
        a.sumSquares += b.sumSquares;
        a.successes += b.successes;
        return a;
    }
}
