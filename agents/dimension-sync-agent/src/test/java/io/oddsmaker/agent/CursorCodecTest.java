package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水位编解码：类型标签 n:/t:/s: 保证恢复时绑定正确的 JDBC 类型。 */
class CursorCodecTest {

    @Test
    @DisplayName("encodeValue：整型→n:，时间型→t:，字符串→s:")
    void encodeValueByJavaType() {
        assertEquals("n:123", CursorCodec.encodeValue(123L));
        assertEquals("n:42", CursorCodec.encodeValue(42));
        assertEquals("n:7", CursorCodec.encodeValue((byte) 7));
        assertEquals("n:9000000000000000001", CursorCodec.encodeValue(new java.math.BigInteger("9000000000000000001")));
        Instant t = Instant.parse("2026-01-02T03:04:05Z");
        assertEquals("t:2026-01-02T03:04:05Z", CursorCodec.encodeValue(Timestamp.from(t)));
        assertEquals("t:2026-01-02T03:04:05Z", CursorCodec.encodeValue(t));
        String ldtEncoded = CursorCodec.encodeValue(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        assertTrue(ldtEncoded.startsWith("t:"));
        assertEquals("s:abc", CursorCodec.encodeValue("abc"));
        // 数值形态的字符串保留 s: 标签——类型跟 Java 类型走，不猜内容
        assertEquals("s:123", CursorCodec.encodeValue("123"));
    }

    @Test
    @DisplayName("encodeConfig：整数→n:，ISO-8601→t:，其余原样 s:；空返回 null")
    void encodeConfigHeuristics() {
        assertEquals("n:123", CursorCodec.encodeConfig("123"));
        assertEquals("t:2026-01-02T03:04:05Z", CursorCodec.encodeConfig("2026-01-02T03:04:05Z"));
        // mysql 风格文本时间不是 ISO，按原样交给驱动绑定
        assertEquals("s:2026-01-02 03:04:05", CursorCodec.encodeConfig("2026-01-02 03:04:05"));
        assertNull(CursorCodec.encodeConfig(null));
        assertNull(CursorCodec.encodeConfig("  "));
    }

    @Test
    @DisplayName("decode：还原为 Long/Timestamp/String；无标签历史值启发式还原")
    void decodeRoundTrip() {
        assertEquals(123L, CursorCodec.decode("n:123"));
        assertEquals(Timestamp.from(Instant.parse("2026-01-02T03:04:05Z")),
                CursorCodec.decode("t:2026-01-02T03:04:05Z"));
        assertEquals("a,b", CursorCodec.decode("s:a,b"));
        assertEquals("", CursorCodec.decode("s:"));
        assertNull(CursorCodec.decode(null));
        // 无标签历史 checkpoint
        assertEquals(999L, CursorCodec.decode("999"));
        assertEquals("plain", CursorCodec.decode("plain"));
    }

    @Test
    @DisplayName("encode→decode 往返一致")
    void roundTrip() {
        Object[] values = {123L, Timestamp.from(Instant.now()), "x:y,z"};
        for (Object v : values) {
            assertEquals(v, CursorCodec.decode(CursorCodec.encodeValue(v)),
                    "round-trip 失败: " + v.getClass());
        }
        // 非整型 Integer 也走 n: 标签，解码统一为 Long（PreparedStatement 绑定等价）
        assertEquals(42L, CursorCodec.decode(CursorCodec.encodeValue(42)));
        // LocalDateTime 经系统时区转 Instant 后往返为 Timestamp（语义等价）
        LocalDateTime ldt = LocalDateTime.of(2026, 6, 1, 12, 0);
        Timestamp restored = (Timestamp) CursorCodec.decode(CursorCodec.encodeValue(ldt));
        assertEquals(ldt.atZone(ZoneId.systemDefault()).toInstant(), restored.toInstant());
    }

    @Test
    @DisplayName("encodeValue：Short/Byte/BigInteger 走 n: 标签")
    void encodeValueCoversAllIntegerTypes() {
        assertEquals("n:32767", CursorCodec.encodeValue((short) 32767));
        assertEquals("n:127", CursorCodec.encodeValue((byte) 127));
        assertEquals("n:9999999999999999999", CursorCodec.encodeValue(new java.math.BigInteger("9999999999999999999")));
    }

    @Test
    @DisplayName("encodeConfig：非标准 ISO 时间格式回退 s:（DateTimeParseException 分支）")
    void encodeConfigFallsBackToStringOnNonIso() {
        // mysql datetime 格式无 T 分隔符
        assertEquals("s:2026-01-02 03:04:05", CursorCodec.encodeConfig("2026-01-02 03:04:05"));
        // 非法日期
        assertEquals("s:not-a-date", CursorCodec.encodeConfig("not-a-date"));
        // 带毫秒但非 ISO
        assertEquals("s:2026/01/02 03:04:05.123", CursorCodec.encodeConfig("2026/01/02 03:04:05.123"));
    }

    @Test
    @DisplayName("encodeConfig：纯数字字符串走 n:（NumberFormatException 不抛分支）")
    void encodeConfigParsesNumericString() {
        assertEquals("n:0", CursorCodec.encodeConfig("0"));
        assertEquals("n:-42", CursorCodec.encodeConfig("-42"));
        assertEquals("n:9223372036854775807", CursorCodec.encodeConfig("9223372036854775807")); // Long.MAX_VALUE
    }

    @Test
    @DisplayName("decode：空标签值 s: 返回空串")
    void decodeEmptySValue() {
        assertEquals("", CursorCodec.decode("s:"));
    }

    @Test
    @DisplayName("decode：无标签纯数字字符串启发式还原为 Long")
    void decodeUnlabeledNumeric() {
        assertEquals(42L, CursorCodec.decode("42"));
        assertEquals(0L, CursorCodec.decode("0"));
        assertEquals(-1L, CursorCodec.decode("-1"));
    }

    @Test
    @DisplayName("decode：无标签非数字字符串原样返回")
    void decodeUnlabeledNonNumeric() {
        assertEquals("abc", CursorCodec.decode("abc"));
        assertEquals("2026-01-01", CursorCodec.decode("2026-01-01"));
    }
}
