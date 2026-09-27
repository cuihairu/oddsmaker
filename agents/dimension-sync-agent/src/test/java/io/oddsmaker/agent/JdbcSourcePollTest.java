package io.oddsmaker.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcSource.poll 的完整回路：用**自注册的假 JDBC 驱动**（DriverManager 前缀
 * {@code jdbc:oddsmaker-fake:}）+ JDK 动态代理伪造 Connection/PreparedStatement/ResultSet，
 * 不需要任何数据库即可覆盖位点绑定、行映射、列定位、空洞与失败传播各分支。
 * 业务代码零改动（JdbcSource 走的就是真实 DriverManager 路径）。
 *
 * <p>真实数据库端到端不在本类范围（仓库无 DB 依赖，见模块 README）。
 */
class JdbcSourcePollTest {

    private static final String URL_PREFIX = "jdbc:oddsmaker-fake:";

    /** 一次假查询的编排：结果集列标签与行数据，以及要注入的失败。 */
    static final class FakeJdbc {
        String url;
        String user;
        String password;
        String preparedSql;
        List<Object> bound = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        SQLException connectError;
        SQLException queryError;
        int closes;

        Connection connection() {
            return (Connection) proxy(Connection.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "prepareStatement":
                        preparedSql = (String) args[0];
                        return statement();
                    case "close":
                        closes++;
                        return null;
                    default:
                        return defaultValue(m.getReturnType());
                }
            });
        }

        private PreparedStatement statement() {
            return (PreparedStatement) proxy(PreparedStatement.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "setObject":
                        bound.add(args[1]);
                        return null;
                    case "executeQuery":
                        if (queryError != null) {
                            throw queryError;
                        }
                        return resultSet();
                    case "close":
                        closes++;
                        return null;
                    default:
                        return defaultValue(m.getReturnType());
                }
            });
        }

        private ResultSet resultSet() {
            return (ResultSet) proxy(ResultSet.class, new InvocationHandler() {
                private int cursor = -1;

                @Override
                public Object invoke(Object p, java.lang.reflect.Method m, Object[] args) {
                    switch (m.getName()) {
                        case "getMetaData":
                            return metaData();
                        case "next":
                            return ++cursor < rows.size();
                        case "getObject":
                            Object[] row = rows.get(cursor);
                            int col = (Integer) args[0];
                            return col <= row.length ? row[col - 1] : null;
                        case "wasNull":
                            return false;
                        case "close":
                            closes++;
                            return null;
                        default:
                            return defaultValue(m.getReturnType());
                    }
                }
            });
        }

        private ResultSetMetaData metaData() {
            return (ResultSetMetaData) proxy(ResultSetMetaData.class, (p, m, args) ->
                    "getColumnCount".equals(m.getName()) ? labels.size()
                            : "getColumnLabel".equals(m.getName())
                                    ? labels.get((Integer) args[0] - 1)
                                    : defaultValue(m.getReturnType()));
        }
    }

    /** 假驱动：只认自己的 URL 前缀，其余返回 null（交回 DriverManager 继续找下一个驱动）。 */
    static final class FakeDriver implements Driver {
        final FakeJdbc jdbc;

        FakeDriver(FakeJdbc jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            if (jdbc.connectError != null) {
                throw jdbc.connectError;
            }
            jdbc.url = url;
            jdbc.user = info.getProperty("user");
            jdbc.password = info.getProperty("password");
            return jdbc.connection();
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(URL_PREFIX);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private FakeDriver registered;

    private FakeJdbc register(FakeJdbc jdbc) throws SQLException {
        registered = new FakeDriver(jdbc);
        DriverManager.registerDriver(registered);
        return jdbc;
    }

    /** 用完注销：假驱动若留在 DriverManager 里会污染同 JVM 其它测试的 getConnection 路径。 */
    @AfterEach
    void deregister() throws SQLException {
        if (registered != null) {
            DriverManager.deregisterDriver(registered);
            registered = null;
        }
    }

    private static Object proxy(Class<?> iface, InvocationHandler h) {
        return Proxy.newProxyInstance(JdbcSourcePollTest.class.getClassLoader(),
                new Class<?>[]{iface}, (p, m, args) -> {
                    if ("toString".equals(m.getName())) {
                        return "fake-" + iface.getSimpleName();
                    }
                    if ("hashCode".equals(m.getName())) {
                        return System.identityHashCode(p);
                    }
                    if ("equals".equals(m.getName())) {
                        return p == args[0];
                    }
                    return h.invoke(p, m, args);
                });
    }

    /** 代理方法未实现时按返回类型给安全默认值（原始类型不能返回 null）。 */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    private static AgentConfig jdbcCfg(String cursorInitial, String cursorColumn) {
        AgentConfig cfg = new AgentConfig();
        cfg.gatewayEndpoint = "http://gw";
        cfg.gatewayApiKey = "k";
        cfg.gameId = "g1";
        cfg.environment = "prod";
        cfg.sourceType = "mysql";
        cfg.dimType = "item";
        cfg.jdbcUrl = URL_PREFIX + "dims";
        cfg.jdbcUser = "oddsmaker_ro";
        cfg.jdbcPassword = "pw-with-\"quote";
        cfg.jdbcQuery = "SELECT resource_id, weight, dim_type, version_ts, updated_at"
                + " FROM items WHERE updated_at > ? ORDER BY updated_at";
        cfg.cursorColumn = cursorColumn;
        cfg.cursorInitial = cursorInitial;
        cfg.validate();
        return cfg;
    }

    @Test
    @DisplayName("首次轮询：cursor-initial 判型绑定（整数→Long），行映射 + 列名大小写归一 + null 空洞 + 时间型水位")
    void firstPollBindsConfiguredCursorAndMapsRows() throws Exception {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.labels = Arrays.asList("RESOURCE_ID", "Weight", "DIM_TYPE", "VERSION_TS", "UPDATED_AT");
        Timestamp t1 = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp t2 = Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"));
        jdbc.rows = Arrays.asList(
                new Object[]{"sword_01", 1.5, "resource", 1000L, t1},
                new Object[]{"sword_02", null, null, "not-a-time", t2});

        DimensionSource.PollResult r = new JdbcSource(jdbcCfg("1000", "updated_at")).poll(new Checkpoint());

        // 连接与凭证：配置面 → DriverManager → 驱动，逐段核对
        assertEquals(URL_PREFIX + "dims", jdbc.url);
        assertEquals("oddsmaker_ro", jdbc.user);
        assertEquals("pw-with-\"quote", jdbc.password);
        assertTrue(jdbc.preparedSql.endsWith("ORDER BY updated_at"));
        // 首轮绑的是 cursor-initial 的解码值（整数 → Long，PG 时间列场景另有 t: 标签）
        assertEquals(List.of(1000L), jdbc.bound);

        assertEquals(2, r.changes().size());
        DimensionChange first = r.changes().get(0);
        // dim_type=resource 归一化为 item；控制列抽出后其余列进 attributes（列名小写化）
        assertEquals("item", first.dimType);
        assertEquals("upsert", first.op);
        assertEquals("sword_01", first.resourceId);
        assertEquals(1000L, first.versionTs);
        assertEquals("1.5", first.attributes.get("weight"));
        // 第二行：dim_type 为 null → 取 cfg.dimType 缺省；version_ts 解析不了 → 取本轮 now
        DimensionChange second = r.changes().get(1);
        assertEquals("item", second.dimType);
        assertTrue(second.versionTs > 0);
        // null 单元格：不串列、也不进 attributes（DimensionChange.attr 拒收 null 值）
        assertFalse(second.attributes.containsKey("weight"));
        assertEquals("sword_02", second.resourceId);
        assertFalse(second.attributes.containsKey("resource_id"), "控制列抽出后不再进 attributes");
        assertFalse(second.attributes.containsKey("dim_type"));
        // 水位取最后一行的 cursor 列（时间型 → t: 标签），lastEventTs 取所有行的 max(version_ts)
        assertEquals("t:2026-01-02T00:00:00Z", r.next().cursor);
        assertTrue(r.next().lastEventTs >= 1000L);
        assertEquals(3, jdbc.closes, "结果集/语句/连接均被 try-with-resources 关闭");
    }

    @Test
    @DisplayName("续传轮询：已有 checkpoint 位点优先于 cursor-initial，并按标签还原绑定类型")
    void resumePollBindsCheckpointCursorNotConfigInitial() throws Exception {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.labels = Arrays.asList("resource_id", "version_ts", "updated_at");
        jdbc.rows = List.<Object[]>of(new Object[]{"shield_01", 2000L, 9001L});

        Checkpoint current = new Checkpoint();
        current.cursor = "t:2026-01-01T00:00:00Z";
        DimensionSource.PollResult r = new JdbcSource(jdbcCfg("1000", "updated_at")).poll(current);

        // t: 标签必须还原成 Timestamp 绑定（PG timestamp 列拒绝 bigint 字面量）
        assertEquals(1, jdbc.bound.size());
        assertTrue(jdbc.bound.get(0) instanceof Timestamp, "实际绑定类型: " + jdbc.bound.get(0).getClass());
        assertEquals(2000L, r.changes().get(0).versionTs);
        // 整型 cursor 列 → n: 标签
        assertEquals("n:9001", r.next().cursor);
    }

    @Test
    @DisplayName("空结果集：位点与 lastEventTs 原样保留（无变更不得回退水位）")
    void emptyResultKeepsCheckpointUntouched() throws Exception {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.labels = Arrays.asList("resource_id", "updated_at");
        jdbc.rows = List.of();

        Checkpoint current = new Checkpoint();
        current.cursor = "s:abc";
        current.lastEventTs = 777L;
        current.pushedCount = 5L;
        DimensionSource.PollResult r = new JdbcSource(jdbcCfg(null, "updated_at")).poll(current);

        assertEquals(0, r.changes().size());
        assertEquals("s:abc", r.next().cursor);
        assertEquals(777L, r.next().lastEventTs);
        assertEquals(5L, r.next().pushedCount);
        // 绑的是 checkpoint 位点还原出的字符串（s: 标签 → String），空轮也不得改写
        assertEquals(1, jdbc.bound.size());
        assertEquals("abc", jdbc.bound.get(0));
    }

    @Test
    @DisplayName("未配 cursor-initial 的首轮：绑 null 全表起（encodeConfig 空值→null→setObject(1, null)）")
    void nullCursorInitialBindsNullForFirstFullScan() throws Exception {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.labels = Arrays.asList("resource_id", "version_ts", "updated_at");
        jdbc.rows = List.<Object[]>of(new Object[]{"sword_09", 3000L, 42L});

        DimensionSource.PollResult r = new JdbcSource(jdbcCfg(null, "updated_at")).poll(new Checkpoint());

        assertEquals(1, jdbc.bound.size());
        assertNull(jdbc.bound.get(0));
        // 有变更则水位照常前进到最后一行的 cursor 值
        assertEquals("n:42", r.next().cursor);
        assertEquals(3000L, r.next().lastEventTs);
    }

    @Test
    @DisplayName("结果集缺 cursor 列：SQLException 明确报错，不静默当空轮")
    void missingCursorColumnFailsLoudly() throws SQLException {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.labels = Arrays.asList("resource_id", "version_ts");
        jdbc.rows = List.<Object[]>of(new Object[]{"sword_01", 1000L});

        JdbcSource source = new JdbcSource(jdbcCfg("0", "updated_at"));
        SQLException ex = assertThrows(SQLException.class, () -> source.poll(new Checkpoint()));
        assertTrue(ex.getMessage().contains("updated_at"), ex.getMessage());
        assertEquals(1, jdbc.bound.size());   // 失败发生在绑定之后（查询确实跑了）
    }

    @Test
    @DisplayName("连接失败：SQLException 原样上抛（Agent 侧计入 errorCount/lastError，见 AgentMainTest）")
    void connectFailurePropagates() throws SQLException {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.connectError = new SQLException("connection refused");
        JdbcSource source = new JdbcSource(jdbcCfg("0", "updated_at"));
        SQLException ex = assertThrows(SQLException.class, () -> source.poll(new Checkpoint()));
        assertEquals("connection refused", ex.getMessage());
    }

    @Test
    @DisplayName("查询失败（executeQuery 抛错）：异常上抛且 try-with-resources 仍关闭连接")
    void queryFailurePropagatesAndCloses() throws SQLException {
        FakeJdbc jdbc = register(new FakeJdbc());
        jdbc.queryError = new SQLException("permission denied on items");
        JdbcSource source = new JdbcSource(jdbcCfg("0", "updated_at"));
        SQLException ex = assertThrows(SQLException.class, () -> source.poll(new Checkpoint()));
        assertEquals("permission denied on items", ex.getMessage());
        assertTrue(jdbc.closes >= 1, "连接/语句应被 try-with-resources 关闭");
    }

    @Test
    @DisplayName("name()/type()：源标识与类型标签取自配置（状态上报里的 sourceType）")
    void exposesNameAndType() throws SQLException {
        AgentConfig cfg = jdbcCfg(null, "updated_at");
        JdbcSource source = new JdbcSource(cfg);
        assertEquals("mysql:" + cfg.jdbcUrl, source.name());
        assertEquals("mysql", source.type());
    }
}
