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
}
