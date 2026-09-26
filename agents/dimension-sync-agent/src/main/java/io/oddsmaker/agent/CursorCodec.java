package io.oddsmaker.agent;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;

/**
 * 增量水位编解码：checkpoint 里以类型标签字符串存储（"n:"/"t:"/"s:"），
 * 恢复时还原为对应 Java 类型再绑 PreparedStatement（PG 的 timestamp 列拒绝 bigint 字面量，必须区分）。
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    /** 按 JDBC 取回的 Java 类型编码（整型→n:，时间型→t:，其余→s:）。 */
    public static String encodeValue(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short
                || v instanceof Byte || v instanceof BigInteger) {
            return "n:" + v;
        }
        if (v instanceof Timestamp ts) {
            return "t:" + ts.toInstant();
        }
        if (v instanceof LocalDateTime ldt) {
            return "t:" + ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (v instanceof Instant in) {
            return "t:" + in;
        }
        return "s:" + v;
    }

    /** 配置里的起始水位（字符串）启发式判型：整数→n:，ISO-8601→t:，其余原样 s:。 */
    public static String encodeConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return "n:" + Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            // 不是纯整数，继续试时间
        }
        try {
            return "t:" + Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 非标准 ISO（如 mysql 文本 "2026-01-01 00:00:00"），按原样字符串交给驱动绑定
        }
        return "s:" + s;
    }

    /** 还原为可绑定 PreparedStatement 的值：Long / Timestamp / String。 */
    public static Object decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.startsWith("n:")) {
            return Long.parseLong(cursor.substring(2));
        }
        if (cursor.startsWith("t:")) {
            return Timestamp.from(Instant.parse(cursor.substring(2)));
        }
        if (cursor.startsWith("s:")) {
            return cursor.substring(2);
        }
        // 无标签的历史 checkpoint：启发式还原
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException ignored) {
            return cursor;
        }
    }
}
