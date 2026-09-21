package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("可配置漏斗 job")
class ConfigurableFunnelsJobTest {

    // ===== main 空配置安全退出 =====

    @Test
    @DisplayName("main：控制面库不可达时加载空配置安全退出（不 execute）")
    void mainExitsSafelyWhenControlDbUnreachable() throws Exception {
        System.setProperty("control.db.url", "jdbc:postgresql://127.0.0.1:1/none");
        try {
            ConfigurableFunnelsJob.main(new String[0]);   // 连接拒绝 → 空配置 → return，不触达 execute
        } finally {
            System.clearProperty("control.db.url");
        }
    }

    @Test
    @DisplayName("loadFromControlDb：连接失败返回空列表")
    void loadFromControlDbReturnsEmptyOnConnectionFailure() {
        List<ConfigurableFunnelsJob.FunnelConfig> configs =
                ConfigurableFunnelsJob.loadFromControlDb("jdbc:postgresql://127.0.0.1:1/none", "u", "p");
        assertTrue(configs.isEmpty());
    }

    @Test
    @DisplayName("driverAvailable：classpath 有/无驱动类两个分支")
    void driverAvailableBranches() {
        assertTrue(ConfigurableFunnelsJob.driverAvailable("org.postgresql.Driver"));
        assertFalse(ConfigurableFunnelsJob.driverAvailable("no.such.Driver"));
    }

    @Test
    @DisplayName("loadWithConnection：成功走完整加载；获取连接抛错安全返回空列表")
    void loadWithConnectionHappyAndFailurePaths() throws Exception {
        ResultSet mainRs = rs(
                new String[]{"id", "game_id", "name", "type", "user_key", "time_window_sec"},
                new Object[][]{{"fx", "g", "n", "SEQUENTIAL", "user_id", 30L}});
        ResultSet emptySteps = rs(
                new String[]{"id", "step_order", "name", "event_name", "event_filter", "time_window_sec", "optional"},
                new Object[0][]);
        Connection conn = connRouting(sql -> sql.contains("funnel_configs") ? psReturning(mainRs) : psReturning(emptySteps));

        List<ConfigurableFunnelsJob.FunnelConfig> loaded = ConfigurableFunnelsJob.loadWithConnection(() -> conn);
        assertEquals(1, loaded.size());
        assertEquals("fx", loaded.get(0).id);

        List<ConfigurableFunnelsJob.FunnelConfig> failed = ConfigurableFunnelsJob.loadWithConnection(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(failed.isEmpty());
    }

    // ===== 配置/构件 =====

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] cfg = ConfigurableFunnelsJob.config();
        assertEquals("jdbc:postgresql://localhost:5432/oddsmaker", cfg[6]);
        assertEquals("oddsmaker", cfg[7]);
        assertEquals("oddsmaker", cfg[8]);
        assertEquals("oddsmaker-configurable-funnels", ConfigurableFunnelsJob.JOB_NAME);

