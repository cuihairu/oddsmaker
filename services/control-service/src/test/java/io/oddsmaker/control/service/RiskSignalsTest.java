package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式信号清单契约（重构契约 §2.4「2.0 动作」落地，control-service 侧镜像）：
 * 名单与 jobs/flink/risk-job io.oddsmaker.jobs.risk.RiskSignals 靠同一份文档名单各自钉住
 * （两侧无共享依赖模块）——本测试与对侧 RiskSignalsTest 钉同一 DOCUMENTED 基线，
 * 改名单必须两侧同批（代决硬口径：两侧格式级改动必须同批上线语义）。
 */
@DisplayName("风控信号清单（control-service 侧镜像）")
class RiskSignalsTest {

    /** 文档基线名单（重构契约 §2.4 + RiskSignals javadoc），两侧测试同钉这一份 */
    private static final List<String> DOCUMENTED = List.of(
        "gold_gain", "login_ip", "device", "win_flag", "event_frequency",
        "receipt", "ad_reward", "revenue", "trust_level");

    @Test
    @DisplayName("九个信号与文档基线一致（保序），且与对侧 risk-job 清单同构")
    void allSignalsMatchDocumentedBaseline() {
        assertEquals(DOCUMENTED, RiskSignals.ALL);
    }

    @Test
    @DisplayName("命名规约：snake_case、唯一、非空")
    void namingConventions() {
        Set<String> seen = new HashSet<>();
        for (String name : RiskSignals.ALL) {
            assertTrue(!name.isBlank(), "信号名非空");
            assertTrue(name.matches("[a-z][a-z0-9_]*"), "snake_case: " + name);
            assertTrue(seen.add(name), "信号名唯一: " + name);
        }
    }

    @Test
    @DisplayName("常量与名单一致（防只改一侧）")
    void constantsMatchAll() {
        List<String> fromConstants = List.of(RiskSignals.GOLD_GAIN, RiskSignals.LOGIN_IP, RiskSignals.DEVICE,
            RiskSignals.WIN_FLAG, RiskSignals.EVENT_FREQUENCY, RiskSignals.RECEIPT, RiskSignals.AD_REWARD,
            RiskSignals.REVENUE, RiskSignals.TRUST_LEVEL);
        assertEquals(fromConstants, RiskSignals.ALL);
    }
}
