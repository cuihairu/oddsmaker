package io.oddsmaker.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 请求签名（HMAC-SHA256，hex）。与 Gateway HmacFilter 口径一致：
 * 签名串 = "t=&lt;epoch秒&gt;, s=&lt;hex&gt;"，被签消息 = t + "." + 请求体字符串。
 * 请求体是 gzip 压缩后的原始字节按 UTF-8 解码的字符串（JVM 解码语义确定性保证
 * 双方算得一致）；见 HttpSink。
 */
final class Signature {

    private Signature() {}

    static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
