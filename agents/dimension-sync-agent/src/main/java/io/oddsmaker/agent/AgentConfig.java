package io.oddsmaker.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent 配置：properties 文件（--config= 指定，默认 ./agent.properties），
 * 同名 System property 可逐键覆盖（便于密钥不落盘：-Dgateway.api-key=...）。
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
    // 同步状态上报（Control /api/dimensions/sync-status；url 空则跳过）
    public String statusUrl;
    public String statusToken;
    public String sourceKey;

    public static AgentConfig load(String[] args) throws IOException {
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
        // System property 逐键覆盖文件值
        for (String key : p.stringPropertyNames()) {
            String override = System.getProperty(key);
            if (override != null) {
                p.setProperty(key, override);
            }
        }
        AgentConfig c = new AgentConfig();
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
        c.statusUrl = trim(p.getProperty("status.url"));
        c.statusToken = trim(p.getProperty("status.token"));
        c.sourceKey = trim(p.getProperty("status.source-key"));
        return c;
    }

    /** 按 source 类型校验，配置错误 fail-fast（启动即抛，不进轮询循环）。 */
    public void validate() {
        require(gatewayEndpoint, "gateway.endpoint");
        require(gatewayApiKey, "gateway.api-key");
        require(gameId, "game.id");
        require(environment, "game.environment");
        require(sourceType, "source.type");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("agent.batch-size 必须为正数");
        }
        if (pollSeconds <= 0) {
            throw new IllegalArgumentException("agent.poll-seconds 必须为正数");
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
            default -> throw new IllegalArgumentException("source.type 仅支持 mysql/postgres/csv，当前: " + sourceType);
        }
    }

    public boolean isJdbc() {
        return "mysql".equals(sourceType) || "postgres".equals(sourceType);
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

    /** SQL 允许换行排版，压成单行比较/绑定。 */
    private static String normalizeSql(String v) {
        return v == null ? null : v.replaceAll("\\s+", " ").trim();
    }
}