        System.setProperty("control.db.url", "jdbc:postgresql://pg:5432/prod");
        try {
            assertEquals("jdbc:postgresql://pg:5432/prod", ConfigurableFunnelsJob.config()[6]);
        } finally {
            System.clearProperty("control.db.url");
        }
    }

    @Test
    @DisplayName("watermarks/stepEventNames/isStepEventIn/funnelKey：构件直测")
    void helpers() throws Exception {
        WatermarkStrategy<RawEvent> wm = ConfigurableFunnelsJob.watermarks();
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<RawEvent> assigner = wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 9_000_000L;
        assertEquals(9_000L, assigner.extractTimestamp(e, 0L));
        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 7_000_000L;
        assertEquals(7_000L, assigner.extractTimestamp(clientOnly, 0L));   // ts_client 回退
        long before = System.currentTimeMillis();
        long nowMs = assigner.extractTimestamp(new RawEvent(), 0L);        // 均缺省 → now
        assertTrue(nowMs >= before && nowMs <= System.currentTimeMillis());

        ConfigurableFunnelsJob.FunnelConfig config = new ConfigurableFunnelsJob.FunnelConfig();
        config.steps = List.of(step("e1", 0, false), step("e2", 1, true));
        assertEquals(List.of("e1", "e2"), ConfigurableFunnelsJob.stepEventNames(config));

        RawEvent hit = new RawEvent();
        hit.event_name = "e2";
        RawEvent miss = new RawEvent();
        miss.event_name = "x";
        assertTrue(ConfigurableFunnelsJob.isStepEventIn(hit, List.of("e1", "e2")));
        assertFalse(ConfigurableFunnelsJob.isStepEventIn(miss, List.of("e1", "e2")));
        assertFalse(ConfigurableFunnelsJob.isStepEventIn(new RawEvent(), List.of("e1")));

        // 过滤算子工厂：lambda 体直调
        org.apache.flink.api.common.functions.FilterFunction<RawEvent> f = ConfigurableFunnelsJob.stepFilter(config);
        assertTrue(f.filter(hit));
        assertFalse(f.filter(miss));

        RawEvent keyed = new RawEvent();
        keyed.game_id = "g";
        keyed.environment = "dev";
        keyed.user_id = "u";
        assertEquals("g|dev|u", ConfigurableFunnelsJob.funnelKey(keyed));
        keyed.user_id = null;
        keyed.device_id = "d9";
        assertEquals("g|dev|d9", ConfigurableFunnelsJob.funnelKey(keyed));   // uidOf 回退 device_id

        assertNotNull(new ConfigurableFunnelsJob());   // 覆盖隐式构造器
    }

    private static ConfigurableFunnelsJob.FunnelStep step(String eventName, int order, boolean optional) {
        ConfigurableFunnelsJob.FunnelStep s = new ConfigurableFunnelsJob.FunnelStep();
        s.id = "st" + order;
        s.stepOrder = order;
        s.name = "Step " + (order + 1);
        s.eventName = eventName;
        s.timeWindowSec = 10;
        s.optional = optional;
        return s;
    }

    // ===== 管道搭建 =====

    @Test
    @DisplayName("buildPipeline：启用的漏斗建链、禁用的跳过（本地环境惰性搭建）")
    void buildPipelineWiresEnabledFunnelsOnly() {
        ConfigurableFunnelsJob.FunnelConfig enabled = new ConfigurableFunnelsJob.FunnelConfig();
        enabled.id = "f1";
        enabled.name = "n1";
        enabled.type = "SEQUENTIAL";
        enabled.enabled = true;
        enabled.timeWindowSec = 60;
        enabled.steps = List.of(step("e1", 0, false), step("e2", 1, false));

        ConfigurableFunnelsJob.FunnelConfig disabled = new ConfigurableFunnelsJob.FunnelConfig();
        disabled.id = "f2";
        disabled.name = "n2";
        disabled.type = "SEQUENTIAL";
        disabled.enabled = false;
        disabled.steps = List.of(step("e1", 0, false));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        StreamExecutionEnvironment returned = ConfigurableFunnelsJob.buildPipeline(env,
                ConfigurableFunnelsJob.config(), List.of(enabled, disabled));
        assertEquals(env, returned);
        assertTrue(env.getTransformations().size() >= 3, "expect >= 3 transformations, got " + env.getTransformations().size());
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindConfigurableRow：8 个参数按序绑定")
    void bindConfigurableRowSetsAllParameters() throws Exception {
        ConfigurableFunnelsJob.FunnelRow row = new ConfigurableFunnelsJob.FunnelRow();
        row.gameId = "g";
        row.environment = "prod";
        row.funnelId = "f1";
        row.eventDateEpochDay = 20_000L;
        row.step = 2;
        row.stepName = "Step 2";
        row.users = 1;
        row.conversionRate = 100.0;

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                ConfigurableFunnelsJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        ConfigurableFunnelsJob.bindConfigurableRow(ps, row);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals("f1", calls.get("setString:3"));
        assertNotNull(calls.get("setDate:4"));
        assertEquals(2, calls.get("setInt:5"));
        assertEquals("Step 2", calls.get("setString:6"));
        assertEquals(1L, calls.get("setLong:7"));
        assertEquals(100.0, calls.get("setDouble:8"));
        assertEquals(8, calls.size());
    }

    // ===== 控制面配置加载（Proxy JDBC） =====

    /** 脚本化 ResultSet：按列名取值、支持 wasNull。 */
    private static ResultSet rs(String[] cols, Object[][] rows) {
        return (ResultSet) Proxy.newProxyInstance(ConfigurableFunnelsJobTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new InvocationHandler() {
                    private int idx = -1;
                    private Object last;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "next" -> ++idx < rows.length;
                            case "wasNull" -> last == null;
                            case "getString" -> {
                                last = rows[idx][col(cols, args[0])];
                                yield (String) last;
                            }
                            case "getLong" -> {
                                last = rows[idx][col(cols, args[0])];
                                yield last == null ? 0L : ((Number) last).longValue();
                            }
                            case "getInt" -> {
                                last = rows[idx][col(cols, args[0])];
                                yield last == null ? 0 : ((Number) last).intValue();
                            }
                            case "getBoolean" -> {
                                last = rows[idx][col(cols, args[0])];
                                yield Boolean.TRUE.equals(last);
                            }
                            default -> null;
                        };
                    }
                });
    }

    private static int col(String[] cols, Object nameOrIdx) {
        if (nameOrIdx instanceof Integer i) return i - 1;
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].equals(nameOrIdx)) return i;
        }
        return -1;
    }

    /** executeQuery 返回预设 ResultSet 的 PreparedStatement。 */
    private static PreparedStatement psReturning(ResultSet rs) {
        return (PreparedStatement) Proxy.newProxyInstance(ConfigurableFunnelsJobTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> "executeQuery".equals(m.getName()) ? rs : null);
    }

    /** 按 SQL 前缀路由 PreparedStatement 的 Connection。 */
    private static Connection connRouting(java.util.function.Function<String, PreparedStatement> bySql) {
        return (Connection) Proxy.newProxyInstance(ConfigurableFunnelsJobTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (Object p, Method m, Object[] a) -> {
                    if ("prepareStatement".equals(m.getName())) {
                        return bySql.apply((String) a[0]);
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("loadFunnelConfigs：行映射 + time_window_sec 缺省/非法回退 24h + 步骤装配")
    void loadFunnelConfigsMapsRowsAndSteps() throws Exception {
        // 主查询 2 行：显式 100 秒窗口 / wasNull → 24h 默认；0 同样回退默认
        ResultSet mainRs = rs(
                new String[]{"id", "game_id", "name", "type", "user_key", "time_window_sec"},
                new Object[][]{
                        {"fa1", "g1", "funnel-a", "SEQUENTIAL", "user_id", 100L},
                        {"fa2", "g1", "funnel-b", "STANDARD", "device_id", 0L},
                });
        ResultSet stepsRs = rs(
                new String[]{"id", "step_order", "name", "event_name", "event_filter", "time_window_sec", "optional"},
                new Object[][]{
                        {"s1", 1, "First", "e1", null, 10L, false},
                        {"s2", 2, "Second", "e2", null, 20L, true},
                });

        Connection conn = connRouting(sql -> {
            if (sql.contains("funnel_configs")) return psReturning(mainRs);
            if (sql.contains("funnel_steps")) return psReturning(stepsRs);
            throw new IllegalArgumentException("unexpected sql: " + sql);
        });

        List<ConfigurableFunnelsJob.FunnelConfig> configs = ConfigurableFunnelsJob.loadFunnelConfigs(conn);
        assertEquals(2, configs.size());
        ConfigurableFunnelsJob.FunnelConfig a = configs.get(0);
        assertEquals("fa1", a.id);
        assertEquals("g1", a.gameId);
        assertEquals("SEQUENTIAL", a.type);
        assertEquals("user_id", a.userKey);
        assertEquals(100L, a.timeWindowSec);
        assertTrue(a.enabled);
        assertEquals(2, a.steps.size());
        assertEquals("e1", a.steps.get(0).eventName);
        assertTrue(a.steps.get(1).optional);

        assertEquals("device_id", configs.get(1).userKey);
        assertEquals(24 * 3600L, configs.get(1).timeWindowSec);   // 0 → 默认
    }

    @Test
    @DisplayName("loadFunnelSteps：步骤行映射（optional/窗口秒，NULL 回退 0/false）")
    void loadFunnelStepsMapsRows() throws Exception {
        ResultSet stepsRs = rs(
                new String[]{"id", "step_order", "name", "event_name", "event_filter", "time_window_sec", "optional"},
                new Object[][]{
                        {"s1", 1, "Install", "install", "{}", 30L, true},
                        {"s2", 2, "Open", "open", null, null, null},
                });
        Connection conn = connRouting(sql -> psReturning(stepsRs));
        List<ConfigurableFunnelsJob.FunnelStep> steps = ConfigurableFunnelsJob.loadFunnelSteps(conn, "fa1");
        assertEquals(2, steps.size());
        assertEquals("s1", steps.get(0).id);
        assertEquals("install", steps.get(0).eventName);
        assertEquals(30L, steps.get(0).timeWindowSec);
        assertTrue(steps.get(0).optional);
        // NULL 窗口 → 0（stepWindowMs 回落漏斗总窗）；NULL optional → false
        assertEquals(0L, steps.get(1).timeWindowSec);
        assertFalse(steps.get(1).optional);
    }

    // ===== ConfigurableFunnelProcess 直测（内存 MapState） =====

    static class TestMapState implements org.apache.flink.api.common.state.MapState<String, Long> {
        final java.util.HashMap<String, Long> m = new java.util.HashMap<>();

        @Override
        public Long get(String key) {
            return m.get(key);
        }

        @Override
        public void put(String key, Long value) {
            m.put(key, value);
        }

        @Override
        public void putAll(Map<String, Long> map) {
            m.putAll(map);
        }

        @Override
        public void remove(String key) {
            m.remove(key);
        }

        @Override
        public boolean contains(String key) {
            return m.containsKey(key);
        }

        @Override
        public Iterable<Map.Entry<String, Long>> entries() {
            return m.entrySet();
        }

        @Override
        public Iterable<String> keys() {
            return m.keySet();
        }

        @Override
        public Iterable<Long> values() {
            return m.values();
        }

        @Override
        public java.util.Iterator<Map.Entry<String, Long>> iterator() {
            return m.entrySet().iterator();
        }

        @Override
        public boolean isEmpty() {
            return m.isEmpty();
        }

        @Override
        public void clear() {
            m.clear();
        }
    }

    private static <T> Collector<T> sinkTo(List<T> out) {
        return new Collector<>() {
            @Override
            public void collect(T record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    private static KeyedProcessFunction<String, RawEvent, ConfigurableFunnelsJob.FunnelRow>.Context keyedContext(
            KeyedProcessFunction<String, RawEvent, ConfigurableFunnelsJob.FunnelRow> fn) {
        return fn.new Context() {
            @Override
            public Long timestamp() {
                return null;
            }

            @Override
            public TimerService timerService() {
                return null;
            }

            @Override
            public <X> void output(OutputTag<X> outputTag, X value) {
            }

            @Override
            public String getCurrentKey() {
                return "k";
            }
        };
    }

    private static ConfigurableFunnelsJob.ConfigurableFunnelProcess processWithState(
            String type, int stepCount, long windowSec, TestMapState state) throws Exception {
        ConfigurableFunnelsJob.FunnelConfig config = new ConfigurableFunnelsJob.FunnelConfig();
        config.id = "f1";
        config.name = "t";
        config.type = type;
        config.timeWindowSec = windowSec;
        config.enabled = true;
        List<ConfigurableFunnelsJob.FunnelStep> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            // 最后一步 optional，其余必选；步骤窗口 10 秒
            ConfigurableFunnelsJob.FunnelStep s = step("e" + (i + 1), i, i == stepCount - 1);
            s.timeWindowSec = 10;
            steps.add(s);
        }
        config.steps = steps;
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = new ConfigurableFunnelsJob.ConfigurableFunnelProcess(config);
        Field f = ConfigurableFunnelsJob.ConfigurableFunnelProcess.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(fn, state);
        return fn;
    }

    private static RawEvent event(String name, long tsMs) {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.event_name = name;
        r.ts_server = tsMs * 1000L;
        return r;
    }

    @Test
    @DisplayName("顺序漏斗：三步依序推进；跳步超窗被 optional 回退救回")
    void sequentialFunnelAdvances() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e1", 1L), keyedContext(fn), sinkTo(out));   // 同日重复 step1 不计
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).step);

        // 跳过 e2 直接 e3：prev(step_1) 无 ts → optional 回退 step_0(0)，8-0 <= 10s → 计入
        fn.processElement(event("e3", 8L), keyedContext(fn), sinkTo(out));
        assertEquals(2, out.size());
        assertEquals(3, out.get(1).step);
        assertEquals("Step 3", out.get(1).stepName);
        assertEquals(100.0, out.get(1).conversionRate);

        // e2：prev(step_2 有 ts=8) 12-8 <= 10 → 计入
        fn.processElement(event("e2", 12L), keyedContext(fn), sinkTo(out));
        assertEquals(3, out.size());
        assertEquals(2, out.get(2).step);
    }

    @Test
    @DisplayName("顺序漏斗：超窗且无更早步骤可回退时不计；非步骤事件忽略")
    void sequentialFunnelMisses() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 2, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("other", 0L), keyedContext(fn), sinkTo(out));   // 非步骤
        assertTrue(out.isEmpty());

        // e2 无 step_1_ts → optional 回退 i=-1 循环不执行
        fn.processElement(event("e2", 0L), keyedContext(fn), sinkTo(out));
        assertTrue(out.isEmpty());

        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 50_000L), keyedContext(fn), sinkTo(out));   // 50s > 10s 步骤窗，且回退 step_0 同样超窗
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("无序漏斗：全步完成窗口内 → converted；已转化不再计")
    void unorderedFunnelConverts() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("STANDARD", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 5L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e3", 8L), keyedContext(fn), sinkTo(out));   // 全步完成，跨度 8s <= 60s → converted
        assertEquals(3, out.size());
        assertEquals(Long.valueOf(1L), state.m.get("converted"));

        fn.processElement(event("e1", 20L), keyedContext(fn), sinkTo(out));   // converted 后不再计
        assertEquals(3, out.size());
    }

    @Test
    @DisplayName("无序漏斗：跨度超窗 → 重置状态，当前事件成为新一轮起点")
    void unorderedFunnelResetsOnSpanOverflow() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("STANDARD", 2, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 100_000L), keyedContext(fn), sinkTo(out));   // 跨度 100s > 60s → 重置
        assertEquals(2, out.size());   // 两步各 emit 一次（emit 在重置判定前）
        org.junit.jupiter.api.Assertions.assertNull(state.m.get("converted"));
        org.junit.jupiter.api.Assertions.assertNull(state.m.get("u_step_0_ts"));   // 已被清除
        assertEquals(Long.valueOf(100_000L), state.m.get("u_step_1_ts"));   // 当前事件作为新起点
    }

    @Test
    @DisplayName("类型为 null 时按顺序漏斗处理")
    void nullTypeTreatedAsSequential() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState(null, 2, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());   // 走 processSequential，而非无序分支
    }

    @Test
    @DisplayName("optional 步骤回退链自然耗尽：所有更早步骤均超窗则不计")
    void optionalFallbackExhaustsOlderSteps() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        // e3（optional）：prev=step_1 无 ts → 回退 i=0：50s > 10s 步骤窗不满足 → 循环自然退出，不计
        fn.processElement(event("e3", 50_000L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        org.junit.jupiter.api.Assertions.assertNull(state.m.get("step_2_ts"));
    }

    @Test
    @DisplayName("窗口回退链：步骤窗口 0 → 漏斗总窗；总窗 0 → 默认 24h")
    void windowFallbackChain() throws Exception {
        // 步骤 timeWindowSec=0 → 回退 config.timeWindowSec=60s：30s 间隔可计入
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 2, 60, state);
        for (ConfigurableFunnelsJob.FunnelStep s : stepsOf(fn)) s.timeWindowSec = 0;
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 30L), keyedContext(fn), sinkTo(out));   // 30s <= 60s 总窗
        assertEquals(2, out.size());

        // 总窗 0 → 默认 24h：无序漏斗大跨度仍判转化
        TestMapState state2 = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn2 = processWithState("STANDARD", 2, 0, state2);
        List<ConfigurableFunnelsJob.FunnelRow> out2 = new ArrayList<>();
        fn2.processElement(event("e1", 0L), keyedContext(fn2), sinkTo(out2));
        fn2.processElement(event("e2", 3_600L), keyedContext(fn2), sinkTo(out2));   // 1h <= 24h
        assertEquals(2, out2.size());
        assertEquals(Long.valueOf(1L), state2.m.get("converted"));
    }

    private static List<ConfigurableFunnelsJob.FunnelStep> stepsOf(ConfigurableFunnelsJob.ConfigurableFunnelProcess fn) throws Exception {
        Field f = ConfigurableFunnelsJob.ConfigurableFunnelProcess.class.getDeclaredField("config");
        f.setAccessible(true);
        ConfigurableFunnelsJob.FunnelConfig config = (ConfigurableFunnelsJob.FunnelConfig) f.get(fn);
        return config.steps;
    }

    @Test
    @DisplayName("事件时间戳回退 ts_client → now")
    void timestampFallsBackToClientThenNow() throws Exception {
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();

        RawEvent clientOnly = event("e1", 0L);
        clientOnly.ts_server = null;
        clientOnly.ts_client = 3_000_000L;
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 2, 60, new TestMapState());
        fn.processElement(clientOnly, keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(0L, out.get(0).eventDateEpochDay);

        RawEvent noTs = event("e1", 0L);
        noTs.ts_server = null;
        noTs.ts_client = null;
        long before = System.currentTimeMillis();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fnNow = processWithState("SEQUENTIAL", 2, 60, new TestMapState());
        fnNow.processElement(noTs, keyedContext(fnNow), sinkTo(out));
        assertEquals(2, out.size());
        assertTrue(out.get(1).eventDateEpochDay >= before / 86_400_000L);
    }

    /** Proxy RuntimeContext：getMapState 返回注入状态，其余按返回类型给默认值。 */
    private static org.apache.flink.api.common.functions.RuntimeContext runtimeContextWith(TestMapState state) {
        return (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                ConfigurableFunnelsJobTest.class.getClassLoader(),
                new Class<?>[]{org.apache.flink.api.common.functions.RuntimeContext.class},
                (Object p, Method m, Object[] a) -> {
                    if ("getMapState".equals(m.getName())) return state;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return Boolean.FALSE;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == char.class) return (char) 0;
                    return null;
                });
    }

    @Test
    @DisplayName("open：经 RuntimeContext 声明并接线 MapState")
    void openWiresStateViaRuntimeContext() throws Exception {
        ConfigurableFunnelsJob.FunnelConfig config = new ConfigurableFunnelsJob.FunnelConfig();
        config.id = "f9";
        config.type = "SEQUENTIAL";
        config.timeWindowSec = 60;
        config.enabled = true;
        config.steps = List.of(step("e1", 0, false), step("e2", 1, false));
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = new ConfigurableFunnelsJob.ConfigurableFunnelProcess(config);
        TestMapState state = new TestMapState();
        fn.setRuntimeContext(runtimeContextWith(state));
        fn.open(new org.apache.flink.configuration.Configuration());
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));   // open 接线的状态可写
        assertEquals(1, out.size());
    }

    // ===== main 带配置全流程（替身 JDBC 与执行环境） =====

    @Test
    @DisplayName("main：控制库返回配置时完成入口（替身 JDBC 与执行环境，不触达真实集群）")
    void mainLoadConfigsAndCompletes() throws Exception {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        try (org.mockito.MockedStatic<org.apache.flink.streaming.api.environment.StreamExecutionEnvironment> envMock =
                 org.mockito.Mockito.mockStatic(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class);
             org.mockito.MockedStatic<java.sql.DriverManager> dmMock = org.mockito.Mockito.mockStatic(java.sql.DriverManager.class)) {

            org.apache.flink.streaming.api.environment.StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
            envMock.when(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment::getExecutionEnvironment)
                .thenReturn(env);

            // 漏斗主查询返回 1 行启用配置；步骤查询返回空
            org.mockito.Mockito.when(rs.next()).thenReturn(true, false, false);
            org.mockito.Mockito.when(rs.getString("id")).thenReturn("f1");
            org.mockito.Mockito.when(rs.getString("game_id")).thenReturn("g");
            org.mockito.Mockito.when(rs.getString("name")).thenReturn("n");
            org.mockito.Mockito.when(rs.getString("type")).thenReturn("standard");
            org.mockito.Mockito.when(rs.getString("user_key")).thenReturn("user_id");
            org.mockito.Mockito.when(rs.getLong("time_window_sec")).thenReturn(0L);
            org.mockito.Mockito.when(rs.wasNull()).thenReturn(true);
            java.sql.PreparedStatement ps = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
            org.mockito.Mockito.when(ps.executeQuery()).thenReturn(rs);
            java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
            org.mockito.Mockito.when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
            dmMock.when(() -> java.sql.DriverManager.getConnection(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(conn);

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ConfigurableFunnelsJob.main(new String[0]));
        }
    }

    @Test
    @DisplayName("loadFromControlDb：驱动类缺失（SPI 注册兜底分支）返回空列表")
    void loadFromControlDbReturnsEmptyWhenDriverMissing() {
        // 不 mock java.lang.Class（mockStatic 对 java.lang 的插桩与 JaCoCo 冲突，同 Math 的坑），
        // 改传不存在的驱动类名直接走缺失分支
        assertTrue(ConfigurableFunnelsJob
            .loadFromControlDb("jdbc:postgresql://127.0.0.1:1/none", "u", "p", "no.such.Driver").isEmpty());
    }

    // ===== 分支对侧补充（BRANCH 收口） =====

    @Test
    @DisplayName("uidOf：user_id 非空空串回退 device_id（null 与非空已盖）")
    void uidOfFallsBackOnEmptyUserId() {
        RawEvent r = new RawEvent();
        r.user_id = "";
        r.device_id = "d9";
        assertEquals("d9", ConfigurableFunnelsJob.uidOf(r));
    }

    @Test
    @DisplayName("isUnordered：UNORDERED 类型与 STANDARD 同走无序分支")
    void unorderedTypeGoesUnorderedPath() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("UNORDERED", 2, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());   // 走 processUnordered：u_step_0_ts 首见 emit
        assertEquals(Long.valueOf(0L), state.m.get("u_step_0_ts"));
    }

    @Test
    @DisplayName("无序漏斗：同一步骤二次到达不再重复计数（u_step_*_ts 已存在的 false 侧）")
    void unorderedSameStepTwiceCountsOnce() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("STANDARD", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e1", 5L), keyedContext(fn), sinkTo(out));   // 二次 e1：ts 已存在不覆盖不 emit
        assertEquals(1, out.size());
        assertEquals(Long.valueOf(0L), state.m.get("u_step_0_ts"));   // 首见时间保留
    }

    @Test
    @DisplayName("顺序漏斗：后续步骤同日二次到达不重复计数（step_N_day_* 已存在的 false 侧）")
    void sequentialSameLaterStepTwiceCountsOnce() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 2L), keyedContext(fn), sinkTo(out));   // step_1_day_0 首见 emit
        fn.processElement(event("e2", 4L), keyedContext(fn), sinkTo(out));   // 二次 e2 同日：已存在不 emit
        assertEquals(2, out.size());
        assertEquals(Long.valueOf(4L), state.m.get("step_1_ts"));   // ts 仍推进
    }

    @Test
    @DisplayName("顺序漏斗：无前置且非 optional 的跳步直接丢弃（optional false 侧）")
    void sequentialNonOptionalSkippedStepDropped() throws Exception {
        TestMapState state = new TestMapState();
        // 3 步配置：仅最后一步 optional，直接来 e2（step2 非 optional）
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e2", 0L), keyedContext(fn), sinkTo(out));   // prev(step_1) 无 ts 且非 optional
        assertTrue(out.isEmpty());
        assertNullState(state, "step_1_ts");
    }

    @Test
    @DisplayName("顺序漏斗 optional 回退：更早步骤均无 ts 时自然耗尽（prevPrevTs null 侧）")
    void optionalFallbackSkipsNullPrevPrevTs() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        // e3（optional）直接到达：step_1/step_0 均无 ts → 两轮 prevPrevTs==null → 循环耗尽
        fn.processElement(event("e3", 0L), keyedContext(fn), sinkTo(out));
        assertTrue(out.isEmpty());
        assertNullState(state, "step_2_ts");
    }

    @Test
    @DisplayName("顺序漏斗 optional 回退：目标步骤当日已计数时只推进 ts 不重复 emit")
    void optionalFallbackSkipsAlreadyCountedDay() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 3, 60, state);
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        // 预置 step_2 当日已计数：回溯命中 step_0（8-0<=10s）但 day key 已存在 → 不 emit、仍推进 ts
        state.m.put("step_2_day_0", 1L);
        fn.processElement(event("e3", 8L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(Long.valueOf(8L), state.m.get("step_2_ts"));
    }

    private static void assertNullState(TestMapState state, String key) {
        org.junit.jupiter.api.Assertions.assertNull(state.m.get(key));
    }

    @Test
    @DisplayName("stepWindowMs 第三级回退：步骤窗 0 + 漏斗总窗 0 → 默认 24h")
    void stepWindowFallsBackTo24hDefault() throws Exception {
        TestMapState state = new TestMapState();
        ConfigurableFunnelsJob.ConfigurableFunnelProcess fn = processWithState("SEQUENTIAL", 2, 0, state);
        for (ConfigurableFunnelsJob.FunnelStep s : stepsOf(fn)) s.timeWindowSec = 0;
        List<ConfigurableFunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("e1", 0L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("e2", 3_600L), keyedContext(fn), sinkTo(out));   // 1h <= 24h 默认窗 → 计入
        assertEquals(2, out.size());
    }
}
