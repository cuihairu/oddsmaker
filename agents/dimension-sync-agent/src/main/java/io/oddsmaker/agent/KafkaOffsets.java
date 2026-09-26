package io.oddsmaker.agent;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Kafka 位点（checkpoint.cursor 里的 "k:" 标签）编解码：
 * "k:0=42;1=57" 表示分区 → 下一条待消费 offset（consumer 语义的 next offset）。
 * 只由 KafkaSource 自产自销；CursorCodec 对 k: 前缀不做解释（JDBC 源不会遇到）。
 */
public final class KafkaOffsets {

    static final String PREFIX = "k:";

    private KafkaOffsets() {
    }

    /** 按分区号升序编码为 "k:p=off;p=off"；空表返回 null（表示尚无位点，按 earliest 起）。 */
    public static String encode(SortedMap<Integer, Long> offsets) {
        if (offsets.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(PREFIX);
        boolean first = true;
        for (Map.Entry<Integer, Long> e : offsets.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** 解码；null / 非 k: 前缀 / 格式残缺一律返回空表（下次按 earliest 重新分配）。 */
    public static SortedMap<Integer, Long> decode(String cursor) {
        SortedMap<Integer, Long> out = new TreeMap<>();
        if (cursor == null || !cursor.startsWith(PREFIX)) {
            return out;
        }
        for (String part : cursor.substring(PREFIX.length()).split(";")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return new TreeMap<>();   // 残缺位点不猜：整体回退 earliest
            }
            try {
                out.put(Integer.parseInt(part.substring(0, eq)), Long.parseLong(part.substring(eq + 1)));
            } catch (NumberFormatException e) {
                return new TreeMap<>();
            }
        }
        return out;
    }
}
