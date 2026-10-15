package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式特征清单（契约行 74「引 EventSchema 字段」已代决落地，control-service 侧镜像）：
 * FEATURE 规则 ruleConditions.features[].feature 的合法取值域 = risk-job 计算面产出的特征名
 * （FeatureCalc × RiskJob.buildFeatureBranch 注册）。名单与 jobs/flink/risk-job
 * io.oddsmaker.jobs.risk.RiskFeatures 靠同一份文档名单各自钉住（两侧无共享依赖模块）——
 * 本测试与对侧 RiskFeaturesTest 钉同一 DOCUMENTED 基线，改名单必须两侧同批。
 */
@DisplayName("风控特征清单（control-service 侧镜像）")
class RiskFeaturesTest {

    /** 文档基线名单（B5 首批 6 特征：RiskJob.buildFeatureBranch 注册序），两侧测试同钉这一份 */
    private static final List<String> DOCUMENTED = List.of(
        "gold_gain_1h", "gold_gain_24h", "device_count",
        "account_count_per_ip", "win_rate", "event_count_10m");

    @Test
    @DisplayName("六个特征与文档基线一致（保序），且与对侧 risk-job 清单同构")
    void allFeaturesMatchDocumentedBaseline() {
        assertEquals(DOCUMENTED, RiskFeatures.ALL);
    }

    @Test
    @DisplayName("命名规约：snake_case、唯一、非空")
    void namingConventions() {
        Set<String> seen = new HashSet<>();
        for (String name : RiskFeatures.ALL) {
            assertTrue(!name.isBlank(), "特征名非空");
            assertTrue(name.matches("[a-z][a-z0-9_]*"), "snake_case: " + name);
            assertTrue(seen.add(name), "特征名唯一: " + name);
        }
    }

    @Test
    @DisplayName("常量与名单一致（防只改一侧）")
    void constantsMatchAll() {
        List<String> fromConstants = List.of(RiskFeatures.GOLD_GAIN_1H, RiskFeatures.GOLD_GAIN_24H,
            RiskFeatures.DEVICE_COUNT, RiskFeatures.ACCOUNT_COUNT_PER_IP, RiskFeatures.WIN_RATE,
            RiskFeatures.EVENT_COUNT_10M);
        assertEquals(fromConstants, RiskFeatures.ALL);
    }
}
