package io.oddsmaker.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 增量源（mysql/postgres）：单 SQL 单 ? 占位，水位绑定 PreparedStatement（类型还原自 cursor 标签）。
 * 约定查询以 ORDER BY <cursor_column> 结尾——新水位取最后一行的 cursor 值；
 * 中途失败不落盘，下一轮以旧水位重放同窗口，下游 ReplacingMergeTree(version_ts) 幂等。
 */
public final class JdbcSource implements DimensionSource {

    private final AgentConfig cfg;

    public JdbcSource(AgentConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return cfg.sourceType + ":" + cfg.jdbcUrl;
    }

    @Override
    public String type() {
        return cfg.sourceType;
    }

    @Override
    public PollResult poll(Checkpoint current) throws SQLException {
        Object bound = current.cursor == null
                ? CursorCodec.decode(CursorCodec.encodeConfig(cfg.cursorInitial))
                : CursorCodec.decode(current.cursor);
        List<DimensionChange> changes = new ArrayList<>();
        Object lastCursorValue = bound;
        long now = System.currentTimeMillis();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(cfg.jdbcQuery)) {
            ps.setObject(1, bound);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                int cursorIdx = -1;
                String[] labels = new String[cols + 1];
                for (int i = 1; i <= cols; i++) {
                    labels[i] = md.getColumnLabel(i).toLowerCase();
                    if (labels[i].equals(cfg.cursorColumn.toLowerCase())) {
                        cursorIdx = i;
                    }
                }
                if (cursorIdx < 0) {
                    throw new SQLException("结果集缺少 cursor 列: " + cfg.cursorColumn);
                }
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        Object v = rs.getObject(i);
                        row.put(labels[i], v == null ? null : String.valueOf(v));
                    }
                    changes.add(mapRow(row, cfg.dimType, now));
                    lastCursorValue = rs.getObject(cursorIdx);
                }
            }
        }
        Checkpoint next = current.copy();
        if (!changes.isEmpty()) {
            next.cursor = CursorCodec.encodeValue(lastCursorValue);
            next.lastEventTs = changes.stream().mapToLong(c -> c.versionTs).max().orElse(now);
        }
        return PollResult.of(changes, next);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(cfg.jdbcUrl, cfg.jdbcUser, cfg.jdbcPassword);
    }

    /** 控制列（dim_type/resource_id/item_code/level_id/id/op/version_ts）抽出来，其余进 attributes；语义对齐 dimension-sync-job 的 parseProps。 */
    static DimensionChange mapRow(Map<String, String> row, String defaultDimType, long now) {
        Map<String, String> lower = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey() != null) {
                lower.put(e.getKey().toLowerCase(), e.getValue());
            }
        }
        String dimType = normalizeDimType(firstNonNull(
                lower.remove("dim_type"), lower.remove("dimension_type"), defaultDimType));
        String op = orDefault(lower.remove("op"), "upsert");
        String resourceId = firstNonNull(
                lower.remove("resource_id"), lower.remove("item_code"),
                lower.remove("level_id"), lower.remove("id"));
        long versionTs = parseVersionTs(lower.remove("version_ts"), now);

        DimensionChange change = new DimensionChange(dimType, op, resourceId, versionTs);
        for (Map.Entry<String, String> e : lower.entrySet()) {
            change.attr(e.getKey(), e.getValue());
        }
        return change;
    }

    /** resource/items→item，levels→level，其余原样（下游归一化兜底）。 */
    static String normalizeDimType(String v) {
        if (v == null) {
            return "item";
        }
        return switch (v.trim().toLowerCase()) {
            case "resource", "items" -> "item";
            case "levels" -> "level";
            default -> v.trim().toLowerCase();
        };
    }

    /** epoch millis 优先，兼容 ISO-8601 文本；都解析不了取 now。 */
    static long parseVersionTs(String v, long now) {
        if (v == null || v.isBlank()) {
            return now;
        }
        String s = v.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            // 非整数时间戳，试 ISO
        }
        try {
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            return now;
        }
    }

    private static String firstNonNull(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String orDefault(String v, String dft) {
        return v == null || v.isBlank() ? dft : v.trim();
    }
}
