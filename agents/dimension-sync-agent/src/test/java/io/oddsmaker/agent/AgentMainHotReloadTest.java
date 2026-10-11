package io.oddsmaker.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control 托管配置热更新（tickRefresh）：门槛/变更检测/失败兜底/换件语义——
 * - 门槛：refresh 关（键缺省或 config-url 缺/测试注入路径）或未到 nextRefreshAt 不发请求
 * - 变更：下发原文与上次应用一致=零动作；有变=load+validate 通过后换 source/sink/status
 *   （全部新件构造成功才释放旧件；checkpoint 不动——断点跨换源续传）
 * - 失败：非 2xx / 新配置校验不过 → 沿用当前配置，旧件不释放
 *
 * <p>Control 用内嵌 HttpServer 走真实 HTTP（沿 AgentMainTest idiom）；时钟注入 AtomicLong
 * 确定性驱动刷新门槛，无真实 sleep。run() 循环体本身仍属进程级行为（见 AgentMainTest 类注）。
 */
class AgentMainHotReloadTest {

    HttpServer server;
    final AtomicLong clockMs = new AtomicLong(1_000_000);
    volatile String remoteText = "";
    volatile int remoteStatus = 200;
    final AtomicInteger fetchCount = new AtomicInteger();

    @TempDir
    Path dir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            fetchCount.incrementAndGet();
            byte[] resp = remoteText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(remoteStatus, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 记账 stub 源：close 计数验证换件释放旧源。 */
    static final class CloseTrackingSource implements DimensionSource {
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public String type() {
            return "stub";
        }

        @Override
        public PollResult poll(Checkpoint current) {
            return PollResult.of(List.of(), current.copy());
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private Path writeProps(String csvDir) throws Exception {
        Path p = dir.resolve("agent.properties");
        Files.write(p, String.join("\n",
                "control.config-url=http://127.0.0.1:" + server.getAddress().getPort() + "/resolved",
                "control.config-refresh-seconds=60",
                "gateway.endpoint=http://127.0.0.1:1",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "status.source-key=agent-csv",
                "source.type=csv",
                "source.csv.dir=" + csvDir,
                "agent.poll-seconds=300").getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private String remoteOf(String csvDir, int pollSeconds) {
        return "gateway.endpoint=http://127.0.0.1:1\ngateway.api-key=k1\ngame.id=g1\ngame.environment=prod\n"
                + "source.type=csv\nsource.csv.dir=" + csvDir + "\nagent.poll-seconds=" + pollSeconds + "\n";
    }

    /** 启动态：本地文件 + Control 下发 V1 合成初始配置（镜像 main() 装配，appliedRemoteText=V1 基线）。 */
    private AgentMain boot(CloseTrackingSource initial) throws Exception {
        Path props = writeProps(dir.resolve("v1").toString());
        remoteText = remoteOf(dir.resolve("v1").toString(), 300);
        ControlConfigFetcher fetcher = new ControlConfigFetcher();
        AgentConfig cfg = AgentConfig.load(new String[]{"--config=" + props}, fetcher);
        cfg.validate();
        return new AgentMain(cfg, initial, new GatewaySink(cfg), new StatusReporter(cfg),
                new CheckpointStore(Path.of(cfg.checkpointPath)),
                new String[]{"--config=" + props}, fetcher, clockMs::get);
    }

    @Test
    @DisplayName("门槛：未到刷新间隔不发请求；到点后文本未变=零动作")
    void gateBlocksUntilIntervalAndUnchangedTextIsNoop() throws Exception {
        AgentMain agent = boot(new CloseTrackingSource());
        assertEquals(1, fetchCount.get());   // 仅启动拉取

        assertFalse(agent.tickRefresh());    // 时钟未进：门槛拦截
        assertEquals(1, fetchCount.get());

        clockMs.addAndGet(60_000);           // 到点：重拉但原文一致 → 零动作
        assertFalse(agent.tickRefresh());
        assertEquals(2, fetchCount.get());
        assertEquals(300, agent.config().pollSeconds);
    }

    @Test
    @DisplayName("变更生效：换源换配置、释放旧源、checkpoint 不动；新文本成基线后不再换")
    void changedTextSwapsAndClosesOldSource() throws Exception {
        CloseTrackingSource initial = new CloseTrackingSource();
        AgentMain agent = boot(initial);
        clockMs.addAndGet(60_000);
        Checkpoint before = agent.checkpoint();

        remoteText = remoteOf(dir.resolve("v2").toString(), 5);
        assertTrue(agent.tickRefresh());

        assertEquals(5, agent.config().pollSeconds);
        assertTrue(agent.source() instanceof CsvSource);
        assertEquals("csv:" + dir.resolve("v2"), agent.source().name());
        assertEquals(1, initial.closeCount.get());   // 旧源已释放
        assertSame(before, agent.checkpoint());      // 断点不因换源重置

        // 再到点：V2 已成基线，文本一致 → 不再换
        clockMs.addAndGet(60_000);
        assertFalse(agent.tickRefresh());
        assertEquals(3, fetchCount.get());
    }

    @Test
    @DisplayName("失败兜底：非 2xx 沿用当前配置，旧件不释放")
    void serverFailureKeepsCurrentConfig() throws Exception {
        CloseTrackingSource initial = new CloseTrackingSource();
        AgentMain agent = boot(initial);
        clockMs.addAndGet(60_000);
        AgentConfig before = agent.config();

        remoteStatus = 503;
        assertFalse(agent.tickRefresh());
        assertSame(before, agent.config());
        assertEquals(300, agent.config().pollSeconds);
        assertEquals(0, initial.closeCount.get());
    }

    @Test
    @DisplayName("失败兜底：新配置校验不过（未知 source.type）不换件")
    void invalidNewConfigKeepsCurrent() throws Exception {
        CloseTrackingSource initial = new CloseTrackingSource();
        AgentMain agent = boot(initial);
        clockMs.addAndGet(60_000);
        AgentConfig before = agent.config();

        remoteText = "gateway.endpoint=http://127.0.0.1:1\ngateway.api-key=k1\nsource.type=bogus\n";
        assertFalse(agent.tickRefresh());
        assertSame(before, agent.config());
        assertEquals(0, initial.closeCount.get());
    }

    @Test
    @DisplayName("关闭态：refresh=0 或测试注入路径恒不拉取")
    void refreshDisabledNeverFetches() throws Exception {
        AgentConfig cfg = new AgentConfig();
        cfg.gatewayEndpoint = "http://127.0.0.1:1";
        cfg.gatewayApiKey = "k";
        cfg.gameId = "g1";
        cfg.environment = "prod";
        cfg.sourceType = "csv";
        cfg.csvDir = dir.toString();
        cfg.configRefreshSeconds = 0;
        cfg.checkpointPath = dir.resolve("cp.json").toString();
        AgentMain agent = new AgentMain(cfg, new CloseTrackingSource(), new GatewaySink(cfg),
                new StatusReporter(cfg), new CheckpointStore(Path.of(cfg.checkpointPath)));
        clockMs.addAndGet(60_000);
        assertFalse(agent.tickRefresh());
        assertEquals(0, fetchCount.get());
    }
}
