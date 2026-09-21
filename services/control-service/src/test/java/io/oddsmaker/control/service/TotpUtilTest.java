package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * TotpUtil 对 RFC 官方向量的验证：
 * - Base32：RFC 4648 §10 测试向量（编码无填充，解码容忍填充/空白/小写）
 * - TOTP：RFC 6238 附录 B SHA-1 向量取末 6 位（密钥 = ASCII "12345678901234567890"）
 */
@DisplayName("TOTP 工具：RFC 4648/6238 官方向量 + 窗口语义 + 防御分支")
class TotpUtilTest {

    private static final String RFC6238_SECRET =
        TotpUtil.base32Encode("12345678901234567890".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("Base32 编码命中 RFC 4648 §10 全部向量（无填充形态）")
    void base32EncodeMatchesRfc4648Vectors() {
        assertEquals("", TotpUtil.base32Encode(new byte[0]));
        assertEquals("MY", TotpUtil.base32Encode("f".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXQ", TotpUtil.base32Encode("fo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6", TotpUtil.base32Encode("foo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YQ", TotpUtil.base32Encode("foob".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YTB", TotpUtil.base32Encode("fooba".getBytes(StandardCharsets.UTF_8)));
        assertEquals("MZXW6YTBOI", TotpUtil.base32Encode("foobar".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Base32 解码：容忍填充/空白/小写，命中 RFC 向量")
    void base32DecodeTolerantForms() {
        assertArrayEquals("f".getBytes(StandardCharsets.UTF_8), TotpUtil.base32Decode("MY======"));
        assertArrayEquals("foobar".getBytes(StandardCharsets.UTF_8), TotpUtil.base32Decode("MZXW6YTBOI===="));
        assertArrayEquals("foobar".getBytes(StandardCharsets.UTF_8), TotpUtil.base32Decode("mzxw6 ytboi"));
        assertArrayEquals(new byte[0], TotpUtil.base32Decode(""));
    }

    @Test
    @DisplayName("Base32 解码：非法字符与非法长度（mod 8 ∈ {1,3,6}）抛 IAE")
    void base32DecodeRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> TotpUtil.base32Decode("MZ1W6"));
        assertThrows(IllegalArgumentException.class, () -> TotpUtil.base32Decode("A"));
        assertThrows(IllegalArgumentException.class, () -> TotpUtil.base32Decode("ABC"));
        assertThrows(IllegalArgumentException.class, () -> TotpUtil.base32Decode("ABCDEF"));
    }

    @Test
    @DisplayName("随机密钥：32 字符合法 Base32，解码恰为 160 位")
    void generateSecretProducesValidKey() {
        String secret = TotpUtil.generateSecret();
        assertTrue(secret.matches("[A-Z2-7]{32}"), "应为 32 字符标准 Base32: " + secret);
        assertEquals(20, TotpUtil.base32Decode(secret).length);
    }

    @Test
    @DisplayName("TOTP 命中 RFC 6238 附录 B 全部 SHA-1 向量（末 6 位）")
    void currentCodeMatchesRfc6238Vectors() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", RFC6238_SECRET);
        assertEquals("287082", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(59)));
        assertEquals("081804", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(1111111109L)));
        assertEquals("050471", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(1111111111L)));
        assertEquals("005924", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(1234567890L)));
        assertEquals("279037", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(2000000000L)));
        assertEquals("353130", TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(20000000000L)));
    }

    @Test
    @DisplayName("verify：当前与 ±1 窗口（±30s）通过，±2 窗口拒绝")
    void verifyHonorsClockWindow() {
        Instant now = Instant.ofEpochSecond(100_000_000);
        String current = TotpUtil.currentCode(RFC6238_SECRET, now);
        String prev = TotpUtil.currentCode(RFC6238_SECRET, now.minusSeconds(30));
        String next = TotpUtil.currentCode(RFC6238_SECRET, now.plusSeconds(30));
        String farPast = TotpUtil.currentCode(RFC6238_SECRET, now.minusSeconds(60));
        String farFuture = TotpUtil.currentCode(RFC6238_SECRET, now.plusSeconds(60));

        assertTrue(TotpUtil.verify(RFC6238_SECRET, current, now));
        assertTrue(TotpUtil.verify(RFC6238_SECRET, prev, now), "上一步码应在容差窗内");
        assertTrue(TotpUtil.verify(RFC6238_SECRET, next, now), "下一步码应在容差窗内");
        assertFalse(TotpUtil.verify(RFC6238_SECRET, farPast, now), "±2 窗口外必须拒绝");
        assertFalse(TotpUtil.verify(RFC6238_SECRET, farFuture, now), "±2 窗口外必须拒绝");
    }

    @Test
    @DisplayName("verify：错误码/格式错/密钥空均拒绝")
    void verifyRejectsBadInputs() {
        String code = TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(59));
        assertFalse(TotpUtil.verify(RFC6238_SECRET, "000000", Instant.ofEpochSecond(59)));
        assertFalse(TotpUtil.verify(RFC6238_SECRET, "28708", Instant.ofEpochSecond(59)), "5 位拒绝");
        assertFalse(TotpUtil.verify(RFC6238_SECRET, "28708a", Instant.ofEpochSecond(59)), "含字母拒绝");
        assertFalse(TotpUtil.verify(RFC6238_SECRET, null, Instant.ofEpochSecond(59)));
        assertFalse(TotpUtil.verify(null, code, Instant.ofEpochSecond(59)));
        assertFalse(TotpUtil.verify("  ", code, Instant.ofEpochSecond(59)));
    }

    @Test
    @DisplayName("HMAC 异常防御兜底：Mac.getInstance 抛出时转 IllegalStateException")
    void hotpWrapsGeneralSecurityException() {
        try (var mockedMac = mockStatic(Mac.class)) {
            mockedMac.when(() -> Mac.getInstance("HmacSHA1"))
                .thenThrow(new java.security.NoSuchAlgorithmException("boom"));
            assertThrows(IllegalStateException.class,
                () -> TotpUtil.currentCode(RFC6238_SECRET, Instant.ofEpochSecond(59)));
        }
    }

    @Test
    void base32Decode_nullInputYieldsEmptyArray() {
        assertArrayEquals(new byte[0], TotpUtil.base32Decode(null));
    }

}
