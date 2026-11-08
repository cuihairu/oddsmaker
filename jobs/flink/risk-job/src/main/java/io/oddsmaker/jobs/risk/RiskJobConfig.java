package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;

import java.time.Duration;

/**
 * RiskJob 配置与入口辅助：程序参数解析、配置读取（System properties）、水位线与滑动步长。
 * 原内联于 RiskJob（拆分 LAY-001），现独立成类，单测经 RiskJob 薄委托面访问。
 */
final class RiskJobConfig {

    private RiskJobConfig() {}

    static final String JOB_NAME = "oddsmaker-risk-job";

    /**
     * --key=value 程序参数 → System properties（config() 只读系统属性）。
     * Flink REST /jars/{id}/run 的 programArgs 即走此途径；-D 系统属性途径不受影响，
     * 且程序参数优先级更高（后 setProperty 覆盖）。非 -- 前缀或无 = 的参数忽略。
     */
    static void parseArgs(String[] args) {
        if (args == null) return;
        for (String arg : args) {
            if (arg != null && arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 2) {
                    System.setProperty(arg.substring(2, eq), arg.substring(eq + 1));
                }
            }
        }
    }

    /** 配置读取（System properties，单测可 setProperty 后直测）。顺序见 buildPipeline。 */
    static Object[] config() {
        return new Object[]{
                System.getProperty("kafka.bootstrap", "localhost:9092"),
                System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2"),
                System.getProperty("kafka.topic", "oddsmaker.events_raw"),
                System.getProperty("risk.topic", "oddsmaker.risk_events"),
                System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default"),
                System.getProperty("clickhouse.user", "default"),
                System.getProperty("clickhouse.pass", ""),
                Integer.parseInt(System.getProperty("risk.frequency.window-minutes", "10")),
                System.getProperty("control.url", "http://localhost:8085"),
                System.getProperty("control.gameId", "default"),
                System.getProperty("control.token", ""),
                Long.parseLong(System.getProperty("rule.refresh-ms", "60000")),
                // B5 特征分支：risk_features 落 PostgreSQL（control 库）
                System.getProperty("control.db.url", "jdbc:postgresql://localhost:5432/oddsmaker"),
                System.getProperty("control.db.user", "oddsmaker"),
                System.getProperty("control.db.pass", "oddsmaker"),
                // 乱序容忍秒数：e2e 复现时置 0 可让窗口按事件时间即刻闭合
                Integer.parseInt(System.getProperty("risk.watermark.delay-seconds", "120")),
                // 特征分支总开关（关掉则既不产出 risk_features 也不评估 FEATURE 规则）
                Boolean.parseBoolean(System.getProperty("risk.features.enabled", "true")),
                // PATTERN 窗口不在此处：DEFAULTS/spec.windowSeconds 直接读 risk.pattern.window-minutes
        };
    }

    /** 有界乱序水位线（缺省 2 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return watermarks(120);
    }

    static WatermarkStrategy<RawEvent> watermarks(int delaySeconds) {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofSeconds(delaySeconds))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (r, recordTimestamp) -> {
                    Long s = r.ts_server;
                    Long c = r.ts_client;
                    long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });
    }

    /** 滑动窗口步长：窗口一半，最小 1 分钟。 */
    static int slideMinutes(int windowMin) {
        return windowMin / 2 > 0 ? windowMin / 2 : 1;
    }
}
