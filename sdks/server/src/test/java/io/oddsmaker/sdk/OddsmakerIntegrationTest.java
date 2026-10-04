package io.oddsmaker.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 集成测试（B3 验收：队列溢出落盘、验签正/负臂）。
 * 以 JDK 内置 HttpServer 模拟网关 /v1/batch：验签口径复制 HmacFilter
 * （t + "." + new String(原始字节, UTF_8) 后 HMAC-SHA256 hex）。
 */
class OddsmakerIntegrationTest {

    /** 网关模拟器：记录请求，可编程响应序列。 */
    static final class GatewaySim implements AutoCloseable {
        static final class Captured {
            String path;
            String apiKey;
            String signature;
            String contentEncoding;
            byte[] raw;
            String decodedBody;
            int eventCount;
        }

        final HttpServer server;
        final List<Captured> captured = java.util.Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<Integer> responses = new ConcurrentLinkedQueue<>();
        /** 网关侧认得的 secret（负臂测试改成与 SDK 不同的值）。 */
        volatile String gatewaySecret = "sk_correct";

        GatewaySim() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/batch", this::handle);
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange ex) throws IOException {
            Captured c = new Captured();
            c.path = ex.getRequestURI().getPath();
            c.apiKey = ex.getRequestHeaders().getFirst("X-api-key");
            c.signature = ex.getRequestHeaders().getFirst("X-signature");
            c.contentEncoding = ex.getRequestHeaders().getFirst("Content-encoding");
            byte[] raw = ex.getRequestBody().readAllBytes();
            c.raw = raw;
            if (c.contentEncoding != null && c.contentEncoding.toLowerCase().contains("gzip")) {
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    c.decodedBody = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                c.decodedBody = new String(raw, StandardCharsets.UTF_8);
            }
            c.eventCount = countEvents(c.decodedBody);
            captured.add(c);
            // 验签口径与 HmacFilter 一致（原始字节按 UTF-8 解码后拼接）；无效签名一律 401
            boolean signatureOk = verifySignature(gatewaySecret, c.signature, raw);
            int status = signatureOk ? nextStatus() : 401;
            byte[] resp = ("{\"accepted\":" + c.eventCount + ",\"rejected\":[],\"sampled_out\":0,"
                + "\"duplicates\":0,\"next_hint_ms\":3000}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        }

        private int nextStatus() {
            Integer next = responses.poll();
            return next == null ? 200 : next;
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    static boolean verifySignature(String secret, String signatureHeader, byte[] rawBody) {
        if (signatureHeader == null) {
            return false;
        }
        String t = null;
        String s = null;
        for (String part : signatureHeader.split(",")) {
            part = part.trim();
            if (part.startsWith("t=")) {
                t = part.substring(2);
            }
            if (part.startsWith("s=")) {
                s = part.substring(2);
            }
        }
        if (t == null || s == null) {
            return false;
        }
        long ts = Long.parseLong(t);
        // 与网关一致：±300s 新鲜窗
        if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
            return false;
        }
        String expected = Signature.hmacSha256Hex(secret, t + "." + new String(rawBody, StandardCharsets.UTF_8));
        return expected.equalsIgnoreCase(s);
    }

    static int countEvents(String body) {
        Matcher m = Pattern.compile("\\{\"event_id\"").matcher(body == null ? "" : body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @TempDir
    Path tempDir;

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        cleanup.clear();
    }

    private Oddsmaker.Config baseConfig(String endpoint, String queueDir) {
        return new Oddsmaker.Config()
            .endpoint(endpoint)
            .apiKey("pk_server_test")
            .secret("sk_correct")
            .gameId("game_demo")
            .environment("prod")
            .queueDir(queueDir)
            .batchSize(3)
            .flushIntervalMs(3_600_000L);   // 测试不靠后台泵，全部显式 flush
    }

    private GatewaySim startSim() throws IOException {
        GatewaySim sim = new GatewaySim();
        cleanup.add(sim);
        return sim;
    }

    @Test
    @DisplayName("全管道：Initialize→SetUser→Track→Flush；gzip+HMAC 正臂 200，字段/合并齐备")
    void fullPipelineInitializeSetUserTrackFlush() throws IOException {
        GatewaySim sim = startSim();
        Oddsmaker sdk = Oddsmaker.initialize(baseConfig(sim.endpoint(),
            tempDir.resolve("q1").toString()));
        cleanup.add(sdk);

        sdk.setUser("player-1", Map.of("tier", "gold", "region", "eu"));
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("amount", 9.99);
            if (i == 2) {
                props.put("tier", "platinum");  // 显式 props 覆盖 setUser traits 同名键
            }
            sdk.track("server.purchase.confirmed", props);
        }
        Oddsmaker.FlushResult result = sdk.flush();

        assertEquals(5, result.accepted);
        assertEquals(2, result.batches);           // batchSize=3 → 3+2
        assertEquals(5, sim.captured.stream().mapToInt(c -> c.eventCount).sum());
        for (GatewaySim.Captured c : sim.captured) {
            assertEquals("/v1/batch", c.path);
            assertEquals("pk_server_test", c.apiKey);
            assertEquals("gzip", String.valueOf(c.contentEncoding).toLowerCase());
            // 正臂：SDK 签名对网关侧 secret 成立（网关模拟器按 HmacFilter 口径用原始字节验签）
            assertTrue(verifySignature("sk_correct", c.signature, c.raw), "签名应为网关接受口径");
            assertTrue(c.signature != null && c.signature.startsWith("t=") && c.signature.contains(", s="));
        }
        // 首批首事件字段断言
        String first = sim.captured.get(0).decodedBody;
        assertTrue(first.contains("\"event_name\":\"server.purchase.confirmed\""));
        assertTrue(first.contains("\"game_id\":\"game_demo\""));
        assertTrue(first.contains("\"environment\":\"prod\""));
        assertTrue(first.contains("\"user_id\":\"player-1\""));
        assertTrue(first.contains("\"device_id\":\"player-1\""));  // device_id 兜底为用户
        assertTrue(first.contains("\"ts_client\":"));
        assertTrue(first.contains("\"tier\":\"gold\"") || first.contains("\"tier\":\"platinum\""));
        assertTrue(first.contains("\"region\":\"eu\"") && first.contains("\"amount\":9.99"));
        assertTrue(first.contains("\"tier\":\"platinum\""));       // 第 2 事件的覆盖值
    }

    @Test
    @DisplayName("验签负臂：伪造签名（secret 不符）→ 网关 401 → 批次丢弃不重试")
    void forgedSignatureRejected401AndDropped() throws IOException {
        GatewaySim sim = startSim();
        sim.gatewaySecret = "sk_rotated_away";   // 网关换了 secret：SDK 的签名全部失效
        Oddsmaker sdk = Oddsmaker.initialize(baseConfig(sim.endpoint(),
            tempDir.resolve("q2").toString()));
        cleanup.add(sdk);

        sdk.track("server.purchase.confirmed", Map.of("amount", 1.0));
        sdk.track("server.session.start", Map.of());
        Oddsmaker.FlushResult result = sdk.flush();

        assertEquals(0, result.accepted);
        assertEquals(2, result.dropped);
        assertEquals(0, result.pending);   // 401 不重试
        assertEquals(1, sim.captured.size());   // batchSize=3 → 两事件同一批、一次 401
    }

    @Test
    @DisplayName("队列溢出落盘：内存上限触发 NDJSON 溢写，重启后同目录续传全量发出")
    void memoryOverflowSpillsToDiskAndRecovers() throws IOException {
        String dead = startDeadEndpoint();
        String queueDir = tempDir.resolve("q3").toString();
        Oddsmaker.Config config = baseConfig(dead, queueDir).maxMemoryEvents(2);
        Oddsmaker sdk = Oddsmaker.initialize(config);
        for (int i = 1; i <= 8; i++) {
            sdk.track("server.match.finished", Map.of("matchId", "m" + i));
        }
        // 内存上限 2：溢写已落盘
        Path dir = Path.of(queueDir);
        assertTrue(Files.isDirectory(dir));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().matches("spill-\\d+\\.ndjson")),
                "memory 溢写应落盘");
        }
        sdk.close();

