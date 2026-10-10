package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP Pull 凭证加密托管测试：AES-GCM 往返、IV 随机性（同明文不同密文）、
 * 篡改/密钥不匹配拒绝、空输入拒绝、显式密钥与 dev 缺省两构造路径。
 */
@DisplayName("HTTP Pull 凭证加密托管")
class DimensionCredentialCipherTest {

    @Test
    @DisplayName("往返：显式密钥加密后解密还原明文")
    void roundTrip() {
        DimensionCredentialCipher cipher = new DimensionCredentialCipher("prod-master-key-2026");
        String secret = "bearer-token-abc123";
        String encrypted = cipher.encrypt(secret);
        assertNotEquals(secret, encrypted);
        assertTrue(encrypted.length() > 24);
        assertEquals(secret, cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("同明文两次加密密文不同（随机 IV）但均可解回")
    void randomIv() {
        DimensionCredentialCipher cipher = new DimensionCredentialCipher("k");
        String a = cipher.encrypt("same-plaintext");
        String b = cipher.encrypt("same-plaintext");
        assertNotEquals(a, b);
        assertEquals("same-plaintext", cipher.decrypt(a));
        assertEquals("same-plaintext", cipher.decrypt(b));
    }

    @Test
    @DisplayName("dev 缺省密钥构造可用（未配置 ODDSMAKER_CREDENTIAL_KEY 时的兜底路径）")
    void devDefaultKey() {
        DimensionCredentialCipher cipher = new DimensionCredentialCipher("");
        String encrypted = cipher.encrypt("dev-token");
        assertEquals("dev-token", cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("密钥不匹配解密拒绝（换密钥后密文不可读）")
    void wrongKeyRejected() {
        DimensionCredentialCipher cipherA = new DimensionCredentialCipher("key-a");
        DimensionCredentialCipher cipherB = new DimensionCredentialCipher("key-b");
        String encrypted = cipherA.encrypt("secret");
        assertThrows(IllegalArgumentException.class, () -> cipherB.decrypt(encrypted));
    }

    @Test
    @DisplayName("密文篡改拒绝（GCM 完整性校验）")
    void tamperedRejected() {
        DimensionCredentialCipher cipher = new DimensionCredentialCipher("k");
        String encrypted = cipher.encrypt("secret");
        byte[] raw = java.util.Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("空输入/坏密文拒绝，统一抛 IllegalArgumentException（不泄漏 crypto 类型）")
    void invalidInputs() {
        DimensionCredentialCipher cipher = new DimensionCredentialCipher("k");
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(" "));
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(null));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(null));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("not-base64!!"));
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("AAAA"));
        IllegalArgumentException notBase64 = assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("not-base64!!"));
        assertEquals("凭证解密失败（密文损坏或密钥不匹配）", notBase64.getMessage());
    }
}
