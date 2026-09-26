package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * ml 产物线性打分（纯逻辑，便于单测）。
 * 语义与 ml/ 训练产物 serving 公式严格一致：
 *   score = sigmoid(intercept + Σ coefficient × feature)，特征序 = feature_names。
 * 与 Python 侧同输入必须同结果（见 MlArtifactScorerTest 的对照用例，期望值由
 * Python 计算后写死）。
 */
public final class MlArtifactScorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MlArtifactScorer() {
    }

    public static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * 线性打分。系数与特征长度不一致抛 IllegalArgumentException（调用方回落启发式）。
     */
    public static double score(double[] coefficients, double intercept, double[] features) {
        if (coefficients.length != features.length) {
            throw new IllegalArgumentException(
                    "特征数与系数数不一致: " + features.length + " vs " + coefficients.length);
        }
        double z = intercept;
        for (int i = 0; i < coefficients.length; i++) {
            z += coefficients[i] * features[i];
        }
        return sigmoid(z);
    }

    /** 解析产物 feature_names（JSON 字符串数组）；格式非法抛 IllegalArgumentException。 */
    public static List<String> parseFeatureNames(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray() || node.isEmpty()) {
                throw new IllegalArgumentException("feature_names 需为非空 JSON 数组");
            }
            List<String> names = new ArrayList<>();
            node.forEach(n -> names.add(n.asText()));
            return names;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("feature_names 解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析产物 coefficients（JSON 数值数组）；格式非法抛 IllegalArgumentException。 */
    public static double[] parseCoefficients(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) {
                throw new IllegalArgumentException("coefficients 需为 JSON 数组");
            }
            double[] out = new double[node.size()];
            for (int i = 0; i < node.size(); i++) {
                JsonNode n = node.get(i);
                if (!n.isNumber()) {
                    throw new IllegalArgumentException("coefficients 含非数值元素: " + n);
                }
                out[i] = n.asDouble();
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("coefficients 解析失败: " + e.getMessage(), e);
        }
    }
}
