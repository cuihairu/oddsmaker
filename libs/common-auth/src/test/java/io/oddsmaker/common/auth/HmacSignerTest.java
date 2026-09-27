package io.oddsmaker.common.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HmacSigner#hmacSha256Hex(String, String)} 的权威向量回归。
 *
 * <p><b>RFC 4231 官方测试向量</b>（HMAC-SHA-256）：7 个完整用例里只有 3 个能经 String API 复现——
 * 用例 3/4/6/7 的 key/data 含 0xaa、0xcd 等非 ASCII 字节，而本 API 入参是 String（UTF-8 编码，
 * U+00AA 会变两字节），无法产生与 RFC 相同的原始字节序列，故只用 1/2/5（key/data 均为 ASCII）：
 * <ul>
 *   <li>用例 1：key = 0x0b×20、data = "Hi There"</li>
 *   <li>用例 2：key = "Jefe"、data = "what do ya want for nothing?"</li>
 *   <li>用例 5：key = 0x0c×20、data = "Test With Truncation"——RFC 只给 128 位截断标签，
 *       故断言完整 64 hex 输出的前缀等于该标签（这正是 RFC 声明的事实）</li>
 * </ul>
 * RFC 之外的补充向量（空消息 / UTF-8 多字节 / 超块长 key）不臆造：期望值由两个独立实现
 * 交叉确认一致后才写死（python3 hmac 与 {@code openssl dgst -sha256 -mac HMAC}）。
 *
 * <p>空 key：{@code SecretKeySpec} 对零长度 key 直接抛 {@code IllegalArgumentException}（JDK
 * 行为，实测），被包成 RuntimeException 上抛——钉成契约：签名入口 fail-fast，不静默产出空签名。
 */
class HmacSignerTest {

    @Test
    @DisplayName("RFC 4231 用例 1：key=0x0b×20、data=\"Hi There\"")
    void rfc4231Case1() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                HmacSigner.hmacSha256Hex("\u000b".repeat(20), "Hi There"));
    }

    @Test
    @DisplayName("RFC 4231 用例 2：key=\"Jefe\"、data=\"what do ya want for nothing?\"")
    void rfc4231Case2() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                HmacSigner.hmacSha256Hex("Jefe", "what do ya want for nothing?"));
    }

    @Test
    @DisplayName("RFC 4231 用例 5：key=0x0c×20、data=\"Test With Truncation\"，RFC 的 128 位截断标签为完整输出的前缀")
    void rfc4231Case5TruncatedTagIsPrefix() {
        String full = HmacSigner.hmacSha256Hex("\u000c".repeat(20), "Test With Truncation");
        assertEquals("a3b6167473100ee06e0c796c2955552bfa6f7c0a6a8aef8b93f860aab0cd20c5", full);
        assertTrue(full.startsWith("a3b6167473100ee06e0c796c2955552b"),
                "RFC 4231 用例 5 的截断标签应是完整输出的前缀");
    }

    @Test
    @DisplayName("超块长 key（131 字节 > SHA-256 的 64 字节块）走 key 先哈希路径（TC6 同形、ASCII key）")
    void keyLongerThanBlockSizeIsHashedFirst() {
        // 期望值：python3 hmac 与 openssl dgst -sha256 -mac HMAC 双实现一致后写死
        assertEquals("70e4b005732fa7ff79392aa24fb240159b925dbc9f1398420c0f45e6763dcf14",
                HmacSigner.hmacSha256Hex("k".repeat(131),
                        "Test Using Larger Than Block-Size Key - Hash Key First"));
    }

    @Test
    @DisplayName("空消息：仍产出与空串内容绑定的合法签名（python3/openssl 双实现一致）")
    void emptyMessageIsSigned() {
        assertEquals("5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0",
                HmacSigner.hmacSha256Hex("key", ""));
    }

    @Test
    @DisplayName("UTF-8 多字节：key 与 message 均含中文，按 UTF-8 字节签名（python3/openssl 双实现一致）")
    void utf8MultibyteKeyAndMessage() {
        assertEquals("4256a3fd25b89ecbf92edd06599b8561bc255d9449157399d5b8e586a4b8d26a",
                HmacSigner.hmacSha256Hex("密钥", "剑与魔法"));
    }

    @Test
    @DisplayName("输出形态：64 位小写十六进制；同输入确定性一致、异输入（密钥或消息）签名不同")
    void outputShapeAndSensitivity() {
        String a = HmacSigner.hmacSha256Hex("secret", "message");
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"), "应全为小写十六进制: " + a);
        assertEquals(a, HmacSigner.hmacSha256Hex("secret", "message"));
        assertNotEquals(a, HmacSigner.hmacSha256Hex("secret", "message "));
        assertNotEquals(a, HmacSigner.hmacSha256Hex("secreT", "message"));
    }

    @Test
    @DisplayName("空 key：SecretKeySpec 拒绝零长度密钥，包装为 RuntimeException 上抛（fail-fast 不出空签名）")
    void emptySecretFailsLoud() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> HmacSigner.hmacSha256Hex("", "message"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "cause 应为 SecretKeySpec 的 IllegalArgumentException，实际: " + ex.getCause());
        assertTrue(String.valueOf(ex.getCause().getMessage()).contains("Empty key"),
                String.valueOf(ex.getCause().getMessage()));
    }
}
