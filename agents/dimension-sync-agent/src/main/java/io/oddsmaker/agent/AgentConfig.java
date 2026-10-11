package io.oddsmaker.agent;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent 配置：properties 文件（--config= 指定，默认 ./agent.properties），
 * 同名 System property 可逐键覆盖（便于密钥不落盘：-Dgateway.api-key=...）。
 * 配置源优先级（低→高）：本地文件 &lt; Control 托管下发 &lt; System property。
 * Control 托管开关是本地键 control.config-url（托管面不托管该键，自指循环）；拉取失败
 * 整段回落本地文件，Control 不可达不阻断启动。配 control.config-refresh-seconds（&gt;0）
 * 后 AgentMain 每 N 秒重拉一次托管配置，原文有变即换源/换通道（checkpoint 不动，断点续传）。
 */
public final class AgentConfig {

    // 推送目标（Gateway /v1/batch）
    public String gatewayEndpoint;
    public String gatewayApiKey;
    // 维度归属
    public String gameId;
    public String environment;
    // Agent 行为
    public int batchSize = 500;
    public int pollSeconds = 300;
    public String checkpointPath = "./checkpoint.json";
    public String dimType = "item";
    // 数据源（mysql / postgres / csv 三选一，单进程单源）
    public String sourceType;
    public String jdbcUrl;
    public String jdbcUser;
    public String jdbcPassword;
    /** 增量查询，必须含且仅含一个 ? 占位（cursor 条件），并以 ORDER BY <cursor_column> 结尾 */
    public String jdbcQuery;
    public String cursorColumn;
    /** 首次运行的起始水位（epoch millis / ISO 时间 / 原始字符串），空则从 cursorColumn 最小值起 */
    public String cursorInitial;
    public String csvDir;
    public String excelDir;
    // kafka 源（消息体 = JSON 对象，字段语义同控制列；位点 checkpoint 自管，不依赖 group offset）
    public String kafkaBootstrap;
    public String kafkaTopic;
    public String kafkaGroupId = "oddsmaker-dimension-sync";
    public long kafkaPollTimeoutMs = 3000;
    /** 起始位点（"0=42;1=57" 或 earliest/latest），缺省 earliest */
    public String kafkaCursorInitial;
    // kafka 鉴权（可选）：PLAINTEXT（默认明文）/ SSL（证书，不走账号）/ SASL_PLAINTEXT / SASL_SSL
    public String kafkaSecurityProtocol = "PLAINTEXT";
    /** SASL 机制：仅支持 SCRAM-SHA-256 / SCRAM-SHA-512（SASL_* 协议必填） */
    public String kafkaSaslMechanism;
    public String kafkaUsername;
    public String kafkaPassword;
    // SSL 证书模式（SSL / SASL_SSL 协议）的客户端信任库——自签或私有 CA 场景必填，
    // 缺省时走 JVM 默认 truststore（cacerts）；path 配了 password 可空（允许空密码信任库）
    public String kafkaSslTruststorePath;
    public String kafkaSslTruststorePassword = "";
    /** 信任库类型：JKS / PKCS12（缺省 JKS，与 kafka-clients 默认一致） */
    public String kafkaSslTruststoreType = "JKS";
    // mTLS（broker 要求客户端证书，ssl.client.auth=required）时的客户端证书库——
    // 普通 SSL/SASL_SSL 单向认证可不配；私钥口令复用 keystore 口令（PKCS12 单一口令语义，
    // JKS 库密/钥密分设场景不在本配置面内）
    public String kafkaSslKeystorePath;
    public String kafkaSslKeystorePassword = "";
    /** 证书库类型：JKS / PKCS12（缺省 JKS，与 kafka-clients 默认一致） */
    public String kafkaSslKeystoreType = "JKS";
    // 同步状态上报（Control /api/dimensions/sync-status；url 空则跳过）
    public String statusUrl;
    public String statusToken;
    public String sourceKey;
    // Control 托管配置开关（本地键，永不下发托管面）：启动拉取地址 + 热更新轮询间隔秒数
    // （0/缺省=关闭，仅启动拉取一次；>0 须配合 control.config-url 使用，启动即校验）
    public String configUrl;
    public int configRefreshSeconds = 0;
    /** 启动/热更新实际应用的 Control 下发原文（未启用托管或拉取失败为 null）；热更新以它为变更基线。 */
    String appliedRemoteText;

