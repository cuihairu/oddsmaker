package io.oddsmaker.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 游戏方 API 凭证 AES-GCM 加密托管（HTTP Pull 链路）。
 * 密钥取 {@code oddsmaker.dimension.credential-key}（环境变量 ODDSMAKER_CREDENTIAL_KEY），
 * 经 SHA-256 派生 32 字节 AES 密钥；密文为 base64(iv[12] + ciphertext+tag)。
 * 未显式配置时使用内置 dev 密钥并 WARN——生产必须更换（与 jwt-secret 同口径）。
 */
@Component
public class DimensionCredentialCipher {

    private static final Logger logger = LoggerFactory.getLogger(DimensionCredentialCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String DEV_KEY = "oddsmaker-dev-dimension-credential-key-change-me-0123456789";

    private final SecretKeySpec keySpec;

    public DimensionCredentialCipher(
            @Value("${oddsmaker.dimension.credential-key:${ODDSMAKER_CREDENTIAL_KEY:}}") String configuredKey) {
        String key = configuredKey;
        if (key == null || key.isBlank()) {
            key = DEV_KEY;
            logger.warn("ODDSMAKER_CREDENTIAL_KEY 未配置：HTTP Pull 凭证使用内置 dev 密钥加密托管，生产必须更换");
        }
        this.keySpec = new SecretKeySpec(sha256(key), "AES");
    }

    /** 加密明文凭证；任何失败抛 IllegalArgumentException（上层转 500 并透出原因）。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("credential 不能为空");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalArgumentException("凭证加密失败: " + e.getMessage());
        }
    }

    /** 解密密文凭证；密文损坏/密钥不匹配抛 IllegalArgumentException。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("credential 密文为空");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.trim());
            if (packed.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(packed, IV_LENGTH_BYTES, packed.length - IV_LENGTH_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("凭证解密失败（密文损坏或密钥不匹配）");
        }
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
