package io.oddsmaker.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 发送器：Batch→Gzip→HMAC→POST /v1/batch。
 *
 * <p>管道顺序为压缩后签名（与网关 HmacFilter 口径一致：HMAC 覆盖压缩后的原始请求体，
 * 网关侧对原始字节按 UTF-8 解码后计算，JVM 解码语义确定性保证双方一致）。
 * 头部：{@code x-api-key}、{@code x-signature: t=<秒>, s=<hex>}、
 * {@code Content-Type: application/json}、{@code Content-Encoding: gzip}。
 */
final class HttpSink {

    private final HttpClient client;
    private final String batchUrl;
    private final String apiKey;
    private final String secret;
    private final int connectTimeoutMs;
    private final int requestTimeoutMs;

    HttpSink(Oddsmaker.Config config) {
        String base = config.endpoint == null ? "" : config.endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.batchUrl = base + "/v1/batch";
        this.apiKey = config.apiKey;
        this.secret = config.secret;
        this.connectTimeoutMs = config.connectTimeoutMs;
        this.requestTimeoutMs = config.requestTimeoutMs;
        this.client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(connectTimeoutMs))
            .build();
    }

    /** 发送一批事件（每条已是 JSON 对象字符串）。返回 HTTP 状态码；网络异常抛 IOException。 */
    int send(List<String> eventJsons) throws IOException, InterruptedException {
        String body = batchBody(eventJsons);
        byte[] gzipped = gzip(body.getBytes(StandardCharsets.UTF_8));
        long t = Instant.now().getEpochSecond();
        // 与 HmacFilter 相同口径：t + "." + new String(原始字节, UTF_8)
        String message = t + "." + new String(gzipped, StandardCharsets.UTF_8);
        String signature = "t=" + t + ", s=" + Signature.hmacSha256Hex(secret, message);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(batchUrl))
            .timeout(java.time.Duration.ofMillis(requestTimeoutMs))
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-api-key", apiKey)
            .header("x-signature", signature)
            .header("User-Agent", "oddsmaker-server-sdk/0.1.0 (java)")
            .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** 批体：JSON 数组（元素为已序列化的事件对象）。 */
    static String batchBody(List<String> eventJsons) {
        StringBuilder sb = new StringBuilder(64 + eventJsons.size() * 96);
        sb.append('[');
        for (int i = 0; i < eventJsons.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(eventJsons.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(input);
        }
        return out.toByteArray();
    }
}
