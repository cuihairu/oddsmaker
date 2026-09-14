package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 可配置漏斗Flink作业
 * 支持多步骤漏斗分析，从数据库读取漏斗配置
 */
public class ConfigurableFunnelsJob {
    
    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String registry = System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
        String topic = System.getProperty("kafka.topic", "oddsmaker.events_raw");
        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");
        String controlDbUrl = System.getProperty("control.db.url", "jdbc:postgresql://localhost:5432/oddsmaker");
        String controlDbUser = System.getProperty("control.db.user", "oddsmaker");
        String controlDbPass = System.getProperty("control.db.pass", "oddsmaker");
        
        // 从控制面数据库加载漏斗配置
        List<FunnelConfig> funnelConfigs = loadFunnelConfigs(controlDbUrl, controlDbUser, controlDbPass);
        
        if (funnelConfigs.isEmpty()) {
            System.out.println("No funnel configurations found. Exiting.");
            return;
        }
        
        System.out.println("Loaded " + funnelConfigs.size() + " funnel configurations");
        
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-configurable-funnels")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();
        
        var wm = WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (element, recordTimestamp) -> {
                    Long tsServer = element.ts_server;
                    Long tsClient = element.ts_client;
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });
        
        DataStream<RawEvent> stream = env.fromSource(source, wm, "events-raw");
        
        // 为每个漏斗配置创建处理链路
        for (FunnelConfig config : funnelConfigs) {
            if (!config.enabled) {
                System.out.println("Skipping disabled funnel: " + config.name);
                continue;
            }
            
            System.out.println("Processing funnel: " + config.name + " with " + config.steps.size() + " steps");
            
            // 收集所有步骤的事件名称
            List<String> stepEvents = config.steps.stream()
                .map(step -> step.eventName)
                .toList();
            
            // 过滤相关事件
            DataStream<RawEvent> filteredStream = stream
                .filter(r -> {
                    Object n = r.event_name;
                    if (n == null) return false;
                    String ev = n.toString();
                    return stepEvents.contains(ev);
                });
            
            // 按用户键分组并处理漏斗
            filteredStream
                .keyBy(r -> (r.game_id + "|" + r.environment + "|" + uidOf(r)))
                .process(new ConfigurableFunnelProcess(config))
                .addSink(JdbcSink.sink(
                    "INSERT INTO funnels_configurable (game_id, environment, funnel_id, event_date, step, step_name, users, conversion_rate) VALUES (?,?,?,?,?,?,?,?)",
                    (ps, row) -> {
                        ps.setString(1, row.gameId);
                        ps.setString(2, row.environment);
                        ps.setString(3, row.funnelId);
                        ps.setDate(4, new java.sql.Date(row.eventDateEpochDay * 24 * 3600 * 1000));
                        ps.setInt(5, row.step);
                        ps.setString(6, row.stepName);
                        ps.setLong(7, row.users);
                        ps.setDouble(8, row.conversionRate);
                    },
                    JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                    new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
                ));
        }
        
        env.execute("oddsmaker-configurable-funnels");
    }
    
    /**
     * 从控制面数据库加载漏斗配置
     */
    private static List<FunnelConfig> loadFunnelConfigs(String dbUrl, String dbUser, String dbPass) {
        List<FunnelConfig> configs = new ArrayList<>();
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            // 查询启用的漏斗配置
            String sql = "SELECT f.id, f.game_id, f.name, f.type, f.user_key, f.time_window_sec " +
                        "FROM funnel_configs f " +
                        "WHERE f.enabled = true AND f.deleted_at IS NULL";
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                
                while (rs.next()) {
                    FunnelConfig config = new FunnelConfig();
                    config.id = rs.getString("id");
                    config.gameId = rs.getString("game_id");
                    config.name = rs.getString("name");
                    config.type = rs.getString("type");
                    config.userKey = rs.getString("user_key");
                    config.timeWindowSec = rs.getLong("time_window_sec");
                    config.enabled = true;
                    
                    // 加载漏斗步骤
                    config.steps = loadFunnelSteps(conn, config.id);
                    
                    configs.add(config);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load funnel configs: " + e.getMessage());
            e.printStackTrace();
        }
        
        return configs;
    }
    
    /**
     * 加载漏斗步骤
     */
    private static List<FunnelStep> loadFunnelSteps(Connection conn, String funnelId) throws Exception {
        List<FunnelStep> steps = new ArrayList<>();
        
        String sql = "SELECT id, step_order, name, event_name, event_filter, time_window_sec, optional " +
                    "FROM funnel_steps " +
                    "WHERE funnel_id = ? " +
                    "ORDER BY step_order ASC";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, funnelId);
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                FunnelStep step = new FunnelStep();
                step.id = rs.getLong("id");
                step.stepOrder = rs.getInt("step_order");
                step.name = rs.getString("name");
                step.eventName = rs.getString("event_name");
                step.eventFilter = rs.getString("event_filter");
                step.timeWindowSec = rs.getLong("time_window_sec");
                step.optional = rs.getBoolean("optional");
                
                steps.add(step);
            }
        }
        
        return steps;
    }
    
    /**
     * 获取用户标识
     */
    static String uidOf(RawEvent r) {
        Object u = r.user_id;
        if (u != null && !u.toString().isEmpty()) return u.toString();
        return String.valueOf(r.device_id);
    }
    
    /**
     * 漏斗配置
     */
    static class FunnelConfig {
        String id;
        String gameId;
        String name;
        String type;
        String userKey;
        long timeWindowSec;
        boolean enabled;
        List<FunnelStep> steps;
    }
    
    /**
     * 漏斗步骤
     */
    static class FunnelStep {
        long id;
        int stepOrder;
        String name;
        String eventName;
        String eventFilter;
        long timeWindowSec;
        boolean optional;
    }
    
    /**
     * 漏斗结果行
     */
    static class FunnelRow {
        String gameId;
        String environment;
        String funnelId;
        long eventDateEpochDay;
        int step;
        String stepName;
        long users;
        double conversionRate;
    }
    
    /**
     * 可配置漏斗处理函数
     */
    static class ConfigurableFunnelProcess extends KeyedProcessFunction<String, RawEvent, FunnelRow> {
        private final FunnelConfig config;
        private transient MapState<String, Long> state;

        ConfigurableFunnelProcess(FunnelConfig config) {
            this.config = config;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(40)).build();
            MapStateDescriptor<String, Long> desc = new MapStateDescriptor<>(
                "funnel_state_" + config.id,
                TypeInformation.of(String.class),
                TypeInformation.of(Long.class)
            );
            desc.enableTimeToLive(ttl);
            state = getRuntimeContext().getMapState(desc);
        }

        @Override
        public void processElement(RawEvent value, Context ctx, Collector<FunnelRow> out) throws Exception {
            String gameId = value.game_id.toString();
            String environment = value.environment.toString();
            String eventName = value.event_name.toString();
            long ts = tsMs(value);
            long day = ts / 86_400_000L;

            int currentStepIndex = stepIndexOf(eventName);
            if (currentStepIndex < 0) {
                return;
            }
            FunnelStep currentStep = config.steps.get(currentStepIndex);

            // STANDARD / UNORDERED：任意顺序完成全部步骤即转化（时间跨度约束）
            if (isUnordered()) {
                processUnordered(gameId, environment, currentStepIndex, currentStep, ts, day, out);
                return;
            }

            // SEQUENTIAL / TIME_WINDOW：按步骤顺序推进
            processSequential(gameId, environment, currentStepIndex, currentStep, ts, day, out);
        }

        private boolean isUnordered() {
            String t = config.type == null ? "" : config.type.toUpperCase();
            return "STANDARD".equals(t) || "UNORDERED".equals(t);
        }

        /**
         * 无序漏斗：记录每步首次完成时间；全部完成且跨度 <= 窗口 -> 每步计数一次（converted 去重）；
         * 跨度超窗 -> 重置状态，当前事件作为新一轮起点。
         */
        private void processUnordered(String gameId, String environment, int stepIndex, FunnelStep step,
                                      long ts, long day, Collector<FunnelRow> out) throws Exception {
            if (state.get("converted") != null) {
                return; // 每用户只计一次转化
            }

            String stepKey = "u_step_" + stepIndex + "_ts";
            if (state.get(stepKey) == null) {
                state.put(stepKey, ts);
                emitStep(gameId, environment, stepIndex, step, day, out);
            }

            java.util.Map<Integer, Long> stepFirstTs = new java.util.HashMap<>();
            for (int i = 0; i < config.steps.size(); i++) {
                Long v = state.get("u_step_" + i + "_ts");
                if (v != null) stepFirstTs.put(i, v);
            }

            long windowMs = funnelWindowMs();
            if (UnorderedFunnelLogic.allStepsDone(config.steps.size(), stepFirstTs)) {
                if (UnorderedFunnelLogic.spanMs(stepFirstTs) <= windowMs) {
                    state.put("converted", 1L);
                } else {
                    // 超窗重置：当前事件作为新一轮起点
                    for (int i = 0; i < config.steps.size(); i++) {
                        state.remove("u_step_" + i + "_ts");
                    }
                    state.put("u_step_" + stepIndex + "_ts", ts);
                }
            }
        }

        /**
         * 顺序漏斗（原逻辑）：按步骤推进 + 步骤级时间窗。
         */
        private void processSequential(String gameId, String environment, int stepIndex, FunnelStep step,
                                       long ts, long day, Collector<FunnelRow> out) throws Exception {
            if (stepIndex == 0) {
                String key = "started_day_" + day;
                if (state.get(key) == null) {
                    state.put(key, 1L);
                    emitStep(gameId, environment, stepIndex, step, day, out);
                }
                state.put("step_0_ts", ts);
                return;
            }

            int prevStepIndex = stepIndex - 1;
            Long prevTs = state.get("step_" + prevStepIndex + "_ts");
            if (prevTs != null && ts - prevTs <= stepWindowMs(step)) {
                String key = "step_" + stepIndex + "_day_" + day;
                if (state.get(key) == null) {
                    state.put(key, 1L);
                    emitStep(gameId, environment, stepIndex, step, day, out);
                }
                state.put("step_" + stepIndex + "_ts", ts);
            } else if (step.optional) {
                for (int i = prevStepIndex - 1; i >= 0; i--) {
                    Long prevPrevTs = state.get("step_" + i + "_ts");
                    if (prevPrevTs != null && ts - prevPrevTs <= stepWindowMs(step)) {
                        String key = "step_" + stepIndex + "_day_" + day;
                        if (state.get(key) == null) {
                            state.put(key, 1L);
                            emitStep(gameId, environment, stepIndex, step, day, out);
                        }
                        state.put("step_" + stepIndex + "_ts", ts);
                        break;
                    }
                }
            }
        }

        private void emitStep(String gameId, String environment, int stepIndex, FunnelStep step,
                              long day, Collector<FunnelRow> out) {
            FunnelRow row = new FunnelRow();
            row.gameId = gameId;
            row.environment = environment;
            row.funnelId = config.id;
            row.eventDateEpochDay = day;
            row.step = stepIndex + 1;
            row.stepName = step.name;
            row.users = 1;
            // 行级恒为 1；真实转化率由 ClickHouse SummingMergeTree 汇总后按步计算
            row.conversionRate = 100.0;
            out.collect(row);
        }

        private int stepIndexOf(String eventName) {
            for (int i = 0; i < config.steps.size(); i++) {
                if (config.steps.get(i).eventName.equals(eventName)) {
                    return i;
                }
            }
            return -1;
        }

        private long stepWindowMs(FunnelStep step) {
            long windowSec = step.timeWindowSec > 0
                ? step.timeWindowSec
                : (config.timeWindowSec > 0 ? config.timeWindowSec : 24 * 3600);
            return windowSec * 1000;
        }

        private long funnelWindowMs() {
            long windowSec = config.timeWindowSec > 0 ? config.timeWindowSec : 24 * 3600;
            return windowSec * 1000;
        }

        private long tsMs(RawEvent r) {
            Long tsServer = r.ts_server;
            Long tsClient = r.ts_client;
            long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
            return micros / 1000L;
        }
    }
}
