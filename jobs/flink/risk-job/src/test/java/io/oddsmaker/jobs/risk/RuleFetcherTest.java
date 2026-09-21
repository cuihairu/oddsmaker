package io.oddsmaker.jobs.risk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("控制面规则拉取")
class RuleFetcherTest {

    /** 起本地 HTTP 服务记录请求并回放固定响应。 */
    private static HttpServer serverResponding(int status, String body,
                                               List<String> seenPaths, List<String> seenTokens) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/risk-dashboard/rules/", ex -> {
            seenPaths.add(ex.getRequestURI().getPath());
            seenTokens.add(ex.getRequestHeaders().getFirst("x-admin-token"));
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, resp.length);
            try (var os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    /** 反射调用私有 fetchAndApply（真实 HTTP 到本地测试服务）。 */
    private static void invokeFetch(RuleFetcher f) throws Exception {
        Method m = RuleFetcher.class.getDeclaredMethod("fetchAndApply");
        m.setAccessible(true);
        m.invoke(f);
    }

    private static void resetRules() {
        RuleConfig.update(new RuleConfig(Map.of()));
    }

    @Test
    @DisplayName("fetchAndApply：同类型取 riskScore 最高，空 type/非正阈值跳过，未配置回落默认")
    void fetchAppliesHighestScorePerType() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        HttpServer server = serverResponding(200, """
                [
                  {"id":"r1","ruleType":"THRESHOLD","triggerThreshold":500,"actionType":"BLOCK","riskScore":90,"riskLevel":"HIGH"},
                  {"id":"r2","ruleType":"THRESHOLD","triggerThreshold":900,"riskScore":30,"riskLevel":"LOW"},
                  {"id":"r3","ruleType":"","triggerThreshold":1,"riskScore":99,"riskLevel":"LOW"},
                  {"id":"r4","ruleType":"FREQUENCY","triggerThreshold":0,"riskScore":99,"riskLevel":"LOW"},
                  {"id":"r5","ruleType":"AD_REWARD","triggerThreshold":7,"actionType":"THROTTLE","riskScore":70,"riskLevel":"HIGH"}
                ]
                """, paths, tokens);
        try {
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    "g1", "tok-1", 60_000L);
            invokeFetch(f);

            assertEquals(1, paths.size());
            assertEquals("/api/risk-dashboard/rules/g1", paths.get(0));
            assertEquals("tok-1", tokens.get(0));   // adminToken 以头附带

            RuleConfig.RuleSpec t = RuleConfig.byType("THRESHOLD");
            assertEquals("r1", t.ruleId);   // 90 > 30 胜出
            assertEquals(500, t.triggerThreshold);
            assertEquals("BLOCK", t.actionType);
            assertEquals(90, t.riskScore);
            assertEquals("HIGH", t.riskLevel);
            assertEquals(7, RuleConfig.byType("AD_REWARD").triggerThreshold);
            assertEquals("THROTTLE", RuleConfig.byType("AD_REWARD").actionType);
            assertEquals(1_000_000, RuleConfig.byType("VELOCITY").triggerThreshold);   // 未配置回落默认
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("fetchAndApply：空数组与非数组响应体保持现状不更新")
    void fetchSkipsEmptyArrayAndNonArrayBody() throws Exception {
        List<String> paths = new ArrayList<>();
        HttpServer emptyArray = serverResponding(200, "[]", paths, new ArrayList<>());
        HttpServer nonArray = serverResponding(200, "{\"not\":\"array\"}", paths, new ArrayList<>());
        try {
            // 先放一个已知快照
            RuleConfig.update(new RuleConfig(Map.of("FREQUENCY",
                    new RuleConfig.RuleSpec("keep", "FREQUENCY", 321, "ALERT", 50, "LOW"))));

            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + emptyArray.getAddress().getPort(), "g1", "", 60_000L);
            invokeFetch(f);
            assertEquals("keep", RuleConfig.byType("FREQUENCY").ruleId);   // 空数组不更新

            RuleFetcher f2 = new RuleFetcher("http://127.0.0.1:" + nonArray.getAddress().getPort(), "g1", "", 60_000L);
            invokeFetch(f2);
            assertEquals("keep", RuleConfig.byType("FREQUENCY").ruleId);   // 非数组不更新
        } finally {
            emptyArray.stop(0);
            nonArray.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("fetchAndApply：非 200 保持现有规则")
    void fetchKeepsExistingOnHttpError() throws Exception {
        HttpServer server = serverResponding(500, "err", new ArrayList<>(), new ArrayList<>());
        try {
            RuleConfig.update(new RuleConfig(Map.of("FREQUENCY",
                    new RuleConfig.RuleSpec("keep", "FREQUENCY", 321, "ALERT", 50, "LOW"))));
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "", 60_000L);
            invokeFetch(f);
            assertEquals("keep", RuleConfig.byType("FREQUENCY").ruleId);
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("fetchAndApply：token 为空不带认证头")
    void fetchOmitsTokenHeaderWhenBlank() throws Exception {
        List<String> tokens = new ArrayList<>();
        HttpServer server = serverResponding(200, "[]", new ArrayList<>(), tokens);
        try {
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "", 60_000L);
            invokeFetch(f);
            assertEquals(1, tokens.size());
            assertNull(tokens.get(0));   // 无 x-admin-token
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("startOnce：单例只启动一次；stop 置停并中断后台线程")
    void startOnceStartsSingleInstance() throws Exception {
        RiskJobTest.stopRuleFetcher();   // 清场
        try {
            String deadUrl = "http://127.0.0.1:1";
            RuleFetcher.startOnce(deadUrl, "g", "", 999_999_999L);
            Object first = RiskJobTest.ruleFetcherInstance();
            assertNotNull(first);

            RuleFetcher.startOnce(deadUrl, "g", "", 999_999_999L);   // 二次：单例 no-op
            assertEquals(first, RiskJobTest.ruleFetcherInstance());
        } finally {
            RiskJobTest.stopRuleFetcher();   // stop：running=false + thread.interrupt
        }
        assertNull(RiskJobTest.ruleFetcherInstance());
    }

    @Test
    @DisplayName("run()：拉取失败容错打印 → 周期 sleep → interrupt 后 break 退出")
    void runLoopToleratesFailuresAndStops() throws Exception {
        // 死 URL：每轮 fetchAndApply 抛连接拒绝 → run 的 catch 打印 → 长周期 sleep
        RuleFetcher f = new RuleFetcher("http://127.0.0.1:1", "g", "", 60_000L);
        Thread main = Thread.currentThread();
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(500);   // 此时 run 必已进入长 sleep（fetch 拒绝 ~220ms）
            } catch (InterruptedException ignored) {
            }
            f.stop();
            main.interrupt();   // 打断测试线程的 sleep → run 内 break
        });
        stopper.setDaemon(true);
        stopper.start();
        f.run();   // 测试线程同步执行（保证被覆盖记录）：失败容错 + sleep + interrupt break
        stopper.join(2_000);
    }

    @Test
    @DisplayName("run()：每轮正常返回（HTTP 500 保持现状）→ sleep 醒来 → stop 后 while 退出")
    void runLoopCyclesAndExitsByFlag() throws Exception {
        HttpServer server = serverResponding(500, "err", new ArrayList<>(), new ArrayList<>());
        try {
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "", 30L);
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(400);   // 数轮正常周期后置停
                } catch (InterruptedException ignored) {
                }
                f.stop();   // running=false → sleep 自然醒后 while 条件退出
            });
            stopper.setDaemon(true);
            stopper.start();
            f.run();   // 每轮 fetchAndApply 正常返回（500 分支）+ sleep 30ms 正常醒
            stopper.join(2_000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("fetchAndApply：全部规则无效（空 type/非正阈值）时不更新快照")
    void fetchSkipsWhenAllRulesInvalid() throws Exception {
        HttpServer server = serverResponding(200, """
                [
                  {"id":"r1","ruleType":"","triggerThreshold":5,"riskScore":90,"riskLevel":"LOW"},
                  {"id":"r2","ruleType":"FREQUENCY","triggerThreshold":0,"riskScore":90,"riskLevel":"LOW"}
                ]
                """, new ArrayList<>(), new ArrayList<>());
        try {
            RuleConfig.update(new RuleConfig(Map.of("FREQUENCY",
                    new RuleConfig.RuleSpec("keep", "FREQUENCY", 321, "ALERT", 50, "LOW"))));
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "", 60_000L);
            invokeFetch(f);
            assertEquals("keep", RuleConfig.byType("FREQUENCY").ruleId);   // collected 空 → 不更新
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("构造器：去尾斜杠；无斜杠原样")
    void constructorStripsTrailingSlash() throws Exception {
        RuleFetcher f = new RuleFetcher("http://localhost:8085/", "g", "t", 1000L);
        java.lang.reflect.Field url = RuleFetcher.class.getDeclaredField("controlUrl");
        url.setAccessible(true);
        assertEquals("http://localhost:8085", url.get(f));   // 尾斜杠剥离

        RuleFetcher plain = new RuleFetcher("http://localhost:8085", "g", "t", 1000L);
        assertEquals("http://localhost:8085", url.get(plain));
        assertTrue(f instanceof Runnable);
    }

    // ===== 分支对侧补充（BRANCH 收口） =====

    @Test
    @DisplayName("fetchAndApply：token 为 null 不带认证头（空串侧已盖）")
    void fetchOmitsTokenHeaderWhenNullToken() throws Exception {
        List<String> tokens = new ArrayList<>();
        HttpServer server = serverResponding(200, "[]", new ArrayList<>(), tokens);
        try {
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", null, 60_000L);
            invokeFetch(f);
            assertEquals(1, tokens.size());
            assertNull(tokens.get(0));   // adminToken null → 不加 x-admin-token
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("fetchAndApply：同类型后到更高分规则替换先到低分（低替高的保留侧已盖）")
    void fetchReplacesWhenLaterRuleScoresHigher() throws Exception {
        HttpServer server = serverResponding(200, """
                [
                  {"id":"low","ruleType":"THRESHOLD","triggerThreshold":500,"riskScore":30,"riskLevel":"LOW"},
                  {"id":"high","ruleType":"THRESHOLD","triggerThreshold":900,"riskScore":90,"riskLevel":"HIGH"}
                ]
                """, new ArrayList<>(), new ArrayList<>());
        try {
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "tok", 60_000L);
            invokeFetch(f);
            RuleConfig.RuleSpec t = RuleConfig.byType("THRESHOLD");
            assertEquals("high", t.ruleId);   // 90 > 30 后到胜出
            assertEquals(900, t.triggerThreshold);
        } finally {
            server.stop(0);
            resetRules();
        }
    }

    @Test
    @DisplayName("startOnce：首次创建并启动守护线程，二次调用见已有实例直接返回")
    void startOnceIsIdempotent() throws Exception {
        // 反射清空静态实例（测试隔离）
        java.lang.reflect.Field instanceField = RuleFetcher.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<RuleFetcher> ref =
            (java.util.concurrent.atomic.AtomicReference<RuleFetcher>) instanceField.get(null);
        java.util.function.Supplier<RuleFetcher> current = ref::get;

        List<String> paths = new ArrayList<>();
        HttpServer server = serverResponding(404, "[]", paths, new ArrayList<>());
        try {
            ref.set(null);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            RuleFetcher.startOnce(base, "g1", "tok", 60_000L);   // 首次：创建 + 启动
            RuleFetcher first = current.get();
            assertNotNull(first);

            RuleFetcher.startOnce(base, "g1", "tok", 60_000L);   // 二次：已有实例直接返回
            assertTrue(current.get() == first, "二次调用不得替换实例");

            first.stop();   // 停守护线程（fetch 404 会安静重试，不阻塞测试）
        } finally {
            RuleFetcher live = current.get();
            if (live != null) live.stop();
            server.stop(0);
            resetRules();
            ref.set(null);
        }
    }
}