    public static AgentConfig load(String[] args) throws IOException {
        return load(args, new ControlConfigFetcher());
    }

    /** 测试用重载：注入 fetcher 以便以本地 mock HTTP 服务验控制面合并臂。 */
    static AgentConfig load(String[] args, ControlConfigFetcher fetcher) throws IOException {
        return load(args, fetcher, null);
    }

    /**
     * 测试用重载：remoteOverride 非空时跳过 Control 拉取直接以该文本模拟已拉取内容
     * （热更新路径同样走此形态——AgentMain 用新拉出的文本重建配置）。
     */
    static AgentConfig load(String[] args, ControlConfigFetcher fetcher, String remoteOverride) throws IOException {
        String path = "./agent.properties";
        for (String a : args) {
            if (a.startsWith("--config=")) {
                path = a.substring("--config=".length());
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String appliedRemote = mergeControlConfig(p, fetcher, remoteOverride);
        // System property 逐键覆盖文件与 Control 下发值（本地显式覆盖最优先）
        for (String key : p.stringPropertyNames()) {
            String override = System.getProperty(key);
            if (override != null) {
                p.setProperty(key, override);
            }
        }
        AgentConfig c = new AgentConfig();
        c.appliedRemoteText = appliedRemote;
        c.gatewayEndpoint = trim(p.getProperty("gateway.endpoint"));
        c.gatewayApiKey = trim(p.getProperty("gateway.api-key"));
        c.gameId = trim(p.getProperty("game.id"));
        c.environment = trim(p.getProperty("game.environment"));
        c.batchSize = intOf(p.getProperty("agent.batch-size"), 500);
        c.pollSeconds = intOf(p.getProperty("agent.poll-seconds"), 300);
        c.checkpointPath = orDefault(p.getProperty("agent.checkpoint-path"), "./checkpoint.json");
        c.dimType = orDefault(p.getProperty("dimension.type"), "item");
        c.sourceType = trim(p.getProperty("source.type"));
        c.jdbcUrl = trim(p.getProperty("source.jdbc.url"));
        c.jdbcUser = trim(p.getProperty("source.jdbc.user"));
        c.jdbcPassword = p.getProperty("source.jdbc.password", "");
        c.jdbcQuery = normalizeSql(p.getProperty("source.jdbc.query"));
        c.cursorColumn = trim(p.getProperty("source.jdbc.cursor-column"));
        c.cursorInitial = trim(p.getProperty("source.jdbc.cursor-initial"));
        c.csvDir = trim(p.getProperty("source.csv.dir"));
        c.excelDir = trim(p.getProperty("source.excel.dir"));
        c.kafkaBootstrap = trim(p.getProperty("source.kafka.bootstrap-servers"));
        c.kafkaTopic = trim(p.getProperty("source.kafka.topic"));
        c.kafkaGroupId = orDefault(p.getProperty("source.kafka.group-id"), "oddsmaker-dimension-sync");
        c.kafkaPollTimeoutMs = longOf(p.getProperty("source.kafka.poll-timeout-ms"), 3000);
        c.kafkaCursorInitial = trim(p.getProperty("source.kafka.cursor-initial"));
        c.kafkaSecurityProtocol = orDefault(p.getProperty("source.kafka.security-protocol"), "PLAINTEXT");
        c.kafkaSaslMechanism = trim(p.getProperty("source.kafka.sasl-mechanism"));
        c.kafkaUsername = trim(p.getProperty("source.kafka.username"));
        c.kafkaPassword = p.getProperty("source.kafka.password", "");
        c.kafkaSslTruststorePath = trim(p.getProperty("source.kafka.ssl-truststore-path"));
        c.kafkaSslTruststorePassword = p.getProperty("source.kafka.ssl-truststore-password", "");
        c.kafkaSslTruststoreType = orDefault(p.getProperty("source.kafka.ssl-truststore-type"), "JKS");
        c.kafkaSslKeystorePath = trim(p.getProperty("source.kafka.ssl-keystore-path"));
        c.kafkaSslKeystorePassword = p.getProperty("source.kafka.ssl-keystore-password", "");
        c.kafkaSslKeystoreType = orDefault(p.getProperty("source.kafka.ssl-keystore-type"), "JKS");
        c.statusUrl = trim(p.getProperty("status.url"));
        c.statusToken = trim(p.getProperty("status.token"));
        c.sourceKey = trim(p.getProperty("status.source-key"));
        c.configUrl = trim(p.getProperty("control.config-url"));
        c.configRefreshSeconds = intOf(p.getProperty("control.config-refresh-seconds"), 0);
        return c;
    }

    /**
     * Control 托管合并：control.config-url 配了才拉（查询身份取本地 game.id / game.environment /
     * status.source-key，鉴权复用 status.token）；下发的键逐键覆盖本地文件值，任何失败（网络/
     * 非 2xx/空下发）只打日志整段回落本地文件——Control 不可达不阻断启动。
     * remoteOverride 非空时跳过拉取直接以该文本模拟已拉取内容（热更新路径）。
     *
     * @return 实际应用的 Control 下发原文（未启用/拉取失败/空下发为 null）
     */
    private static String mergeControlConfig(Properties p, ControlConfigFetcher fetcher, String remoteOverride) {
        String configUrl = p.getProperty("control.config-url");
        if (configUrl == null || configUrl.isBlank()) {
            return null;
        }
        try {
            String text = remoteOverride != null ? remoteOverride : fetcher.fetch(configUrl,
                    trim(p.getProperty("game.id")),
                    trim(p.getProperty("game.environment")),
                    trim(p.getProperty("status.source-key")),
                    p.getProperty("status.token"));
            Properties remote = new Properties();
            remote.load(new StringReader(text));
            if (remote.isEmpty()) {
                System.err.println("[config] Control 下发配置为空，回落本地 agent.properties");
                return null;
            }
            for (String key : remote.stringPropertyNames()) {
                p.setProperty(key, remote.getProperty(key));
            }
            System.out.println("[config] 已从 Control 拉取 " + remote.size() + " 键并覆盖本地同名值");
            return text;
        } catch (Exception e) {
            System.err.println("[config] Control 托管配置拉取失败，回落本地 agent.properties: " + e.getMessage());
            return null;
        }
    }

    /** 按 source 类型校验，配置错误 fail-fast（启动即抛，不进轮询循环）。 */
    public void validate() {
        require(gatewayEndpoint, "gateway.endpoint");
        require(gatewayApiKey, "gateway.api-key");
        require(gameId, "game.id");
        require(environment, "game.environment");
        require(sourceType, "source.type");
        if (batchSize <= 0) {            throw new IllegalArgumentException("agent.batch-size 必须为正数");
        }
        if (pollSeconds <= 0) {
            throw new IllegalArgumentException("agent.poll-seconds 必须为正数");
        }
        if (configRefreshSeconds < 0) {
            throw new IllegalArgumentException("control.config-refresh-seconds 不能为负数");
        }
        if (configRefreshSeconds > 0 && (configUrl == null || configUrl.isBlank())) {
            throw new IllegalArgumentException("control.config-refresh-seconds 需配合 control.config-url 使用（未启用托管则无热更新对象）");
        }
        switch (sourceType) {
            case "mysql", "postgres" -> {
                require(jdbcUrl, "source.jdbc.url");
                require(jdbcUser, "source.jdbc.user");
                require(jdbcQuery, "source.jdbc.query");
                require(cursorColumn, "source.jdbc.cursor-column");
                long marks = jdbcQuery.chars().filter(ch -> ch == '?').count();
                if (marks != 1) {
                    throw new IllegalArgumentException("source.jdbc.query 必须含且仅含一个 ? 占位符，当前 " + marks + " 个");
                }
            }
            case "csv" -> require(csvDir, "source.csv.dir");
            case "excel" -> require(excelDir, "source.excel.dir");
            case "kafka" -> {
                require(kafkaBootstrap, "source.kafka.bootstrap-servers");
                require(kafkaTopic, "source.kafka.topic");
                if (kafkaPollTimeoutMs <= 0) {
                    throw new IllegalArgumentException("source.kafka.poll-timeout-ms 必须为正数");
                }
                validateKafkaSecurity();
            }
            default -> throw new IllegalArgumentException(
                    "source.type 仅支持 mysql/postgres/csv/excel/kafka，当前: " + sourceType);
        }
    }

    public boolean isJdbc() {
        return "mysql".equals(sourceType) || "postgres".equals(sourceType);
    }

    /** kafka 鉴权 fail-fast：协议白名单 + SASL_* 必须带齐机制与凭证（PLAIN 明文机制不支持）+
     * 证书材料（truststore 验服务端 / keystore 出示客户端证书，mTLS 场景）类型白名单，
     * 明文协议下配置任何证书材料视为配置漂移直接拒绝。 */
    private void validateKafkaSecurity() {
        switch (kafkaSecurityProtocol) {
            case "PLAINTEXT", "SSL" -> { /* 无账号凭证 */ }
            case "SASL_PLAINTEXT", "SASL_SSL" -> {
                require(kafkaSaslMechanism, "source.kafka.sasl-mechanism");
                if (!"SCRAM-SHA-256".equals(kafkaSaslMechanism)
                        && !"SCRAM-SHA-512".equals(kafkaSaslMechanism)) {
                    throw new IllegalArgumentException("source.kafka.sasl-mechanism 仅支持 "
                            + "SCRAM-SHA-256/SCRAM-SHA-512，当前: " + kafkaSaslMechanism);
                }
                require(kafkaUsername, "source.kafka.username");
                require(kafkaPassword, "source.kafka.password");
            }
            default -> throw new IllegalArgumentException("source.kafka.security-protocol 仅支持 "
                    + "PLAINTEXT/SSL/SASL_PLAINTEXT/SASL_SSL，当前: " + kafkaSecurityProtocol);
        }
        requireCertMaterial("source.kafka.ssl-truststore-path", kafkaSslTruststorePath,
                "source.kafka.ssl-truststore-type", kafkaSslTruststoreType, "信任库");
        requireCertMaterial("source.kafka.ssl-keystore-path", kafkaSslKeystorePath,
                "source.kafka.ssl-keystore-type", kafkaSslKeystoreType, "证书库");
    }

    /** 证书材料通用校验（truststore / keystore 同规则）：配了 path 才校验——
     *  明文协议下配置 = 配置漂移 fail-fast；type 白名单 JKS/PKCS12。 */
    private void requireCertMaterial(String pathKey, String path,
                                     String typeKey, String type, String cnName) {
        if (path == null || path.isBlank()) {
            return;
        }
        if ("PLAINTEXT".equals(kafkaSecurityProtocol) || "SASL_PLAINTEXT".equals(kafkaSecurityProtocol)) {
            throw new IllegalArgumentException(pathKey + " 仅在 "
                    + "SSL/SASL_SSL 证书协议下生效，当前协议: " + kafkaSecurityProtocol
                    + "（明文协议配置" + cnName + "属配置漂移，请检查 security-protocol 是否写错）");
        }
        if (!"JKS".equals(type) && !"PKCS12".equals(type)) {
            throw new IllegalArgumentException(typeKey + " 仅支持 "
                    + "JKS/PKCS12，当前: " + type);
        }
    }

    private static void require(String v, String key) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填配置: " + key);
        }
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    private static String orDefault(String v, String dft) {
        return v == null || v.isBlank() ? dft : v.trim();
    }

    private static int intOf(String v, int dft) {
        return v == null || v.isBlank() ? dft : Integer.parseInt(v.trim());
    }

    private static long longOf(String v, long dft) {
        return v == null || v.isBlank() ? dft : Long.parseLong(v.trim());
    }

    /** SQL 允许换行排版，压成单行比较/绑定。 */
    private static String normalizeSql(String v) {
        return v == null ? null : v.replaceAll("\\s+", " ").trim();
    }
}
