package io.oddsmaker.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public <T> T execute(ConnectionCallback<T> action) {
        return jdbc.execute(action);
    }
}
