package io.oddsmaker.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ClickHouse 访问入口（风控处置归档 + 大屏指标查询）。
 * 未配置 oddsmaker.clickhouse.url 时 isAvailable()=false，调用方按不可用降级，
 * 不影响处置主链路。
 */
@Component
public class ClickHouseClient {

    private static final Logger logger = LoggerFactory.getLogger(ClickHouseClient.class);

    private final JdbcTemplate jdbc;

    // 多构造器必须显式 @Autowired 指定生产构造器（否则 Spring 找无参构造失败）
    @Autowired
    public ClickHouseClient(
            @Value("${oddsmaker.clickhouse.url:}") String url,
            @Value("${oddsmaker.clickhouse.user:default}") String user,
            @Value("${oddsmaker.clickhouse.password:}") String password) {
        if (url == null || url.isBlank()) {
            this.jdbc = null;
            logger.info("ClickHouse not configured (oddsmaker.clickhouse.url empty), risk archive/metrics disabled");
        } else {
            DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
            this.jdbc = new JdbcTemplate(ds);
            logger.info("ClickHouse client initialized for risk archive/metrics: {}", url);
        }
    }

    public boolean isAvailable() {
        return jdbc != null;
    }

    /** 测试注入：直接给定 JdbcTemplate，覆盖 sanitize 参数规范化与 query/update/execute 转发。 */
    ClickHouseClient(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, sanitize(args));
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, sanitize(args));
    }

    public <T> T execute(ConnectionCallback<T> action) {
        return jdbc.execute(action);
    }

    /**
     * clickhouse-jdbc 0.6.5 的 JdbcTemplate 路径（setObject）把 Timestamp 参数
     * 渲染成 "HH:mm:ss.n"（丢日期部分）→ CH SYNTAX_ERROR；PreparedStatement 的
     * setTimestamp 才完整。此处统一把时间类参数转 'yyyy-MM-dd HH:mm:ss' 字符串，
     * CH 与 DateTime/DateTime64 的比较和写入都做隐式解析。
     */
    private static Object[] sanitize(Object... args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof java.sql.Timestamp t) {
                out[i] = TS_FMT.format(t.toLocalDateTime());
            } else if (a instanceof java.time.LocalDateTime ldt) {
                out[i] = TS_FMT.format(ldt);
            } else if (a instanceof java.time.LocalDate ld) {
                out[i] = ld.toString();
            } else {
                out[i] = a;
            }
        }
        return out;
    }

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
