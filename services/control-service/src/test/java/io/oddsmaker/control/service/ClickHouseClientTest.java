package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClickHouse 客户端：未配置降级、query/update/execute 转发与时间参数规范化（Timestamp/LocalDateTime/LocalDate）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClickHouse 客户端测试")
class ClickHouseClientTest {

    @Mock
    JdbcTemplate jdbc;

    @Mock
    ConnectionCallback<Object> callback;

    @Test
    @DisplayName("未配置 url：不可用；注入 jdbc：可用")
    void availability() {
        assertFalse(new ClickHouseClient("", "default", "").isAvailable());
        assertFalse(new ClickHouseClient(null, "default", "").isAvailable());
        ClickHouseClient configured = new ClickHouseClient("jdbc:clickhouse://127.0.0.1:8123/default", "default", "");
        assertTrue(configured.isAvailable());
        assertFalse(new ClickHouseClient((JdbcTemplate) null).isAvailable());
    }

    @Test
    @DisplayName("query/update/execute 转发到 JdbcTemplate")
    void delegatesToJdbcTemplate() throws Exception {
        ClickHouseClient client = new ClickHouseClient(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(7);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn("ok");

        assertTrue(client.query("SELECT 1").isEmpty());
        assertEquals(7, client.update("INSERT INTO t VALUES (?)", "x"));
        assertEquals("ok", client.execute(callback));
        verify(jdbc).queryForList(anyString(), any(Object[].class));
        verify(jdbc).update(anyString(), any(Object[].class));
        verify(jdbc).execute(any(ConnectionCallback.class));
    }

    @Test
    @DisplayName("时间类参数规范化：Timestamp/LocalDateTime → 'yyyy-MM-dd HH:mm:ss'，LocalDate → ISO 日期，其余原样")
    void sanitizesTemporalArgs() {
        ClickHouseClient client = new ClickHouseClient(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Timestamp ts = Timestamp.valueOf(LocalDateTime.of(2026, 9, 16, 10, 30, 5));
        LocalDateTime ldt = LocalDateTime.of(2026, 9, 15, 23, 59, 59);
        LocalDate ld = LocalDate.of(2026, 9, 1);
        client.query("SELECT ?", ts, ldt, ld, "plain", 42);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertEquals("2026-09-16 10:30:05", args[0]);
        assertEquals("2026-09-15 23:59:59", args[1]);
        assertEquals("2026-09-01", args[2]);
        assertSame("plain", args[3]);
        assertEquals(42, args[4]);
    }

    @Test
    @DisplayName("无参数调用不触发转换异常")
    void noArgsQuery() {
        ClickHouseClient client = new ClickHouseClient(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertTrue(client.query("SELECT 1").isEmpty());
    }
}
