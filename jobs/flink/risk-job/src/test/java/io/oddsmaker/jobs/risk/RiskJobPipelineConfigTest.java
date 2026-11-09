package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：管道搭建惰性整图接线（本地环境不 execute）、配置读取与默认值、parseArgs 落 System.setProperty、str/nz/parseAmount 等杂项 helper。
 */
class RiskJobPipelineConfigTest {

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→map→七类规则→union→双 sink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        DataStream<RiskJob.RiskHit> tail = RiskJob.buildPipeline(env, RiskJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 10, "expect >= 10 transformations, got " + env.getTransformations().size());
        // 容错三件套生效:无 checkpoint 时 Kafka offset 从不提交,重启丢停机窗口数据(state 全空)
        assertTrue(env.getCheckpointConfig().isCheckpointingEnabled());
        assertEquals(30_000L, env.getCheckpointConfig().getCheckpointInterval());
        assertEquals(CheckpointingMode.AT_LEAST_ONCE, env.getCheckpointConfig().getCheckpointingMode());
        assertTrue(env.getRestartStrategy() instanceof RestartStrategies.FixedDelayRestartStrategyConfiguration,
            "重启策略应为固定延迟自愈");
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        Object[] cfg = RiskJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", cfg[1]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("oddsmaker.risk_events", cfg[3]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", cfg[4]);
        assertEquals("default", cfg[5]);
        assertEquals("", cfg[6]);
        assertEquals(10, cfg[7]);
        assertEquals("http://localhost:8085", cfg[8]);
        assertEquals("default", cfg[9]);
        assertEquals("", cfg[10]);
        assertEquals(60000L, cfg[11]);
        assertEquals("oddsmaker-risk-job", RiskJob.JOB_NAME);

        System.setProperty("risk.frequency.window-minutes", "5");
        System.setProperty("control.token", "tk-1");
        System.setProperty("rule.refresh-ms", "123");
        try {
            Object[] c = RiskJob.config();
            assertEquals(5, c[7]);
            assertEquals("tk-1", c[10]);
            assertEquals(123L, c[11]);
        } finally {
            System.clearProperty("risk.frequency.window-minutes");
            System.clearProperty("control.token");
            System.clearProperty("rule.refresh-ms");
        }
    }

    @Test
    @DisplayName("parseArgs：--key=value 落系统属性（覆盖优先）、非法参数忽略、null 安全")
    void parseArgsAppliesSystemProperties() {
        try {
            // 基本落位（Flink REST programArgs 途径）
            RiskJob.parseArgs(new String[]{"--control.url=http://control:8085", "--control.gameId=g1"});
            assertEquals("http://control:8085", System.getProperty("control.url"));
            assertEquals("g1", System.getProperty("control.gameId"));

            // 程序参数覆盖 -D 已有系统属性（setProperty 后到者胜）
            System.setProperty("kafka.bootstrap", "from-D:9092");
            RiskJob.parseArgs(new String[]{"--kafka.bootstrap=from-args:9092"});
            assertEquals("from-args:9092", System.getProperty("kafka.bootstrap"));

            // 非 -- 前缀 / 缺 = / "--" 裸前缀均忽略，合法参数在混排中仍生效
            RiskJob.parseArgs(new String[]{"positional", "--noeq", "--", "--testarg.onlyvalid=1"});
            assertEquals("1", System.getProperty("testarg.onlyvalid"));

            RiskJob.parseArgs(null);
        } finally {
            System.clearProperty("control.url");
            System.clearProperty("control.gameId");
            System.clearProperty("kafka.bootstrap");
            System.clearProperty("testarg.onlyvalid");
        }
    }

    @Test
    @DisplayName("watermarks/slideMinutes/subject*/str/nz/口径过滤：构件直测")
    void helpers() {
        WatermarkStrategy<RawEvent> wm = RiskJob.watermarks();
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

        assertEquals(5, RiskJob.slideMinutes(10));
        assertEquals(1, RiskJob.slideMinutes(1));
        assertEquals(1, RiskJob.slideMinutes(2));
        assertEquals(1, RiskJob.slideMinutes(3));   // 3/2 整除为 1
        assertEquals(2, RiskJob.slideMinutes(4));
        assertEquals(2, RiskJob.slideMinutes(5));

        RiskJob.RiskInput player = new RiskJob.RiskInput();
        player.userId = "u1";
        player.deviceId = "d1";
        assertEquals("PLAYER", RiskJob.subjectType(player));
        assertEquals("u1", RiskJob.subjectId(player));
        assertEquals("PLAYER:u1", RiskJob.subjectKey(player));

        RiskJob.RiskInput device = new RiskJob.RiskInput();
        device.userId = "";
        device.deviceId = "d1";
        assertEquals("DEVICE", RiskJob.subjectType(device));
        assertEquals("d1", RiskJob.subjectId(device));

        RiskJob.RiskInput keyed = new RiskJob.RiskInput();
        keyed.gameId = "g";
        keyed.environment = "prod";
        keyed.userId = "u1";
        keyed.deviceId = "d1";
        keyed.receiptKey = "rc_1";
        assertEquals("1:g4:prod9:PLAYER:u1", RiskJob.subjectWindowKey(keyed));
        assertEquals("1:g4:prod9:PLAYER:u14:rc_1", RiskJob.receiptWindowKey(keyed));

        assertNull(RiskJob.str(null));
        assertEquals("7", RiskJob.str(7));
        assertEquals("", RiskJob.nz(null));
        assertEquals("v", RiskJob.nz("v"));

        RiskJob.RiskInput amt = new RiskJob.RiskInput();
        amt.amount = new BigDecimal("50");
        assertTrue(RiskJob.hasAmount(amt));
        amt.amount = null;
        assertFalse(RiskJob.hasAmount(amt));
        amt.amount = BigDecimal.ZERO;
        assertFalse(RiskJob.hasAmount(amt));
        amt.amount = new BigDecimal("-1");
        assertFalse(RiskJob.hasAmount(amt));

        RiskJob.RiskInput flow = new RiskJob.RiskInput();
        flow.amount = new BigDecimal("10");
        flow.flowType = "source";
        assertTrue(RiskJob.isFlowAmount(flow));
        flow.flowType = "sink";
        assertTrue(RiskJob.isFlowAmount(flow));
        flow.flowType = "other";
        assertFalse(RiskJob.isFlowAmount(flow));
        flow.amount = null;
        assertFalse(RiskJob.isFlowAmount(flow));

        RiskJob.RiskInput receipt = new RiskJob.RiskInput();
        assertFalse(RiskJob.hasReceipt(receipt));
        receipt.receiptKey = "";
        assertFalse(RiskJob.hasReceipt(receipt));
        receipt.receiptKey = "rc";
        assertTrue(RiskJob.hasReceipt(receipt));
    }

}
