package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
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
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
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
    static final String JOB_NAME = "oddsmaker-configurable-funnels";

    public static void main(String[] args) throws Exception {
        String[] cfg = config();
        // 从控制面数据库加载漏斗配置；连不上/无配置时安全退出（不 execute）
        List<FunnelConfig> funnelConfigs = loadFromControlDb(cfg[6], cfg[7], cfg[8]);
        if (funnelConfigs.isEmpty()) {
            System.out.println("No funnel configurations found. Exiting.");
            return;
        }
        System.out.println("Loaded " + funnelConfigs.size() + " funnel configurations");
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), cfg, funnelConfigs).execute(JOB_NAME);
    }

    /** 配置读取（System properties，单测可 setProperty 后直测）。前 6 项同其他 job，后 3 项为控制面库。 */
    static String[] config() {
        return new String[]{
                System.getProperty("kafka.bootstrap", "localhost:9092"),
                System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2"),
                System.getProperty("kafka.topic", "oddsmaker.events_raw"),
                System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default"),
                System.getProperty("clickhouse.user", "default"),
                System.getProperty("clickhouse.pass", ""),
                System.getProperty("control.db.url", "jdbc:postgresql://localhost:5432/oddsmaker"),
                System.getProperty("control.db.user", "oddsmaker"),
                System.getProperty("control.db.pass", "oddsmaker"),
        };
    }

    /** 有界乱序水位线（10 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (element, recordTimestamp) -> {
                    Long tsServer = element.ts_server;
                    Long tsClient = element.ts_client;
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });
    }

    /**
     * 搭建可配置漏斗管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     * 每个启用的漏斗配置一条处理链路。
     */
    static StreamExecutionEnvironment buildPipeline(StreamExecutionEnvironment env, String[] cfg, List<FunnelConfig> funnelConfigs) {
        // 容错语义闭环:无 checkpoint 时 KafkaSource 从不提交 offset,重启后按回退策略重新初始化,
        // 停机窗口事件全部丢失且 keyed state 全空。启用 checkpoint 后 offset 随 checkpoint 提交、
        // state 持久恢复、重启从上次 checkpoint 续读(残余风险收敛为 JdbcSink at-least-once 的
        // 检查点间隔窗口内重复,处理层重复由去重/聚合 state 恢复挡住);
        // 回退 LATEST 保持既有首启动语义,避免已部署环境全量重放+state 空造成全量重复
        env.enableCheckpointing(30_000L, CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(10)));
        String bootstrap = cfg[0], registry = cfg[1], topic = cfg[2], chUrl = cfg[3], chUser = cfg[4], chPass = cfg[5];

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-configurable-funnels")
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> stream = env.fromSource(source, watermarks(), "events-raw");

        // 为每个漏斗配置创建处理链路
        for (FunnelConfig config : funnelConfigs) {
            if (!config.enabled) {
                System.out.println("Skipping disabled funnel: " + config.name);
                continue;
            }

            System.out.println("Processing funnel: " + config.name + " with " + config.steps.size() + " steps");

            // 过滤相关事件
            DataStream<RawEvent> filteredStream = stream.filter(stepFilter(config));

            // 按用户键分组并处理漏斗
            filteredStream
                .keyBy(ConfigurableFunnelsJob::funnelKey)
                .process(new ConfigurableFunnelProcess(config))
                .addSink(JdbcSink.sink(
                    "INSERT INTO funnels_configurable (game_id, environment, funnel_id, event_date, step, step_name, users, conversion_rate) VALUES (?,?,?,?,?,?,?,?)",
                    ConfigurableFunnelsJob::bindConfigurableRow,
                    JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                    new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
                ));
        }

        return env;
    }

    /** 步骤事件名清单。 */
    static List<String> stepEventNames(FunnelConfig config) {
        return config.steps.stream()
                .map(step -> step.eventName)
                .toList();
    }

    /** 事件名是否属于某漏斗的步骤事件。 */
    static boolean isStepEventIn(RawEvent r, List<String> stepEvents) {
        Object n = r.event_name;
        return n != null && stepEvents.contains(n.toString());
    }

    /** 过滤算子工厂（lambda 体可经返回值直调覆盖）。 */
    static org.apache.flink.api.common.functions.FilterFunction<RawEvent> stepFilter(FunnelConfig config) {
        List<String> names = stepEventNames(config);
        return r -> isStepEventIn(r, names);
    }

    /** 漏斗分组键：game|environment|uid。 */
    static String funnelKey(RawEvent r) {
        return r.game_id + "|" + r.environment + "|" + uidOf(r);
    }

    /** funnels_configurable 写入绑定。 */
    static void bindConfigurableRow(java.sql.PreparedStatement ps, FunnelRow row) throws java.sql.SQLException {
        ps.setString(1, row.gameId);
        ps.setString(2, row.environment);
        ps.setString(3, row.funnelId);
        ps.setDate(4, new java.sql.Date(row.eventDateEpochDay * 24 * 3600 * 1000));
        ps.setInt(5, row.step);
        ps.setString(6, row.stepName);
        ps.setLong(7, row.users);
        ps.setDouble(8, row.conversionRate);
    }

    /**
     * 打开控制面库连接并加载漏斗配置。驱动缺失/连接失败均安全返回空列表。
     */
    static List<FunnelConfig> loadFromControlDb(String dbUrl, String dbUser, String dbPass) {
        return loadFromControlDb(dbUrl, dbUser, dbPass, "org.postgresql.Driver");
    }

    static List<FunnelConfig> loadFromControlDb(String dbUrl, String dbUser, String dbPass, String driverClass) {
        // fatJar 合并依赖时 META-INF/services 可能被同名文件覆盖，DriverManager SPI
        // 注册不到 PG 驱动（"No suitable driver"），显式加载兜底
        if (!driverAvailable(driverClass)) {
            return new ArrayList<>();
        }
        return loadWithConnection(() -> DriverManager.getConnection(dbUrl, dbUser, dbPass));
    }

    /** 驱动类是否在 classpath（单测可传不存在的类名覆盖缺失分支）。 */
    static boolean driverAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC driver not on classpath: " + e.getMessage());
            return false;
        }
    }

    /** 连接供应商（允许抛受检异常，便于直接包裹 DriverManager.getConnection）。 */
    interface ConnectionSupplier {
        Connection get() throws Exception;
    }

    /** 打开连接并加载配置；获取/查询失败安全返回空列表。单测可注入 Connection 供应商直测成功路径。 */
    static List<FunnelConfig> loadWithConnection(ConnectionSupplier connectionSupplier) {
        List<FunnelConfig> configs = new ArrayList<>();
        try (Connection conn = connectionSupplier.get()) {
            configs = loadFunnelConfigs(conn);
        } catch (Exception e) {
            System.err.println("Failed to load funnel configs: " + e.getMessage());
        }
        return configs;
    }

    /**
     * 从控制面库连接读取启用的漏斗配置（含步骤）。
     */
    static List<FunnelConfig> loadFunnelConfigs(Connection conn) throws Exception {
        List<FunnelConfig> configs = new ArrayList<>();

        // 查询启用的漏斗配置——控制面控制台 CRUD 写 funnel_configs（V0.8.8 按实体建）：
        // enabled/deleted_at 表达启用态，type 是漏斗类型，time_window_sec 是总窗口秒数
        String sql = "SELECT f.id, f.game_id, f.name, f.type, f.user_key, f.time_window_sec " +
                    "FROM funnel_configs f " +
                    "WHERE f.enabled = TRUE AND f.deleted_at IS NULL";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                FunnelConfig config = new FunnelConfig();
                config.id = rs.getString("id");
                config.gameId = rs.getString("game_id");
                config.name = rs.getString("name");
                config.type = rs.getString("type");
                config.userKey = rs.getString("user_key");
                long window = rs.getLong("time_window_sec");
                config.timeWindowSec = rs.wasNull() || window <= 0 ? 24 * 3600 : window;
                config.enabled = true;

                // 加载漏斗步骤
                config.steps = loadFunnelSteps(conn, config.id);

                configs.add(config);
            }
        }

        return configs;
    }
    
    /**
     * 加载漏斗步骤
     */
    static List<FunnelStep> loadFunnelSteps(Connection conn, String funnelId) throws Exception {
        List<FunnelStep> steps = new ArrayList<>();
        
        // funnel_steps 与控制面 FunnelStepEntity 同表：funnel_id 外键、
        // time_window_sec（步间窗口秒数，空/0 回落漏斗总窗）、optional
        String sql = "SELECT id, step_order, name, event_name, event_filter, time_window_sec, optional " +
                    "FROM funnel_steps " +
                    "WHERE funnel_id = ? AND deleted_at IS NULL " +
                    "ORDER BY step_order ASC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, funnelId);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                FunnelStep step = new FunnelStep();
                step.id = rs.getString("id");
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
    // 闭包捕获 config 进算子，必须可序列化
    static class FunnelConfig implements java.io.Serializable {
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
    static class FunnelStep implements java.io.Serializable {
        String id; // control 迁移里 funnel_steps.id 是 VARCHAR(32)，getLong 会抛转换异常
        int stepOrder;
        String name;
        String eventName;
        String eventFilter;
        long timeWindowSec;
        boolean optional;
    }
    
    /**
     * 漏斗结果行。
     * public class + public fields：包私有类会被 Flink 退化成 Kryo 泛型序列化
     */
    public static class FunnelRow {
        public String gameId;
        public String environment;
        public String funnelId;
        public long eventDateEpochDay;
        public int step;
        public String stepName;
        public long users;
        public double conversionRate;
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
