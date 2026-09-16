package io.oddsmaker.control.experiment;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验分流器：确定性哈希分桶 + 变体权重分配。
 *
 * 算法与四个客户端 SDK（sdks/web、android、ios、unity）完全一致：
 * hash32(experimentId + ":" + salt + ":" + subjectId)（FNV-1a 32 变体）
 * → 无符号取模总权重 → 按变体累积权重落位。
 * 同一 (experimentId, salt, subjectId) 永远落在同一变体，保证曝光/转化口径稳定；
 * 跨端锚定向量见 ExperimentSplitterTest（与 sdks/web tests/hash_test.js 相同）。
 */
public final class ExperimentSplitter {

    /** 变体分配：name + weight（正整数） */
    public static final class Variant {
        public final String name;
        public final int weight;

        public Variant(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }
    }

    private ExperimentSplitter() {}

    /**
     * 为主体（userId/deviceId）分配变体。
     *
     * @param experimentId 实验 id（参与哈希，保证不同实验之间分配独立）
     * @param salt 实验盐值
     * @param subjectId 主体标识
     * @param variants 变体列表（weight 之和需大于 0）
     * @return 命中的变体名；无有效变体时返回 null
     */
    public static String assign(String experimentId, String salt, String subjectId, List<Variant> variants) {
        if (experimentId == null || salt == null || subjectId == null || variants == null || variants.isEmpty()) {
            return null;
        }
        int totalWeight = 0;
        for (Variant v : variants) {
            if (v.weight <= 0) {
                return null;
            }
            totalWeight += v.weight;
        }

        long h = Integer.toUnsignedLong(hash32(experimentId + ":" + salt + ":" + subjectId)) % totalWeight;
        int cumulative = 0;
        for (Variant v : variants) {
            cumulative += v.weight;
            if (h < cumulative) {
                return v.name;
            }
        }
        // 取模兜底：落在最后一个变体
        return variants.get(variants.size() - 1).name;
    }

    /** 从实验配置 JSON 解析变体列表 */
    public static List<Variant> parseVariants(JsonNode config) {
        List<Variant> out = new ArrayList<>();
        if (config == null || !config.has("variants")) {
            return out;
        }
        JsonNode variants = config.get("variants");
        if (!variants.isArray()) {
            return out;
        }
        for (JsonNode variant : variants) {
            if (variant == null || !variant.isObject()) continue;
            JsonNode name = variant.get("name");
            JsonNode weight = variant.get("weight");
            if (name == null || !name.isTextual() || name.asText().isBlank()) continue;
            int w = weight != null && weight.isIntegralNumber() ? weight.asInt() : 0;
            if (w <= 0) continue;
            out.add(new Variant(name.asText().trim(), w));
        }
        return out;
    }

    /**
     * 四端 SDK 同款哈希（sdks/web src/index.ts hash32 等）：UTF-16 码元逐位混合，
     * 溢出按 32 位回绕（各端同余）。锚定向量：hash32("a")=0xe40c292c、hash32("foobar")=0xbf9cf968。
     */
    static int hash32(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
        }
        return h;
    }
}