        // 重启：同 queueDir + 活网关，磁盘存量应全量续传
        GatewaySim sim = startSim();
        Oddsmaker revived = Oddsmaker.initialize(baseConfig(sim.endpoint(), queueDir));
        cleanup.add(revived);
        Oddsmaker.FlushResult result = revived.flush();

        assertEquals(8, result.accepted);
        assertEquals(8, sim.captured.stream().mapToInt(c -> c.eventCount).sum());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".ndjson")).count(),
                "flush 后溢写文件应清空");
        }
    }

    @Test
    @DisplayName("可重试失败臂：5xx 整批退回队列（flush 止步不死循环），恢复后重发成功")
    void retryableServerFailureKeepsEvents() throws IOException {
        GatewaySim sim = startSim();
        sim.responses.add(500);
        Oddsmaker sdk = Oddsmaker.initialize(baseConfig(sim.endpoint(),
            tempDir.resolve("q4").toString()));
        cleanup.add(sdk);

        sdk.track("server.round.settled", Map.of("round", 1));
        sdk.track("server.round.settled", Map.of("round", 2));
        Oddsmaker.FlushResult first = sdk.flush();
        assertEquals(0, first.accepted);
        assertEquals(2, first.pending);

        Oddsmaker.FlushResult second = sdk.flush();   // 网关恢复（默认 200）
        assertEquals(2, second.accepted);
        // 4 = 首次投递（网关收妥后回 500）2 条 + 恢复后重发 2 条；请求 2 次
        assertEquals(4, sim.captured.stream().mapToInt(c -> c.eventCount).sum());
        assertEquals(2, sim.captured.size());
    }

    /** 占住一个端口再关掉，得到必然连接拒绝的 endpoint。 */
    private String startDeadEndpoint() throws IOException {
        var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        int port = socket.getLocalPort();
        socket.close();
        // 端口释放后仍假定无监听（测试进程内无并发抢占者）
        return "http://127.0.0.1:" + port;
    }
}
