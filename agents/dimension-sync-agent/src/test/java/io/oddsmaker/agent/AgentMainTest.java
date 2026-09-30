package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 单轮循环：推送成功才前进断点；每轮心跳上报；失败路径 errorCount++/水位不前进。
 * Gateway/Control 用内嵌 HttpServer 走真实 HTTP（沿用仓库 RuleFetcherTest 的 idiom）。
 * 另覆盖 {@link AgentMain#sourceOf} 的五类 source 装配分支与两类失败细节
 * （异常无 message、checkpoint 落盘自身失败）。
 *
 * <p>{@code AgentMain.main()} 不在本类范围：它 {@code cfg.validate()} 后进入 {@code run()}
 * 无限循环并注册 shutdown hook，无法在单测内确定性地启停（进程级行为留给 installDist 手工验收）。
 */
class AgentMainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final ConcurrentLinkedQueue<String> batchBodies = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> statusBodies = new ConcurrentLinkedQueue<>();
    final AtomicInteger gatewayStatus = new AtomicInteger(200);

    @TempDir
    Path dir;

    /** 可编排的假源：依次返回排好的 PollResult，或按 failure 抛错（走 cycle 的异常分支）。 */
    static final class FakeSource implements DimensionSource {
        final Deque<DimensionSource.PollResult> results = new ArrayDeque<>();
        final List<Checkpoint> polledWith = new ArrayList<>();
        RuntimeException failure;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public PollResult poll(Checkpoint current) {
            polledWith.add(current.copy());
            if (failure != null) {
                throw failure;
            }
            return results.isEmpty() ? PollResult.of(List.of(), current.copy()) : results.removeFirst();
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1/batch", exchange -> {
            batchBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(gatewayStatus.get(), ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.createContext("/api/dimensions/sync-status", exchange -> {
            statusBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AgentConfig cfg() {
        AgentConfig cfg = new AgentConfig();
        cfg.gatewayEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        cfg.gatewayApiKey = "k";
        cfg.gameId = "g1";
        cfg.environment = "prod";
        cfg.sourceType = "fake";
        cfg.statusUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/dimensions/sync-status";
        cfg.statusToken = "t";
        cfg.checkpointPath = dir.resolve("checkpoint.json").toString();
        return cfg;
    }

    private DimensionChange change(String id) {
        return new DimensionChange("item", "upsert", id, 1000L);
    }

    @Test
    @DisplayName("cycle：推送成功 → 断点前进并落盘；心跳每轮都报（无变更也报）")
    void successCycleAdvancesCheckpointAndReports() throws Exception {
        FakeSource source = new FakeSource();
        Checkpoint withCursor = new Checkpoint();
        withCursor.cursor = "s:123";
        withCursor.lastEventTs = 1000L;
        source.results.add(DimensionSource.PollResult.of(List.of(change("a"), change("b")), withCursor));
        source.results.add(DimensionSource.PollResult.of(List.of(), new Checkpoint()));

        AgentMain agent = new AgentMain(cfg(), source, new GatewaySink(cfg()),
                new StatusReporter(cfg()), new CheckpointStore(Path.of(cfg().checkpointPath)));

        agent.cycle();
        assertEquals(1, batchBodies.size());
        assertEquals(2, batchBodies.poll().split("\n").length); // 取走 cycle 1 的批次体

        Checkpoint cp = agent.checkpoint();
        assertEquals("s:123", cp.cursor);
        assertEquals(2, cp.pushedCount);
        assertEquals(1000L, cp.lastEventTs);
        // 落盘校验：重启可恢复
        Checkpoint persisted = new CheckpointStore(Path.of(cfg().checkpointPath)).load();
        assertEquals("s:123", persisted.cursor);
        assertEquals(2, persisted.pushedCount);

        // 第二轮：无变更 → 不再新增批次请求，但心跳仍报
        agent.cycle();
        assertTrue(batchBodies.isEmpty());
        assertEquals(2, statusBodies.size());

        // FIFO：第一条是 cycle 1 的上报，第二条是 cycle 2 的心跳
        Map<?, ?> afterCycle1 = JSON.readValue(statusBodies.poll(), Map.class);
        assertEquals(2, afterCycle1.get("pushedCount"));
        Map<?, ?> heartbeat = JSON.readValue(statusBodies.poll(), Map.class);
        assertEquals(2, heartbeat.get("pushedCount"));
        assertEquals("g1", heartbeat.get("gameId"));
        assertNotNull(heartbeat.get("sourceKey"));
    }

    @Test
    @DisplayName("失败路径：errorCount++/lastError 落盘，cursor 不前进，下轮重放同窗口")
    void failureKeepsCursorAndCountsErrors() throws Exception {
        FakeSource source = new FakeSource();
        Checkpoint next = new Checkpoint();
        next.cursor = "n:500";
        next.lastEventTs = 5000L;
        source.results.add(DimensionSource.PollResult.of(List.of(change("x")), next));

        gatewayStatus.set(503);
        AgentMain agent = new AgentMain(cfg(), source, new GatewaySink(cfg()),
                new StatusReporter(cfg()), new CheckpointStore(Path.of(cfg().checkpointPath)));

        agent.cycle();
        Checkpoint cp = agent.checkpoint();
        assertNull(cp.cursor);                 // 水位未前进
        assertEquals(0, cp.pushedCount);
        assertEquals(1, cp.errorCount);
        assertNotNull(cp.lastError);
        assertTrue(cp.lastError.contains("503"));

        Checkpoint persisted = new CheckpointStore(Path.of(cfg().checkpointPath)).load();
        assertEquals(1, persisted.errorCount);
        assertNull(persisted.cursor);

        // 失败后的心跳带上错误信息
        Map<?, ?> reported = JSON.readValue(statusBodies.poll(), Map.class);
        assertEquals(1, reported.get("errorCount"));

        // 下一轮以旧水位重放
        Checkpoint recovery = new Checkpoint();
        recovery.cursor = null;
        source.results.add(DimensionSource.PollResult.of(List.of(), recovery));
        gatewayStatus.set(200);
        agent.cycle();
        assertNull(source.polledWith.get(1).cursor);      // 重放仍绑旧水位
        assertEquals(0, agent.checkpoint().pushedCount);   // 空变更不推进计数
        assertEquals(1, agent.checkpoint().errorCount);    // 计数保留
    }

    @Test
    @DisplayName("run：shutdown 置停后退出循环")
    void runStopsWhenShutdown() throws Exception {
        FakeSource source = new FakeSource();
        AgentMain agent = new AgentMain(cfg(), source, new GatewaySink(cfg()),
                new StatusReporter(cfg()), new CheckpointStore(Path.of(cfg().checkpointPath)));
        // 模拟 shutdown hook：300ms 后置停（run 的 500ms 步进睡眠最迟 500ms 内感知）
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            agent.running.set(false);
        });
        stopper.start();
        long start = System.currentTimeMillis();
        agent.run();
        assertTrue(System.currentTimeMillis() - start < 5000);
    }

    @Test
    @DisplayName("sourceOf：csv / excel / mysql / postgres 分支按配置装配对应的源（构造不触网、不读文件）")
    void sourceOfBuildsConfiguredSourceTypes() {
        AgentConfig csv = cfg();
        csv.sourceType = "csv";
        csv.csvDir = dir.toString();
        DimensionSource csvSource = AgentMain.sourceOf(csv);
        assertEquals("csv", csvSource.type());
        assertEquals("csv:" + dir, csvSource.name());

        AgentConfig excel = cfg();
        excel.sourceType = "excel";
        excel.excelDir = dir.toString();
        DimensionSource excelSource = AgentMain.sourceOf(excel);
        assertEquals("excel", excelSource.type());
        assertEquals("excel:" + dir, excelSource.name());

        for (String jdbcType : List.of("mysql", "postgres")) {
            AgentConfig jdbc = cfg();
            jdbc.sourceType = jdbcType;
            jdbc.jdbcUrl = "jdbc:h2:mem:dims";
            DimensionSource source = AgentMain.sourceOf(jdbc);
            assertTrue(source instanceof JdbcSource, jdbcType + " → " + source.getClass());
            assertEquals(jdbcType + ":jdbc:h2:mem:dims", source.name());
        }
    }

    @Test
    @DisplayName("sourceOf：kafka 分支装配真实 KafkaConsumerAdapter（含 SslSettings 六键），构造后可关闭")
    void sourceOfKafkaBranchWiresConsumerAdapter() throws Exception {
        AgentConfig kafka = cfg();
        kafka.sourceType = "kafka";
        kafka.kafkaBootstrap = "127.0.0.1:9999";   // 无人监听的端口：只构造 consumer，不 assign/poll
        kafka.kafkaTopic = "dims";
        kafka.kafkaGroupId = "g";
        DimensionSource source = AgentMain.sourceOf(kafka);
        assertEquals("kafka", source.type());
        assertEquals("kafka:127.0.0.1:9999/dims", source.name());

        java.lang.reflect.Field portField = KafkaSource.class.getDeclaredField("port");
        portField.setAccessible(true);
        Object port = portField.get(source);
        assertTrue(port instanceof KafkaConsumerAdapter, "实际端口实现: " + port.getClass());
        ((AutoCloseable) port).close();            // 释放 consumer 线程与 MBean
    }

    @Test
    @DisplayName("sourceOf：未知 source.type 直接抛错（不静默返回空源）")
    void sourceOfUnknownTypeFailsLoud() {
        AgentConfig bogus = cfg();
        bogus.sourceType = "parquet";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentMain.sourceOf(bogus));
        assertTrue(ex.getMessage().contains("parquet"), ex.getMessage());
    }

    @Test
    @DisplayName("失败路径：异常 message 为 null 时 lastError 取 toString（不留 null、不 NPE）")
    void failureWithoutMessageRecordsToString() throws Exception {
        AgentConfig cfg = cfg();
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException();   // getMessage() == null
        AgentMain agent = new AgentMain(cfg, source, new GatewaySink(cfg),
                new StatusReporter(cfg), new CheckpointStore(Path.of(cfg.checkpointPath)));

        agent.cycle();

        Checkpoint cp = agent.checkpoint();
        assertEquals(1, cp.errorCount);
        assertEquals("java.lang.IllegalStateException", cp.lastError);
        Checkpoint persisted = new CheckpointStore(Path.of(cfg.checkpointPath)).load();
        assertEquals("java.lang.IllegalStateException", persisted.lastError);
        assertEquals(1, statusBodies.size());          // 失败轮的心照报
    }

    @Test
    @DisplayName("失败路径叠加 checkpoint 落盘失败：异常不外逃，计数在内存生效且心跳继续")
    void checkpointSaveFailureStillCountsAndReports() throws Exception {
        AgentConfig cfg = cfg();
        // 把 checkpoint 的父目录做成普通文件 → save 必抛 IOException（FileAlreadyExistsException）
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        cfg.checkpointPath = blocker.resolve("checkpoint.json").toString();
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("boom");
        AgentMain agent = new AgentMain(cfg, source, new GatewaySink(cfg),
                new StatusReporter(cfg), new CheckpointStore(Path.of(cfg.checkpointPath)));

        agent.cycle();   // 不抛

        assertEquals(1, agent.checkpoint().errorCount);
        assertEquals("boom", agent.checkpoint().lastError);
        assertEquals(1, statusBodies.size());
        assertFalse(Files.exists(blocker.resolve("checkpoint.json")));
        // 第二轮继续计数（落盘失败不影响循环存活）
        agent.cycle();
        assertEquals(2, agent.checkpoint().errorCount);
    }

    // === 分支对侧补充（BRANCH 收口）===

    @Test
    @DisplayName("run：pollSeconds 等待窗自然走完后进入下一轮（for 条件假臂），shutdown 置停后退出")
    void runExitsWaitLoopViaConditionThenStops() throws Exception {
        FakeSource source = new FakeSource();
        AgentConfig cfg = cfg();
        cfg.pollSeconds = 1;   // 1000ms 等待窗
        AgentMain agent = new AgentMain(cfg, source, new GatewaySink(cfg),
                new StatusReporter(cfg), new CheckpointStore(Path.of(cfg.checkpointPath)));
        Thread t = new Thread(() -> {
            try {
                agent.run();
            } catch (Exception ignored) {
            }
        });
        t.start();
        // 等首轮 cycle 完成（心跳已报，进入 for 等待窗）
        long deadline = System.currentTimeMillis() + 5000;
        while (statusBodies.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        // 让 for 窗自然走完（1000ms → waited=1000 条件假退出），再置停
        Thread.sleep(1200);
        agent.running.set(false);
        t.join(5000);
        assertFalse(t.isAlive());
    }
}
