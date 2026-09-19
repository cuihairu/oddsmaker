package io.oddsmaker.control.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP（HMAC-SHA1 / 30s 步长 / 6 位 / ±1 窗口）+ RFC 4648 Base32。
 * 纯 JDK 无新依赖；Google Authenticator 等 TOTP 应用可直接扫 otpauth URL 配对。
 * 包级可见供测试直接验证 RFC 测试向量。
 */
final class TotpUtil {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    /** 允许时钟漂移 ±1 步（RFC 6238 §5.2 建议）。 */
    private static final int CLOCK_WINDOW = 1;
    private static final int SECRET_BYTES = 20;  // RFC 4226 建议 160 位

    private TotpUtil() {
    }

    /** 生成 160 位随机密钥的 Base32 形态（32 字符无填充——otpauth 惯例）。 */
    static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(raw);
        return base32Encode(raw);
    }

    /** 计算指定时刻的 6 位 TOTP 码（供测试生成当前有效码）。 */
    static String currentCode(String base32Secret, Instant now) {
        long counter = now.getEpochSecond() / STEP_SECONDS;
        return hotp(base32Decode(base32Secret), counter);
    }

    /**
     * 校验验证码：格式限 {CODE_DIGITS} 位数字；时间窗 ±CLOCK_WINDOW 步；
     * 逐候选值 MessageDigest.isEqual 常数时间比较，防时序侧信道。
     */
    static boolean verify(String base32Secret, String code, Instant now) {
        if (base32Secret == null || base32Secret.isBlank()
                || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        byte[] key = base32Decode(base32Secret);
        long counter = now.getEpochSecond() / STEP_SECONDS;
        for (long i = counter - CLOCK_WINDOW; i <= counter + CLOCK_WINDOW; i++) {
            if (MessageDigest.isEqual(
                    hotp(key, i).getBytes(StandardCharsets.UTF_8),
                    code.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** RFC 4226 HOTP：HMAC-SHA1 → 动态截断 → 模 10^6 左侧补零。 */
    private static String hotp(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(longToBytes(counter));
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
            return String.format("%0" + CODE_DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 每个 JDK 必有、SecretKeySpec 恒有效——防御兜底，可达性仅存于异常 JVM
            throw new IllegalStateException("TOTP computation failed", e);
        }
    }

    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    /** RFC 4648 Base32 编码（无填充；末尾不足 5 位左对齐补零）。 */
    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /**
     * RFC 4648 Base32 解码：容忍填充 '='、空白与小写输入（authenticator 手动输入场景）；
     * 非法字符抛 IllegalArgumentException。
     */
    static byte[] base32Decode(String encoded) {
        String normalized = encoded == null ? "" : encoded.replace("=", "").replaceAll("\\s+", "").toUpperCase();
        int remainder = normalized.length() % 8;
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            // RFC 4648 §6：合法 Base32 长度 mod 8 不可能是 1/3/6
            throw new IllegalArgumentException("Invalid Base32 length: " + normalized.length());
        }
        int outLen = normalized.length() * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + normalized.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
