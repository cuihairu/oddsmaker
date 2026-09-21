package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 报表查询引擎深度测试：报表词汇 → ClickHouse 白名单聚合 SQL 的编译正确性、
 * 诚实失败语义（坏配置/CH 不可达/查询异常 → FAILED）、结果写回真实值。
 * 词汇表安全模型：标识符全部来自服务端静态映射（零用户输入拼接），值全参数化。
 */
@DisplayName("报表引擎：白名单 SQL 编译 + 诚实失败 + 真实结果写回")
class ReportServiceDeepTest {

    private ReportRepo reportRepo;
    private ReportExecutionRepo executionRepo;
    private ClickHouseClient clickHouse;
    private ReportService service;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ReportRepo.class);
        executionRepo = mock(ReportExecutionRepo.class);
        clickHouse = mock(ClickHouseClient.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        ReportService real = new ReportService();
        ReflectionTestUtils.setField(real, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(real, "executionRepo", executionRepo);
        ReflectionTestUtils.setField(real, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(real, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(real, "clickHouse", clickHouse);
        service = real;

        lenient().when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(clickHouse.isAvailable()).thenReturn(true);
        lenient().when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("bucket", "2026-09-19 00:00:00", "a_events", 42L, "a_players", 17L)));
    }

    private ReportEntity report() {
        ReportEntity r = new ReportEntity();
        r.id = "rpt_1";
        r.gameId = "g1";
        r.name = "daily";
        r.status = ReportEntity.ReportStatus.PUBLISHED;
        return r;
    }

    private ReportExecutionEntity run(ReportEntity r) {
        when(reportRepo.findById("rpt_1")).thenReturn(Optional.of(r));
        return service.executeReport("rpt_1", "ops", "manual", null, null);
    }

    @Test
    @DisplayName("缺省词汇编译：默认聚合 events+players、day 粒度、7d 窗口，args = [game, start, end]")
    void defaultVocabularyCompiles() {
        ReportExecutionEntity execution = run(report());

        assertEquals(ReportExecutionEntity.ExecutionStatus.COMPLETED, execution.executionStatus);
        assertEquals(Long.valueOf(1L), execution.rowCount);
        assertNotNull(execution.resultData);
        assertNotNull(execution.queryTimeMs);
        assertTrue(execution.executionTimeMs >= 0);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(sqlCap.capture(), argsCap.capture());

        String sql = sqlCap.getValue();
        assertTrue(sql.contains("toStartOfDay(ts_server) AS bucket"), sql);
        assertTrue(sql.contains("count() AS a_events"), sql);
        assertTrue(sql.contains("uniqExact(if(player_id != '', player_id, if(user_id != '', user_id, device_id))) AS a_players"), sql);
        assertTrue(sql.startsWith("SELECT "), sql);
        assertTrue(sql.contains(" FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ?"), sql);
        assertTrue(sql.contains(" GROUP BY bucket ORDER BY bucket LIMIT 10000"), sql);
        assertFalse(sql.contains("AND environment"), sql);
        assertFalse(sql.contains("AND event_type"), sql);

        Object[] args = argsCap.getValue();
        assertEquals(3, args.length);
        assertEquals("g1", args[0]);
        LocalDateTime start = (LocalDateTime) args[1];
        LocalDateTime end = (LocalDateTime) args[2];
        assertTrue(start.isBefore(end));
        assertTrue(java.time.Duration.between(start, end).toDays() >= 6);
    }

    @Test
    @DisplayName("粒度映射：minute/hour/week/month → toStartOfMinute/toStartOfHour/toMonday/toStartOfMonth")
    void granularityMapping() {
        for (String g : List.of("minute", "hour", "week", "month")) {
            ReportEntity r = report();
            r.timeGranularity = g;
            run(r);
        }
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(4)).query(sqlCap.capture(), any(Object[].class));
        List<String> sqls = sqlCap.getAllValues();
        assertTrue(sqls.get(0).contains("toStartOfMinute(ts_server)"), sqls.get(0));
        assertTrue(sqls.get(1).contains("toStartOfHour(ts_server)"), sqls.get(1));
        assertTrue(sqls.get(2).contains("toMonday(ts_server)"), sqls.get(2));
        assertTrue(sqls.get(3).contains("toStartOfMonth(ts_server)"), sqls.get(3));
    }

    @Test
    @DisplayName("自定义聚合与维度：sum/avg/min/max/count_distinct 编译 + groupBy 白名单维度进 SELECT/GROUP BY")
    void customAggregationsAndDimensions() {
        ReportEntity r = report();
        r.aggregations = "{\"rev\":\"sum:revenue\",\"avg_rev\":\"avg:revenue\",\"lo\":\"min:revenue\","
            + "\"hi\":\"max:revenue\",\"uv\":\"count_distinct:players\",\"n\":\"count\"}";
        r.groupBy = "[\"platform\",\"event_type\"]";
        ReportExecutionEntity execution = run(r);

        assertEquals(ReportExecutionEntity.ExecutionStatus.COMPLETED, execution.executionStatus);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).query(sqlCap.capture(), any(Object[].class));
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("coalesce(sum(revenue_amount), 0) AS a_rev"), sql);
        assertTrue(sql.contains("avg(revenue_amount) AS a_avg_rev"), sql);
        assertTrue(sql.contains("min(revenue_amount) AS a_lo"), sql);
        assertTrue(sql.contains("max(revenue_amount) AS a_hi"), sql);
        assertTrue(sql.contains("uniqExact(") && sql.contains(") AS a_uv"), sql);
        assertTrue(sql.contains("if(platform = '', 'unknown', platform) AS d_platform"), sql);
        assertTrue(sql.contains("if(event_type = '', 'unknown', event_type) AS d_event_type"), sql);
        assertTrue(sql.contains("GROUP BY bucket, d_platform, d_event_type"), sql);
    }

    @Test
    @DisplayName("过滤缺省各侧：env 无键/空白、eventTypes 空数组/非数组、queryConfig/groupBy 空白 JSON")
    void configFilterMissingSides() {
        // config 非 null 但无 environment 键（env == null 侧）+ eventTypes 空数组（isEmpty true 侧）
        ReportEntity noEnvKey = report();
        noEnvKey.queryConfig = "{\"eventTypes\":[]}";
        run(noEnvKey);

        // environment 为空白（isBlank true 侧）+ eventTypes 非 List（instanceof false 侧）
        ReportEntity blankEnv = report();
        blankEnv.queryConfig = "{\"environment\":\"   \",\"eventTypes\":\"purchase\"}";
        run(blankEnv);

        // queryConfig 纯空白（readJsonMap isBlank 侧 → config null）+ groupBy 纯空白（readJsonList isBlank 侧）
        ReportEntity blankJson = report();
        blankJson.queryConfig = "   ";
        blankJson.groupBy = "   ";
        assertEquals(ReportExecutionEntity.ExecutionStatus.COMPLETED, run(blankJson).executionStatus);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(3)).query(sqlCap.capture(), any(Object[].class));
        for (String sql : sqlCap.getAllValues()) {
            assertFalse(sql.contains("AND environment"), sql);
            assertFalse(sql.contains("AND event_type"), sql);
        }
        // blank groupBy → 无维度列
        assertFalse(sqlCap.getAllValues().get(2).contains(" d_"), sqlCap.getAllValues().get(2));
    }

    @Test
    @DisplayName("过滤参数化：queryConfig.environment 等值 + eventTypes IN (?, ?)，值不拼 SQL")
    void configFiltersParameterized() {
        ReportEntity r = report();
        r.queryConfig = "{\"environment\":\"prod\",\"eventTypes\":[\"purchase\",\"login\"]}";
        run(r);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(sqlCap.capture(), argsCap.capture());
        String sql = sqlCap.getValue();
        assertTrue(sql.contains("AND environment = ?"), sql);
        assertTrue(sql.contains("AND event_type IN (?, ?)"), sql);
        Object[] args = argsCap.getValue();
        // 3 时间窗 + 1 environment + 2 eventTypes = 6
        assertEquals(6, args.length);
        assertEquals("prod", args[3]);
        assertEquals("purchase", args[4]);
        assertEquals("login", args[5]);
    }

    @Test
    @DisplayName("执行参数 timeRange 覆盖报表默认窗口")
    void executionParametersOverrideTimeRange() {
        ReportEntity r = report();
        when(reportRepo.findById("rpt_1")).thenReturn(Optional.of(r));
        service.executeReport("rpt_1", "ops", "manual", Map.of("timeRange", "24h"), null);

        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(anyString(), argsCap.capture());
        LocalDateTime start = (LocalDateTime) argsCap.getValue()[1];
        LocalDateTime end = (LocalDateTime) argsCap.getValue()[2];
        assertTrue(java.time.Duration.between(start, end).toHours() >= 23
            && java.time.Duration.between(start, end).toHours() <= 24);
    }

    @Test
    @DisplayName("坏配置诚实落 FAILED：未知 op/未知 metric/未知维度/非法别名/非法 timeRange/越界窗口/坏粒度")
    void badConfigFailsHonest() {
        // 未知 op
        ReportEntity r1 = report();
        r1.aggregations = "{\"x\":\"drop:revenue\"}";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r1).executionStatus);
        // 未知 metric
        ReportEntity r2 = report();
        r2.aggregations = "{\"x\":\"sum:secret_col\"}";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r2).executionStatus);
        // 非法别名（会拼列别名，限标识符字符）
        ReportEntity r3 = report();
        r3.aggregations = "{\"bad alias!\":\"count\"}";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r3).executionStatus);
        // 未知维度
        ReportEntity r4 = report();
        r4.groupBy = "[\"secret_col\"]";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r4).executionStatus);
        // 非法 timeRange
        ReportEntity r5 = report();
        r5.defaultTimeRange = "7w";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r5).executionStatus);
        // 越界窗口
        ReportEntity r6 = report();
        r6.defaultTimeRange = "366d";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r6).executionStatus);
        // 坏粒度
        ReportEntity r7 = report();
        r7.timeGranularity = "quarter";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r7).executionStatus);
        // 坏 JSON
        ReportEntity r8 = report();
        r8.aggregations = "{not json";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r8).executionStatus);
        // groupBy 坏 JSON 数组
        ReportEntity r9 = report();
        r9.groupBy = "{oops";
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r9).executionStatus);

        // 全部失败都不触碰 CH
        verify(clickHouse, never()).query(anyString(), any(Object[].class));
        // 错误消息可行动：未知 metric 提示合法 metric 清单
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, run(r2).executionStatus);
        ArgumentCaptor<ReportExecutionEntity> execCap = ArgumentCaptor.forClass(ReportExecutionEntity.class);
        verify(executionRepo, atLeastOnce()).save(execCap.capture());
        assertTrue(execCap.getValue().errorMessage.contains("players"), execCap.getValue().errorMessage);
    }

    @Test
    @DisplayName("CH 不可达与查询异常均诚实 FAILED（不造假成功）")
    void clickHouseUnavailableAndQueryFailureFail() {
        ReportEntity r = report();
        when(reportRepo.findById("rpt_1")).thenReturn(Optional.of(r));
        when(clickHouse.isAvailable()).thenReturn(false);
        ReportExecutionEntity e1 = service.executeReport("rpt_1", "ops", null, null, null);
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, e1.executionStatus);
        assertTrue(e1.errorMessage.contains("ClickHouse"));

        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("CH down"));
        ReportExecutionEntity e2 = service.executeReport("rpt_1", "ops", null, null, null);
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, e2.executionStatus);
        assertEquals("CH down", e2.errorMessage);
    }

    @Test
    @DisplayName("defaultTimeRange 为 null 时兜底 7d 窗口")
    void nullTimeRangeFallsBackTo7d() {
        ReportEntity r = report();
        r.defaultTimeRange = null; // 覆盖字段初始化器，走 parseTimeRange 的 blank 兜底
        run(r);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(anyString(), argsCap.capture());
        LocalDateTime start = (LocalDateTime) argsCap.getValue()[1];
        LocalDateTime end = (LocalDateTime) argsCap.getValue()[2];
        long days = java.time.Duration.between(start, end).toDays();
        assertTrue(days >= 6 && days <= 7, "expected ~7d window, got " + days);
    }

    @Test
    @DisplayName("软删报表拒绝执行（与 IntegrationService verify 同款）")
    void deletedReportRefused() {
        ReportEntity r = report();
        r.deletedAt = LocalDateTime.now();
        when(reportRepo.findById("rpt_1")).thenReturn(Optional.of(r));
        assertThrows(IllegalArgumentException.class,
            () -> service.executeReport("rpt_1", "ops", null, null, null));
        verify(executionRepo, never()).save(any());
    }
}
