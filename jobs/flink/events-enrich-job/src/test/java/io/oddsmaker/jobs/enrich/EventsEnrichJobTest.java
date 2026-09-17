package io.oddsmaker.jobs.enrich;

import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("事件清洗/富化 job")
class EventsEnrichJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→validate→dedup→map→sink+DLQ）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        SingleOutputStreamOperator<EventsEnrichJob.EventRow> tail = EventsEnrichJob.buildPipeline(env, EventsEnrichJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 5, "expect >= 5 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] cfg = EventsEnrichJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", cfg[1]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", cfg[3]);
        assertEquals("default", cfg[4]);
        assertEquals("", cfg[5]);
        assertEquals("", cfg[6]);
        assertEquals("oddsmaker.deadletter", cfg[7]);
        assertEquals("oddsmaker-events-enrich", EventsEnrichJob.JOB_NAME);

        System.setProperty("geoip.mmdb", "/tmp/x.mmdb");
        System.setProperty("kafka.dlq", "dlq-x");
        try {
            assertEquals("/tmp/x.mmdb", EventsEnrichJob.config()[6]);
            assertEquals("dlq-x", EventsEnrichJob.config()[7]);
        } finally {
            System.clearProperty("geoip.mmdb");
            System.clearProperty("kafka.dlq");
        }
    }

    @Test
    @DisplayName("watermarks/nz/decimalOrZero/toTimestamp：构件直测")
    void helpers() {
        WatermarkStrategy<RawEvent> wm = EventsEnrichJob.watermarks();
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<RawEvent> assigner = wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 9_000_000L;
        assertEquals(9_000L, assigner.extractTimestamp(e, 0L));
        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 7_000_000L;
        assertEquals(7_000L, assigner.extractTimestamp(clientOnly, 0L));
        long before = System.currentTimeMillis();
        long nowMs = assigner.extractTimestamp(new RawEvent(), 0L);
        assertTrue(nowMs >= before && nowMs <= System.currentTimeMillis());

        assertEquals("", EventsEnrichJob.nz(null));
        assertEquals("v", EventsEnrichJob.nz("v"));

        assertEquals(BigDecimal.ZERO, EventsEnrichJob.decimalOrZero(null));
        assertEquals(new BigDecimal("12.5"), EventsEnrichJob.decimalOrZero("12.5"));
        assertEquals(BigDecimal.valueOf(3.5d), EventsEnrichJob.decimalOrZero(3.5f));
        assertEquals(BigDecimal.valueOf(7.0d), EventsEnrichJob.decimalOrZero(7L));   // Number 走 doubleValue
        assertEquals(BigDecimal.ZERO, EventsEnrichJob.decimalOrZero("NaN-ish"));
        BigDecimal bd = new BigDecimal("9.99");
        assertEquals(bd, EventsEnrichJob.decimalOrZero(bd));   // BigDecimal 原样

        assertNull(EventsEnrichJob.toTimestamp(null));
        assertEquals(new Timestamp(2_000L), EventsEnrichJob.toTimestamp(2_000_000L));
    }

    // ===== 校验算子直测 =====

    /** Flink 1.19 Collector.close() 非默认实现，需匿名类补齐。 */
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

    /** ProcessFunction.Context 是非静态内部类，须以 fn.new Context(){} 限定语法创建；记录侧输出。 */
    private static ProcessFunction<RawEvent, RawEvent>.Context validateContext(
            ProcessFunction<RawEvent, RawEvent> fn, List<String> dlqOut) {
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
                dlqOut.add(String.valueOf(value));
            }
        };
    }

    @Test
    @DisplayName("ValidateFunction：必填字段缺失走 DLQ；合法事件透传并填充 ts_server")
    void validateFunctionBranches() {
        EventsEnrichJob.ValidateFunction fn = new EventsEnrichJob.ValidateFunction(new OutputTag<>("dlq", Types.STRING));
        List<RawEvent> out = new ArrayList<>();
        List<String> dlq = new ArrayList<>();

        fn.processElement(new RawEvent(), validateContext(fn, dlq), sinkTo(out));   // 全空 → DLQ
        assertEquals(1, dlq.size());
        assertTrue(dlq.get(0).contains("invalid_schema"));

        RawEvent noDevice = validEvent();
        noDevice.device_id = null;
        fn.processElement(noDevice, validateContext(fn, dlq), sinkTo(out));
        assertEquals(2, dlq.size());

        RawEvent ok = validEvent();   // ts_server 缺失 → 填 now
        long before = System.currentTimeMillis();
        fn.processElement(ok, validateContext(fn, dlq), sinkTo(out));
        assertEquals(2, dlq.size());
        assertEquals(1, out.size());
        assertEquals(ok, out.get(0));
        assertTrue(ok.ts_server >= before * 1000L);

        RawEvent withTs = validEvent();   // 已有 ts_server 不覆盖
        withTs.ts_server = 1_000L;
        fn.processElement(withTs, validateContext(fn, dlq), sinkTo(out));
        assertEquals(1_000L, out.get(1).ts_server);
    }

    private static RawEvent validEvent() {
        RawEvent r = new RawEvent();
        r.event_id = "e1";
        r.event_name = "level_start";
        r.game_id = "g";
        r.environment = "prod";
        r.device_id = "d1";
        return r;
    }

    // ===== 去重算子直测 =====

    static class TestValueState implements ValueState<Long> {
        Long v;

        @Override
        public Long value() {
            return v;
        }

        @Override
        public void update(Long value) {
            v = value;
        }

        @Override
        public void clear() {
            v = null;
        }
    }

    private static KeyedProcessFunction<String, RawEvent, RawEvent>.Context keyedContext(
            KeyedProcessFunction<String, RawEvent, RawEvent> fn, List<String> dlqOut) {
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
                dlqOut.add(String.valueOf(value));
            }

            @Override
            public String getCurrentKey() {
                return "k";
            }
        };
    }

    @Test
    @DisplayName("DedupFunction：首见透传并记账，重复事件走 DLQ duplicate")
    void dedupFunctionDeduplicates() throws Exception {
        OutputTag<String> dlqTag = new OutputTag<>("dlq", Types.STRING);
        EventsEnrichJob.DedupFunction fn = new EventsEnrichJob.DedupFunction(dlqTag);
        TestValueState state = new TestValueState();
        Field f = EventsEnrichJob.DedupFunction.class.getDeclaredField("seenTs");
        f.setAccessible(true);
        f.set(fn, state);

        List<RawEvent> out = new ArrayList<>();
        List<String> dlq = new ArrayList<>();
        RawEvent e = validEvent();

        fn.processElement(e, keyedContext(fn, dlq), sinkTo(out));   // 首见
        assertEquals(1, out.size());
        assertNotNull(state.v);

        fn.processElement(e, keyedContext(fn, dlq), sinkTo(out));   // 重复 → DLQ
        assertEquals(1, out.size());
        assertEquals(1, dlq.size());
        assertTrue(dlq.get(0).contains("duplicate"));
    }

    /** Proxy RuntimeContext：getState 返回注入状态，其余按返回类型给默认值。 */
    private static org.apache.flink.api.common.functions.RuntimeContext runtimeContextWith(ValueState<?> state) {
        return (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                EventsEnrichJobTest.class.getClassLoader(),
                new Class<?>[]{org.apache.flink.api.common.functions.RuntimeContext.class},
                (Object p, Method m, Object[] a) -> {
                    if ("getState".equals(m.getName())) return state;
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
    @DisplayName("DedupFunction.open：经 RuntimeContext 声明并接线 ValueState")
    void dedupOpenWiresState() throws Exception {
        EventsEnrichJob.DedupFunction fn = new EventsEnrichJob.DedupFunction(new OutputTag<>("dlq", Types.STRING));
        TestValueState state = new TestValueState();
        fn.setRuntimeContext(runtimeContextWith(state));
        fn.open(new org.apache.flink.configuration.Configuration());
        List<RawEvent> out = new ArrayList<>();
        fn.processElement(validEvent(), keyedContext(fn, new ArrayList<>()), sinkTo(out));
        assertEquals(1, out.size());
        assertNotNull(state.v);   // open 接线的状态可写
    }

    // ===== 字段映射 + 富化 =====

    @Test
    @DisplayName("toRow：全字段映射 + UA 解析合并 props_json + 国家保留/兜底")
    void toRowMapsAndEnriches() {
        RawEvent r = validEvent();
        r.event_type = "progression";
        r.user_id = "u1";
        r.player_id = "p1";
        r.character_id = "c1";
        r.session_id = "s1";
        r.platform = "ios";
        r.app_version = "1.2.3";
        r.sdk_version = "0.9";
        r.country = "JP";
        r.user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        r.order_id = "o1";
        r.revenue_amount = 9.99;
        r.revenue_currency = "USD";
        r.virtual_amount = 5.5;
        r.props_json = "{\"lvl\":3}";
        r.experiments = Map.of("exp_1", "control");

        EventsEnrichJob.Enrichers enrichers = EventsEnrichJob.Enrichers.create("");
        EventsEnrichJob.EventRow row = EventsEnrichJob.toRow(r, enrichers);

        assertEquals("g", row.game_id);
        assertEquals("prod", row.environment);
        assertEquals("e1", row.event_id);
        assertEquals("progression", row.event_type);
        assertEquals("u1", row.user_id);
        assertEquals("d1", row.device_id);
        assertEquals("p1", row.player_id);
        assertEquals("JP", row.country);   // 已有国家保留（不做 IP 兜底）
        assertEquals(BigDecimal.valueOf(9.99d), row.revenue_amount);
        assertEquals("USD", row.revenue_currency);
        assertEquals(BigDecimal.valueOf(5.5d), row.virtual_amount);
        assertEquals("{'exp_1':'control'}", row.experiments);
        assertNotNull(row.ts_server);   // ts_server 缺失填 now
        assertEquals(row.ts_server, row.ts_client);   // ts_client 缺省回退 server
        // UA 真解析合并
        assertTrue(row.props_json.contains("\"lvl\":3"));
        assertTrue(row.props_json.contains("\"ua_family\":\"Chrome"));
        assertTrue(row.props_json.contains("\"os_family\":\"Windows"), row.props_json);
        assertTrue(row.props_json.contains("\"device_class\":\"Desktop\""));
    }

    @Test
    @DisplayName("toRow：国家缺失 + IP 存在但无 mmdb → 空国名；UA 空 → props 原样")
    void toRowCountryFallbackWithoutMmdb() {
        RawEvent r = validEvent();
        r.client_ip = "8.8.8.8";
        r.props_json = "{\"k\":\"v\"}";
        EventsEnrichJob.EventRow row = EventsEnrichJob.toRow(r, EventsEnrichJob.Enrichers.create(""));
        assertEquals("", row.country);   // 无 mmdb → null → 空
        assertEquals("{\"k\":\"v\"}", row.props_json);   // UA 空：不合并
    }

    @Test
    @DisplayName("Enrichers：UA 三族解析 + 空 UA/无 mmdb 分支")
    void enrichersBranches() {
        EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create("");
        assertNull(e.countryByIp("8.8.8.8"));   // 无 mmdb → null
        assertNull(e.uaFamily(null));
        assertNull(e.uaFamily(""));
        assertNull(e.osFamily(null));
        assertNull(e.deviceClass(""));

        String chrome = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
        assertEquals("Chrome", e.uaFamily(chrome));
        assertTrue(e.osFamily(chrome).startsWith("Mac OS"), e.osFamily(chrome));
        assertEquals("Desktop", e.deviceClass(chrome));
    }

    /** 构造带 isoCode 的 CityResponse（record 类提供无参构造）。 */
    private static com.maxmind.geoip2.model.CityResponse cityResponse(String isoCode) {
        com.maxmind.geoip2.record.Country country = isoCode == null
                ? new com.maxmind.geoip2.record.Country()
                : new com.maxmind.geoip2.record.Country(null, null, null, null, isoCode, null);
        return new com.maxmind.geoip2.model.CityResponse(
                null, null, country, null, null, null, null, null, null, null);
    }

    /** 注入 GeoIp2Provider 替身的 Enrichers。 */
    private static EventsEnrichJob.Enrichers enrichersWith(com.maxmind.geoip2.GeoIp2Provider provider) throws Exception {
        EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create("");
        Field f = EventsEnrichJob.Enrichers.class.getDeclaredField("geoip");
        f.setAccessible(true);
        f.set(e, provider);
        return e;
    }

    private static com.maxmind.geoip2.GeoIp2Provider providerReturning(
            java.util.function.Function<java.net.InetAddress, com.maxmind.geoip2.model.CityResponse> fn) {
        return (com.maxmind.geoip2.GeoIp2Provider) Proxy.newProxyInstance(
                EventsEnrichJobTest.class.getClassLoader(),
                new Class<?>[]{com.maxmind.geoip2.GeoIp2Provider.class},
                (Object p, Method m, Object[] a) -> fn.apply((java.net.InetAddress) a[0]));
    }

    /** 任意调用都抛 IOException 的替身（InvocationHandler 可抛受检异常，Proxy 原样传播）。 */
    private static com.maxmind.geoip2.GeoIp2Provider providerThrowing() {
        return (com.maxmind.geoip2.GeoIp2Provider) Proxy.newProxyInstance(
                EventsEnrichJobTest.class.getClassLoader(),
                new Class<?>[]{com.maxmind.geoip2.GeoIp2Provider.class},
                (Object p, Method m, Object[] a) -> {
                    throw new java.io.IOException("boom");
                });
    }

    @Test
    @DisplayName("countryByIp：命中 isoCode / isoCode 空 / 国家空 / 查询异常 四分支")
    void countryLookupBranches() throws Exception {
        EventsEnrichJob.Enrichers hit = enrichersWith(providerReturning(ip -> cityResponse("JP")));
        assertEquals("JP", hit.countryByIp("8.8.8.8"));

        EventsEnrichJob.Enrichers noIso = enrichersWith(providerReturning(ip -> cityResponse(null)));
        assertNull(noIso.countryByIp("8.8.8.8"));   // isoCode null → null

        EventsEnrichJob.Enrichers noCountry = enrichersWith(providerReturning(ip -> new com.maxmind.geoip2.model.CityResponse(
                null, null, null, null, null, null, null, null, null, null)));
        assertNull(noCountry.countryByIp("8.8.8.8"));   // country null → null

        EventsEnrichJob.Enrichers failing = enrichersWith(providerThrowing());
        assertNull(failing.countryByIp("8.8.8.8"));   // 查询异常 → null
    }

    @Test
    @DisplayName("ensureInit：损坏 mmdb 文件存在时构建失败回退 null（不抛出）")
    void ensureInitToleratesBrokenMmdb() throws Exception {
        java.nio.file.Path broken = java.nio.file.Files.createTempFile("broken", ".mmdb");
        java.nio.file.Files.write(broken, new byte[]{1, 2, 3, 4});   // 非 mmdb 格式
        try {
            EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create(broken.toString());
            assertNull(e.countryByIp("8.8.8.8"));   // 构建 IOException → geoip=null → null
            assertEquals("Chrome", e.uaFamily("Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36"));   // UA 不受影响
        } finally {
            java.nio.file.Files.deleteIfExists(broken);
        }
    }

    @Test
    @DisplayName("mergeProps：对象合入/已存在不覆盖/非对象与非法 JSON 原样/null 构造兜底")
    void mergePropsBranches() {
        assertEquals("{\"a\":1,\"b\":\"2\"}", EventsEnrichJob.mergeProps("{\"a\":1}", "b", "2"));
        assertEquals("{\"a\":1}", EventsEnrichJob.mergeProps("{\"a\":1}", "a", "2"));   // 已存在不覆盖
        assertEquals("[1,2]", EventsEnrichJob.mergeProps("[1,2]", "b", "2"));   // 非对象原样
        assertEquals("", EventsEnrichJob.mergeProps("", "b", "v\"x"));   // 空内容 → 非对象节点 → 原样返回
        assertEquals("bad json", EventsEnrichJob.mergeProps("bad json", "b", "2"));   // 解析异常 → 原样
        assertEquals("{\"k\":\"v\"}", EventsEnrichJob.mergeProps(null, "k", "v"));   // null → 构造最小对象
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindEvent：43 个参数按序绑定")
    void bindEventSetsAllParameters() throws Exception {
        EventsEnrichJob.EventRow r = new EventsEnrichJob.EventRow();
        r.game_id = "g";
        r.environment = "prod";
        r.ts_server = new Timestamp(1_000L);
        r.ts_client = new Timestamp(2_000L);
        r.event_id = "e1";
        r.event_type = "t";
        r.event_name = "n";
        r.user_id = "u";
        r.device_id = "d";
        r.player_id = "p";
        r.character_id = "c";
        r.session_id = "s";
        r.platform = "ios";
        r.app_version = "1";
        r.sdk_version = "2";
        r.country = "US";
        r.user_agent = "UA";
        r.server_id = "srv";
        r.guild_id = "gd";
        r.match_id = "m";
        r.level_id = "l";
        r.game_mode = "gm";
        r.difficulty = "df";
        r.progression_path = "pp";
        r.order_id = "o";
        r.product_id = "pr";
        r.revenue_amount = new BigDecimal("1.5");
        r.revenue_currency = "USD";
        r.receipt_hash = "rh";
        r.virtual_currency = "vc";
        r.virtual_amount = new BigDecimal("2.5");
        r.flow_type = "ft";
        r.item_id = "i";
        r.operation_id = "op";
        r.operation_type = "opt";
        r.resource_id = "ri";
        r.resource_amount = new BigDecimal("3.5");
        r.ad_network = "an";
        r.ad_placement = "ap";
        r.ad_format = "af";
        r.ad_impression_id = "ai";
        r.props_json = "{}";
        r.experiments = "{}";

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                EventsEnrichJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        EventsEnrichJob.bindEvent(ps, r);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals(new Timestamp(1_000L), calls.get("setTimestamp:3"));
        assertEquals(new Timestamp(2_000L), calls.get("setTimestamp:4"));
        assertEquals("e1", calls.get("setString:5"));
        assertEquals("n", calls.get("setString:7"));
        assertEquals(new BigDecimal("1.5"), calls.get("setBigDecimal:27"));
        assertEquals("USD", calls.get("setString:28"));
        assertEquals(new BigDecimal("2.5"), calls.get("setBigDecimal:31"));
        assertEquals(new BigDecimal("3.5"), calls.get("setBigDecimal:37"));
        assertEquals("{}", calls.get("setString:42"));
        assertEquals("{}", calls.get("setString:43"));
        assertEquals(43, calls.size());
    }

    @Test
    @DisplayName("实例化：覆盖隐式构造器")
    void instances() {
        assertNotNull(new EventsEnrichJob());
        assertNotNull(new EventsEnrichJob.EventRow());
    }


    // 模拟 oddsmaker-event.avsc 的字段形态：string 必填 + [null,long] / [null,double] 可选
    private static org.apache.avro.Schema eventSchema() {
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        var fields = new java.util.ArrayList<org.apache.avro.Schema.Field>();
        fields.add(new org.apache.avro.Schema.Field("event_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("game_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("ts_client",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.LONG))));
        fields.add(new org.apache.avro.Schema.Field("revenue_amount",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.DOUBLE))));
        schema.setFields(fields);
        return schema;
    }

    @Test
    void fromMissingFieldsYieldsNull() {
        var record = new org.apache.avro.generic.GenericData.Record(eventSchema());
        RawEvent e = RawEvent.from(record);
        assertNull(e.event_id);
        assertNull(e.ts_client);
        assertNull(e.revenue_amount);
        assertNull(e.props_json);
    }

    @Test
    void fromReadsKnownFieldsAndIgnoresUnknown() {
        var record = new org.apache.avro.generic.GenericData.Record(eventSchema());
        record.put("event_id", "evt_1");
        record.put("game_id", "g1");
        record.put("ts_client", 12345L);
        record.put("revenue_amount", 12.5);
        RawEvent e = RawEvent.from(record);
        assertEquals("evt_1", e.event_id);
        assertEquals("g1", e.game_id);
        assertEquals(12345L, e.ts_client);
        assertEquals(12.5, e.revenue_amount);
        assertNull(e.ts_server);
    }

    @Test
    void toDlqJsonReturnsValidJson() {
        // DLQ 最小载荷只含 event_id + reason（与 Gateway DlqPublisher 结构一致）
        RawEvent e = new RawEvent();
        e.event_id = "test_event_123";
        assertEquals("{\"event_id\":\"test_event_123\",\"reason\":\"invalid_schema\"}",
            RawEvent.toDlqJson(e, "invalid_schema"));
    }

    @Test
    void toDlqJsonHandlesNullEvent() {
        assertEquals("{\"event_id\":\"\",\"reason\":\"invalid_schema\"}",
            RawEvent.toDlqJson(null, "invalid_schema"));
    }

    private static org.apache.avro.Schema schemaWithExperiments() {
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        var fields = new java.util.ArrayList<org.apache.avro.Schema.Field>();
        fields.add(new org.apache.avro.Schema.Field("event_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("experiments",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.createMap(
                    org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)))));
        schema.setFields(fields);
        return schema;
    }

    @Test
    void mpReadsAvroMapAndSkipsNullEntries() {
        var record = new org.apache.avro.generic.GenericData.Record(schemaWithExperiments());
        var m = new java.util.HashMap<CharSequence, CharSequence>();
        m.put("exp_1", "control");
        m.put("exp_2", "treatment");
        m.put("exp_3", null);
        record.put("event_id", "evt_1");
        record.put("experiments", m);
        var parsed = RawEvent.mp(record, "experiments");
        assertEquals(java.util.Map.of("exp_1", "control", "exp_2", "treatment"), parsed);
    }

    @Test
    void mpYieldsNullOnMissingFieldOrEmptyMap() {
        // schema 不含 experiments 字段：不抛 AvroRuntimeException，返回 null
        var missing = new org.apache.avro.generic.GenericData.Record(eventSchema());
        missing.put("event_id", "evt_1");
        assertNull(RawEvent.mp(missing, "experiments"));

        var record = new org.apache.avro.generic.GenericData.Record(schemaWithExperiments());
        record.put("experiments", new java.util.HashMap<CharSequence, CharSequence>());
        assertNull(RawEvent.mp(record, "experiments"));
    }

    @Test
    void mapLiteralFormatsClickHouseMapSyntax() {
        assertEquals("{}", EventsEnrichJob.mapLiteral(null));
        assertEquals("{}", EventsEnrichJob.mapLiteral(java.util.Map.of()));
        assertEquals("{'exp_1':'control'}", EventsEnrichJob.mapLiteral(java.util.Map.of("exp_1", "control")));
        var ordered = new java.util.LinkedHashMap<String, String>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        assertEquals("{'a':'1','b':'2'}", EventsEnrichJob.mapLiteral(ordered));
    }

    @Test
    void mapLiteralEscapesQuotesAndBackslashesAndSkipsNulls() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("it's", "va\\lue");
        m.put("nullKey", null);
        m.put("k2", "v2");
        assertEquals("{'it\\'s':'va\\\\lue','k2':'v2'}", EventsEnrichJob.mapLiteral(m));
    }

    @Test
    void mapLiteralAllNullEntriesYieldsEmptyMap() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("a", null);
        assertEquals("{}", EventsEnrichJob.mapLiteral(m));
    }

    // ===== 覆盖缺口补充 =====

    @Test
    @DisplayName("from：数值字段为不可解析字符串时归 null")
    void fromNonNumericStringFieldsYieldNull() {
        var record = new org.apache.avro.generic.GenericData.Record(eventSchema());
        record.put("ts_client", "12x");
        record.put("revenue_amount", "abc");
        RawEvent e = RawEvent.from(record);
        assertNull(e.ts_client);
        assertNull(e.revenue_amount);
    }

    @Test
    @DisplayName("Enrichers：坏 mmdb 文件构建失败回退 geoip=null")
    void enrichersBrokenMmdbFallsBackToNull() throws Exception {
        java.nio.file.Path bad = java.nio.file.Files.createTempFile("bad", ".mmdb");
        java.nio.file.Files.writeString(bad, "not-a-mmdb");
        try {
            EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create(bad.toString());
            assertNull(e.countryByIp("1.2.3.4"));
        } finally {
            java.nio.file.Files.deleteIfExists(bad);
        }
    }

    @Test
    @DisplayName("Enrichers：UA 解析器抛错时兜底返回 null")
    void uaValueParseFailureReturnsNull() throws Exception {
        EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create("");
        assertNull(e.uaFamily(null));   // 先走一次真实初始化

        nl.basjes.parse.useragent.UserAgentAnalyzer broken =
            org.mockito.Mockito.mock(nl.basjes.parse.useragent.UserAgentAnalyzer.class);
        org.mockito.Mockito.when(broken.parse(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new IllegalStateException("boom"));
        Field f = EventsEnrichJob.Enrichers.class.getDeclaredField("uaa");
        f.setAccessible(true);
        f.set(e, broken);

        assertNull(e.uaFamily("Mozilla/5.0"));
        assertNull(e.osFamily("Mozilla/5.0"));
        assertNull(e.deviceClass("Mozilla/5.0"));
    }

    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<org.apache.flink.streaming.api.environment.StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class)) {
            org.apache.flink.streaming.api.environment.StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment::getExecutionEnvironment)
                .thenReturn(env);
            assertDoesNotThrow(() -> EventsEnrichJob.main(new String[0]));
        }
    }

    @Test
    @DisplayName("Enrichers：mmdb 路径存在时构建 DatabaseReader 并按库返回国家")
    void enrichersMmdbPathBuildsReader() throws Exception {
        java.nio.file.Path any = java.nio.file.Files.createTempFile("any", ".mmdb");
        java.nio.file.Files.writeString(any, "placeholder");
        try {
            // 显式 stub 链：builder.build() → reader.city(ip) → 响应含 isoCode
            com.maxmind.geoip2.record.Country country =
                org.mockito.Mockito.mock(com.maxmind.geoip2.record.Country.class);
            org.mockito.Mockito.when(country.getIsoCode()).thenReturn("JP");
            com.maxmind.geoip2.model.CityResponse resp =
                org.mockito.Mockito.mock(com.maxmind.geoip2.model.CityResponse.class);
            org.mockito.Mockito.when(resp.getCountry()).thenReturn(country);
            com.maxmind.geoip2.DatabaseReader reader =
                org.mockito.Mockito.mock(com.maxmind.geoip2.DatabaseReader.class);
            org.mockito.Mockito.when(reader.city(org.mockito.ArgumentMatchers.any(java.net.InetAddress.class)))
                .thenReturn(resp);

            try (org.mockito.MockedConstruction<com.maxmind.geoip2.DatabaseReader.Builder> mocked =
                     org.mockito.Mockito.mockConstruction(com.maxmind.geoip2.DatabaseReader.Builder.class,
                         (builder, context) -> org.mockito.Mockito.when(builder.build()).thenReturn(reader))) {
                EventsEnrichJob.Enrichers e = EventsEnrichJob.Enrichers.create(any.toString());
                assertEquals("JP", e.countryByIp("1.2.3.4"));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(any);
        }
    }
}
