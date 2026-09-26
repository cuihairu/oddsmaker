package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka 消息 → 维度变更：消息体为 JSON 对象，字段语义与 JDBC/CSV 列一致
 * （控制列 dim_type / resource_id / op / version_ts 大小写不敏感，其余进 attributes）。
 * 标量字段转文本；嵌套对象/数组序列化为紧凑 JSON 文本进 attributes（不丢信息）。
 */
public final class KafkaRecordMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KafkaRecordMapper() {
    }

    /** null / 空消息体 / 非对象根 → IOException（调用方记日志跳过该条，offset 照常前进）。 */
    static DimensionChange map(byte[] value, String defaultDimType, long now) throws IOException {
        if (value == null || value.length == 0) {
            throw new IOException("空消息体");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(value);
        } catch (IOException e) {
            throw new IOException("JSON 解析失败: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IOException("消息根节点须为 JSON 对象");
        }
        Map<String, String> row = new LinkedHashMap<>();
        root.properties().forEach(field -> row.put(field.getKey(), textOf(field.getValue())));
        return JdbcSource.mapRow(row, defaultDimType, now);
    }

    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        try {
            return MAPPER.writeValueAsString(node);   // 嵌套对象/数组保留为 JSON 文本
        } catch (IOException e) {
            return node.toString();
        }
    }
}
