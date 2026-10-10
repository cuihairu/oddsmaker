package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pull 配置 YAML（de)serialization 纯函数矩阵：导出形状（凭证永不落盘/注释头）、
 * 解析契约（dimensions 根键/映射键即 sourceKey/未知字段拒绝/类型不符拒绝/缺必填拒绝）。
 * 字段级业务校验（endpoint 形态/区间/环境存在性）由 create/update 承担，不在本层。
 */
@DisplayName("Pull 配置 YAML 形态")
class DimensionPullYamlTest {

    private static DimensionPullConfigEntity entity(String sourceKey, String env) {
        DimensionPullConfigEntity e = new DimensionPullConfigEntity();
        e.id = "dpc_" + sourceKey;
        e.gameId = "game_x";
        e.environment = env;
        e.sourceKey = sourceKey;
        e.dimType = "item";
        e.endpoint = "https://api.game-x.com/v1/" + sourceKey;
        e.credentialEncrypted = "enc-blob-should-never-export";
        e.enabled = true;
        e.intervalSeconds = 300;
        e.pageLimit = 1000;
        return e;
    }

    // ===== toYaml =====

    @Test
    @DisplayName("导出：dimensions 根键 + 映射键即 sourceKey，字段 1:1，凭证密文永不落盘")
    void exportShape() {
        String yaml = DimensionPullYaml.toYaml(List.of(entity("items", "prod"), entity("levels", "prod")));
        assertTrue(yaml.startsWith(DimensionPullYaml.EXPORT_HEADER));
        assertTrue(yaml.contains("dimensions:"));
        assertTrue(yaml.contains("  items:"));
        assertTrue(yaml.contains("  levels:"));
        assertTrue(yaml.contains("    environment: prod"));
        assertTrue(yaml.contains("    dimType: item"));
        assertTrue(yaml.contains("    endpoint: https://api.game-x.com/v1/items"));
        assertTrue(yaml.contains("    intervalSeconds: 300"));
        assertTrue(yaml.contains("    pageLimit: 1000"));
        assertTrue(yaml.contains("    enabled: true"));
        assertFalse(yaml.contains("enc-blob-should-never-export"), "密文严禁出现在导出物中");
        assertFalse(yaml.contains("credential:"), "导出物不含 credential 字段（注释 prose 提及不算）");
    }

    @Test
    @DisplayName("导出→解析往返：非凭证字段全部保真")
    void roundTrip() {
        DimensionPullConfigEntity in = entity("skills", "staging");
        in.dimType = "level";
        in.enabled = false;
        in.intervalSeconds = 60;
        in.pageLimit = 50;

        List<DimensionPullService.PullConfigUpsert> parsed =
                DimensionPullYaml.parse(DimensionPullYaml.toYaml(List.of(in)));

        assertEquals(1, parsed.size());
        DimensionPullService.PullConfigUpsert up = parsed.get(0);
        assertEquals("skills", up.sourceKey);
        assertEquals("staging", up.environment);
        assertEquals("level", up.dimType);
        assertEquals("https://api.game-x.com/v1/skills", up.endpoint);
        assertEquals(60, up.intervalSeconds);
        assertEquals(50, up.pageLimit);
        assertEquals(Boolean.FALSE, up.enabled);
        assertNull(up.credential, "导出物不含凭证，解析后 credential 为缺省（import 语义=保留既有）");
    }

    // ===== parse =====

    @Test
    @DisplayName("解析：credential 首建形态完整解析")
    void parseWithCredential() {
        String yaml = """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/items
                    credential: bearer-token-xyz
                """;
        List<DimensionPullService.PullConfigUpsert> parsed = DimensionPullYaml.parse(yaml);
        assertEquals(1, parsed.size());
        assertEquals("items", parsed.get(0).sourceKey);
        assertEquals("prod", parsed.get(0).environment);
        assertEquals("bearer-token-xyz", parsed.get(0).credential);
        assertNull(parsed.get(0).dimType, "dimType 缺省由 create 落 item");
        assertNull(parsed.get(0).intervalSeconds);
        assertNull(parsed.get(0).pageLimit);
        assertNull(parsed.get(0).enabled);
    }

    @Test
    @DisplayName("解析：未知字段精确报错（防 endpont 类手误静默失效）")
    void unknownFieldRejected() {
        String yaml = """
                dimensions:
                  items:
                    environment: prod
                    endpont: https://api.game-x.com/v1/items
                """;
        IllegalArgumentException e =
                assertIllegaYaml(yaml);
        assertTrue(e.getMessage().contains("items"), e.getMessage());
        assertTrue(e.getMessage().contains("endpont"), e.getMessage());
    }

    @Test
    @DisplayName("解析：缺 dimensions 根键 / 空文档 / 标量根均拒绝")
    void badRootRejected() {
        assertIllegaYaml("not-a-map");
        assertIllegaYaml("providers:\n  items: {}");
        assertIllegaYaml("");
    }

    @Test
    @DisplayName("解析：条目缺 environment / endpoint 拒绝")
    void missingRequiredRejected() {
        assertIllegaYaml("dimensions:\n  items:\n    endpoint: https://a.com/x");
        assertIllegaYaml("dimensions:\n  items:\n    environment: prod");
    }

    @Test
    @DisplayName("解析：intervalSeconds/enabled 类型不符（文本标量）拒绝")
    void wrongTypeRejected() {
        assertIllegaYaml("""
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://a.com/x
                    intervalSeconds: "300"
                """);
        assertIllegaYaml("""
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://a.com/x
                    enabled: yes-please
                """);
    }

    @Test
    @DisplayName("解析：条目非映射（标量/列表）与空 sourceKey 键拒绝")
    void badEntryShapeRejected() {
        assertIllegaYaml("dimensions:\n  items: prod");
        assertIllegaYaml("dimensions:\n  \"\": {}");
    }

    @Test
    @DisplayName("解析：空 dimensions 映射得空列表（import 层拒绝空导入）")
    void emptyDimensions() {
        assertTrue(DimensionPullYaml.parse("dimensions: {}").isEmpty());
    }

    private static IllegalArgumentException assertIllegaYaml(String yaml) {
        try {
            DimensionPullYaml.parse(yaml);
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("应拒绝的 YAML 未报错: " + yaml);
    }
}
