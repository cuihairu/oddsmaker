package io.oddsmaker.control.service;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Agent 属性面键规范（纯函数层，AgentConfigSpecTest 直测）。
 *
 * 键名沿用 agents/dimension-sync-agent 的 agent.properties dot-key 命名；分三组：
 * - 非密钥属性键：进 props 文本（管理面可见）；
 * - 密钥键：只进 secrets 映射，整体 AES-GCM 密文托管，管理面响应永不回显值；
 * - 注入键：game.id / game.environment / status.source-key 由行 (game_id, environment, agent_key)
 *   在下发时注入，agent.checkpoint-path 是游戏方本地路径永远本地保留——props 里出现即拒绝。
 *
 * 校验口径与 AgentConfig.validate 一致地轻：只做键白名单 + 值形状（枚举/正整数），跨字段
 * 组合校验（如 SASL_* 必带机制与凭证）由 Agent 启动 fail-fast 兜底。
 */
public final class AgentConfigSpec {

    private AgentConfigSpec() {
    }

    /** 非密钥属性键（props 文本白名单，TreeMap 序展示） */
    public static final Set<String> PROP_KEYS = Set.of(
            "gateway.endpoint",
            "dimension.type",
            "source.type",
            "source.jdbc.url", "source.jdbc.user", "source.jdbc.query",
            "source.jdbc.cursor-column", "source.jdbc.cursor-initial",
            "source.csv.dir", "source.excel.dir",
            "source.kafka.bootstrap-servers", "source.kafka.topic", "source.kafka.group-id",
            "source.kafka.poll-timeout-ms", "source.kafka.cursor-initial",
            "source.kafka.security-protocol", "source.kafka.sasl-mechanism", "source.kafka.username",
            "source.kafka.ssl-truststore-path", "source.kafka.ssl-truststore-type",
            "source.kafka.ssl-keystore-path", "source.kafka.ssl-keystore-type",
            "agent.batch-size", "agent.poll-seconds",
            "status.url");

    /** 密钥键（只能进 secrets 映射，密文托管） */
    public static final Set<String> SECRET_KEYS = Set.of(
            "gateway.api-key",
            "status.token",
            "source.jdbc.password",
            "source.kafka.password",
            "source.kafka.ssl-truststore-password",
            "source.kafka.ssl-keystore-password");

    /** 下发注入/本地保留键（props 里出现即拒绝） */
    public static final Set<String> INJECTED_KEYS = Set.of(
            "game.id", "game.environment", "status.source-key", "agent.checkpoint-path");

    private static final Set<String> SOURCE_TYPES = Set.of("mysql", "postgres", "csv", "excel", "kafka");
    private static final Set<String> KAFKA_PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Set<String> KAFKA_SASL_MECHS = Set.of("SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final Set<String> STORE_TYPES = Set.of("JKS", "PKCS12");

    /** 解析 properties 文本为有序映射（load 语义：# 注释跳过、\ 续行、首分隔符切键值）。 */
    public static TreeMap<String, String> parseProps(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("props 不能为空");
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalArgumentException("props 不是合法的 properties 文本: " + e.getMessage());
        }
        TreeMap<String, String> m = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            m.put(key, p.getProperty(key));
        }
        if (m.isEmpty()) {
            throw new IllegalArgumentException("props 里没有任何配置键");
        }
        return m;
    }

    /**
     * 建档/整体轮换前校验：props 与 secrets 拆分正确 + 必填在位 + 值形状合法。
     * 消息全部点出具体键与允许集，控制台可直接透出。
     */
    public static void validate(TreeMap<String, String> props, TreeMap<String, String> secrets) {
        validateProps(props);
        validateSecrets(secrets);
    }

    /** props 侧单边校验（props 全量替换而密钥侧不动时用）。 */
    public static void validateProps(TreeMap<String, String> props) {
        for (String key : props.keySet()) {
            if (SECRET_KEYS.contains(key)) {
                throw new IllegalArgumentException("props 存在密钥键 " + key + "，请写进 secrets（props 里不落密钥）");
            }
            if (INJECTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("props 存在键 " + key
                        + "：game.id/game.environment/status.source-key 由 Control 下发时自动注入，"
                        + "agent.checkpoint-path 属游戏方本地路径——均不在托管面内");
            }
            if (!PROP_KEYS.contains(key)) {
                throw new IllegalArgumentException("props 存在未知键 " + key + "（允许: " + sorted(PROP_KEYS) + "）");
            }
        }
        requireValue(props, "gateway.endpoint");
        requireValue(props, "source.type");
        String sourceType = props.get("source.type");
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("source.type 仅支持 mysql/postgres/csv/excel/kafka，当前: " + sourceType);
        }
        requireHttpUrl(props.get("gateway.endpoint"), "gateway.endpoint");
        enumOf(props, "source.kafka.security-protocol", KAFKA_PROTOCOLS);
        enumOf(props, "source.kafka.sasl-mechanism", KAFKA_SASL_MECHS);
        enumOf(props, "source.kafka.ssl-truststore-type", STORE_TYPES);
        enumOf(props, "source.kafka.ssl-keystore-type", STORE_TYPES);
        positiveInt(props, "agent.batch-size");
        positiveInt(props, "agent.poll-seconds");
        positiveInt(props, "source.kafka.poll-timeout-ms");
    }

    /** secrets 侧单边校验（整体轮换时与 validateProps 同跑）。 */
    public static void validateSecrets(TreeMap<String, String> secrets) {
        for (String key : secrets.keySet()) {
            if (!SECRET_KEYS.contains(key)) {
                throw new IllegalArgumentException("secrets 存在未知键 " + key
                        + "（允许: " + sorted(SECRET_KEYS) + "；非密钥键请写进 props）");
            }
        }
        requireValue(secrets, "gateway.api-key");
    }

    /**
     * 下发文本：注入行 + props 行 + secrets 行（均 TreeMap 序），java.util.Properties.load 可直接读。
     * 值内换行/反斜杠/前导空白转义保证 load→原文往返。
     */
    public static String renderResolved(String gameId, String environment, String agentKey,
                                        Map<String, String> props, Map<String, String> secrets) {
        TreeMap<String, String> all = new TreeMap<>();
        all.put("game.id", gameId);
        all.put("game.environment", environment);
        all.put("status.source-key", agentKey);
        all.putAll(props);
        all.putAll(secrets);
        return all.entrySet().stream()
                .map(e -> e.getKey() + "=" + renderValue(e.getValue()))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /** 值转义（包内共享：props 规范化存储同规则）——反斜杠/换行/前导空白保证 load 往返。 */
    static String renderValue(String v) {
        if (v == null) {
            return "";
        }
        String out = v.replace("\\", "\\\\").replace("\n", "\\n");
        return out.startsWith(" ") ? "\\" + out : out;
    }

    private static void requireValue(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填配置: " + key);
        }
    }

    private static void requireHttpUrl(String v, String key) {
        String lower = v.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(key + " 必须以 http:// 或 https:// 开头");
        }
    }

    private static void enumOf(Map<String, String> m, String key, Set<String> allowed) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            return;
        }
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(key + " 仅支持 " + String.join("/", allowed.stream().sorted().toList())
                    + "，当前: " + v);
        }
    }

    private static void positiveInt(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            return;
        }
        try {
            int i = Integer.parseInt(v.trim());
            if (i <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为正整数，当前: " + v);
        }
    }

    private static String sorted(Set<String> keys) {
        return keys.stream().sorted().collect(Collectors.joining(", "));
    }
}
