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
    @DisplayName("权重和溢出 int：取模不变量破坏后兜底返回最后一个变体（确定、不抛异常）")
    void weightOverflowFallsBackToLastVariant() {
        // totalWeight = 2 + Integer.MAX_VALUE = 2^31+1 → int 累加溢出为 -2147483647；
        // 非负哈希对负模数取余结果落在 [0, |total|) 且 ≥ 前缀和 2（正模数下 "h % total < total"
        // 的恒真不变量在负模数下不成立），循环无法落位 → 触达末尾兜底返回最后一个变体。
        // 误配防护语义：病态权重下主体仍被确定性地落位，而非抛异常或返回 null。
        List<ExperimentSplitter.Variant> variants = List.of(
            new ExperimentSplitter.Variant("control", 2),
            new ExperimentSplitter.Variant("treatment", Integer.MAX_VALUE));
        assertEquals("treatment", ExperimentSplitter.assign("exp", "salt", "u1", variants));
        // 同主体结果稳定（兜底路径同样确定性）
        assertEquals("treatment", ExperimentSplitter.assign("exp", "salt", "u1", variants));
    }

    @Test
    @DisplayName("权重和溢出回绕到 0：取模无定义时兜底返回最后一个变体（不抛 ArithmeticException）")
    void weightOverflowToZeroFallsBackToLastVariant() {
        // totalWeight = MAX + MAX + 2 = 2^32 → int 累加回绕恰为 0，取模除零；
        // 按与溢出负总数一致的兜底语义返回最后一个变体（该形态被 validateConfig 拒绝，
        // 仅 DB 直写/历史数据可达，纯函数层保持确定性防御）
        List<ExperimentSplitter.Variant> variants = List.of(
            new ExperimentSplitter.Variant("control", Integer.MAX_VALUE),
            new ExperimentSplitter.Variant("treat-a", Integer.MAX_VALUE),
            new ExperimentSplitter.Variant("treat-b", 2));
        assertEquals("treat-b", ExperimentSplitter.assign("exp", "salt", "u1", variants));
    }

    @Test
    @DisplayName("权重和溢出但回绕后为正：按 int32 折叠语义正常落位（合法可创建形态的跨端锚定向量）")
    void weightOverflowPositiveTotalLandsByInt32Fold() {
        // totalWeight = 3×MAX = 6442450941 → int 回绕 2147483645 > 0，validateConfig 放行（合法可达）；
        // u1 的 u=2654950594，h = u % 2147483645 = 507466949 < 前缀和 MAX → 落 control。
        // 未折叠 int32 的实现（如 JS number 精确求和）会把 h 折进第二变体 —— web SDK 曾在此分裂
        List<ExperimentSplitter.Variant> variants = List.of(
            new ExperimentSplitter.Variant("control", Integer.MAX_VALUE),
            new ExperimentSplitter.Variant("treat-a", Integer.MAX_VALUE),
            new ExperimentSplitter.Variant("treat-b", Integer.MAX_VALUE));
        assertEquals("control", ExperimentSplitter.assign("exp", "salt", "u1", variants));
        // u≥2^31 的主体同样落 control（h = u - 2147483645 < MAX 恒真，回绕前缀和 -2 拦不住）
        assertEquals("control", ExperimentSplitter.assign("exp", "salt", "u8", variants));
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

    @Test
    @DisplayName("variants 非数组 / 缺失 → 空列表；零权重/空参数 → null")
    void parseVariantsNonArrayOrMissing() throws Exception {
        assertTrue(ExperimentSplitter.parseVariants(
            new ObjectMapper().readTree("{\"variants\":\"not-array\"}")).isEmpty());
        assertTrue(ExperimentSplitter.parseVariants(
            new ObjectMapper().readTree("{\"other\":1}")).isEmpty());
        assertTrue(ExperimentSplitter.parseVariants(null).isEmpty());
        // 零权重变体被拒绝（防除零/兜底歧义）
        assertNull(ExperimentSplitter.assign("exp", "salt", "s",
            List.of(new ExperimentSplitter.Variant("only", 0))));
        assertNull(ExperimentSplitter.assign("exp", "salt", "s", List.of()));
    }

    @Test
    @DisplayName("变体解析：非 object 元素/非文本 name/非整数 weight 全部过滤")
    void parseVariantsMalformedElementSides() throws Exception {
        String json = "{\"variants\":[" +
            "\"str-element\", 42, null," +                 // 80 行 !isObject 侧
            "{\"name\":\"n1\",\"weight\":100}," +        // 正常锚定
            "{\"name\":123,\"weight\":100}," +             // 83 行 !isTextual 侧
            "{\"name\":\"n2\"}," +                          // 84 行 weight 缺失侧
            "{\"name\":\"n3\",\"weight\":1.5}," +         // 84 行非整数侧
            "{\"name\":\"n4\",\"weight\":\"50\"}" +      // 84 行字符串权重侧
            "]}";
        List<ExperimentSplitter.Variant> variants =
            ExperimentSplitter.parseVariants(new ObjectMapper().readTree(json));
        assertEquals(1, variants.size());
        assertEquals("n1", variants.get(0).name);
    }

}
