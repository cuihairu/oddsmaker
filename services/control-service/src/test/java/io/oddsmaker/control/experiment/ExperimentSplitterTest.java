package io.oddsmaker.control.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实验分流器测试：确定性、权重分布、边界、四端 SDK 跨端一致。
 */
@DisplayName("实验分流器测试")
class ExperimentSplitterTest {

    private static List<ExperimentSplitter.Variant> ab50() {
        return List.of(
            new ExperimentSplitter.Variant("control", 50),
            new ExperimentSplitter.Variant("treatment", 50));
    }

    @Test
    @DisplayName("同一主体重复分流结果稳定（确定性）")
    void assignmentIsDeterministic() {
        for (int i = 0; i < 100; i++) {
            String subject = "user_" + i;
            String first = ExperimentSplitter.assign("exp_test", "exp_salt", subject, ab50());
            for (int repeat = 0; repeat < 5; repeat++) {
                assertEquals(first, ExperimentSplitter.assign("exp_test", "exp_salt", subject, ab50()),
                    "subject " + subject + " 分流必须稳定");
            }
            assertNotNull(first);
        }
    }

    @Test
    @DisplayName("hash32 与四端 SDK 锚定向量一致（sdks/web tests/hash_test.js）")
    void hash32MatchesSdkVectors() {
        assertEquals(0x811c9dc5, ExperimentSplitter.hash32(""));
        assertEquals(0xe40c292c, ExperimentSplitter.hash32("a"));
        assertEquals(0xbf9cf968, ExperimentSplitter.hash32("foobar"));
    }

    @Test
    @DisplayName("分流结果与 Web SDK 实测一致（exp_consistency/salt=s，真值取自 dist 实跑）")
    void assignmentMatchesWebSdk() {
        List<ExperimentSplitter.Variant> variants = List.of(
            new ExperimentSplitter.Variant("A", 50),
            new ExperimentSplitter.Variant("B", 50));
        // web SDK 实跑结果：u1=B, u2=A, u3=A, device123=A, device456=A
        assertEquals("B", ExperimentSplitter.assign("exp_consistency", "s", "u1", variants));
        assertEquals("A", ExperimentSplitter.assign("exp_consistency", "s", "u2", variants));
        assertEquals("A", ExperimentSplitter.assign("exp_consistency", "s", "u3", variants));
        assertEquals("A", ExperimentSplitter.assign("exp_consistency", "s", "device123", variants));
        assertEquals("A", ExperimentSplitter.assign("exp_consistency", "s", "device456", variants));
    }

    @Test
    @DisplayName("不同实验（id 或盐值不同）之间分配独立")
    void differentSaltReassignsIndependently() {
        int diff = 0;
        for (int i = 0; i < 200; i++) {
            String subject = "user_" + i;
            String a = ExperimentSplitter.assign("experiment_a", "salt", subject, ab50());
            String b = ExperimentSplitter.assign("experiment_b", "salt", subject, ab50());
            if (!a.equals(b)) diff++;
        }
        // 两个独立 50/50 实验，约一半主体分配不同；容差防止哈希偏斜误报
        assertTrue(diff > 60 && diff < 140, "实际差异主体数: " + diff);
    }

    @Test
    @DisplayName("50/50 分流在大样本下接近均衡")
    void fiftyFiftyIsBalanced() {
        int control = 0;
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            if ("control".equals(ExperimentSplitter.assign("exp_test", "balance_salt", "u" + i, ab50()))) {
                control++;
            }
        }
        double ratio = (double) control / total;
        assertTrue(ratio > 0.47 && ratio < 0.53, "control 占比: " + ratio);
    }

    @Test
    @DisplayName("非对称权重（80/20）分流比例正确")
    void weightedSplitRespectsRatio() {
        List<ExperimentSplitter.Variant> variants = List.of(
            new ExperimentSplitter.Variant("control", 80),
            new ExperimentSplitter.Variant("treatment", 20));
        int treatment = 0;
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            if ("treatment".equals(ExperimentSplitter.assign("exp_test", "weight_salt", "u" + i, variants))) {
                treatment++;
            }
        }
        double ratio = (double) treatment / total;
        assertTrue(ratio > 0.17 && ratio < 0.23, "treatment 占比: " + ratio);
    }

    @Test
    @DisplayName("无效输入返回 null")
    void invalidInputReturnsNull() {
        assertNull(ExperimentSplitter.assign(null, "salt", "u1", ab50()));
        assertNull(ExperimentSplitter.assign("exp", null, "u1", ab50()));
        assertNull(ExperimentSplitter.assign("exp", "salt", null, ab50()));
        assertNull(ExperimentSplitter.assign("exp", "salt", "u1", List.of()));
        assertNull(ExperimentSplitter.assign("exp", "salt", "u1",
            List.of(new ExperimentSplitter.Variant("only", 0))));
    }

    @Test
    @DisplayName("从配置 JSON 解析变体列表")
    void parsesVariantsFromConfig() throws Exception {
        String json = "{\"variants\":[" +
            "{\"name\":\"control\",\"weight\":50}," +
            "{\"name\":\"treatment\",\"weight\":50}" +
            "]}";
        List<ExperimentSplitter.Variant> variants =
            ExperimentSplitter.parseVariants(new ObjectMapper().readTree(json));
        assertEquals(2, variants.size());
        assertEquals("control", variants.get(0).name);
        assertEquals(50, variants.get(0).weight);
    }

    @Test
    @DisplayName("配置中缺失或非法变体被过滤")
    void parseVariantsFiltersInvalid() throws Exception {
        String json = "{\"variants\":[" +
            "{\"name\":\"ok\",\"weight\":100}," +
            "{\"name\":\"\",\"weight\":100}," +
            "{\"weight\":100}," +
            "{\"name\":\"zero\",\"weight\":0}" +
            "]}";
        List<ExperimentSplitter.Variant> variants =
            ExperimentSplitter.parseVariants(new ObjectMapper().readTree(json));
        assertEquals(1, variants.size());
        assertEquals("ok", variants.get(0).name);
    }
}
