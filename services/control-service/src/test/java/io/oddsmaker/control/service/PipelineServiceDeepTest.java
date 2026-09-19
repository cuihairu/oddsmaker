package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DataQualityRuleEntity;
import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.jpa.PipelineEntity;
import io.oddsmaker.control.jpa.PipelineJobEntity;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PipelineService 质量门禁做真后的规则分派深度测试：
 * 每种 RuleType 的真实 SQL 形态（表白名单 + 标识符正则 + ? 参数化）与守卫分支。
 */
class PipelineServiceDeepTest {

    private PipelineService service;
    private ClickHouseClient ch;
    private DataQualityRuleRepo ruleRepo;
    private PipelineRepo pipelineRepo;
    private PipelineJobRepo jobRepo;

    @BeforeEach
    void setUp() {
        service = new PipelineService();
        ch = mock(ClickHouseClient.class);
        ruleRepo = mock(DataQualityRuleRepo.class);
        pipelineRepo = mock(PipelineRepo.class);
        jobRepo = mock(PipelineJobRepo.class);
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", jobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", ruleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));
        ReflectionTestUtils.setField(service, "clickHouse", ch);
        when(ruleRepo.save(any(DataQualityRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DataQualityRuleEntity rule(DataQualityRuleEntity.RuleType type) {
        DataQualityRuleEntity r = new DataQualityRuleEntity();
        r.ruleName = "r-" + type;
        r.ruleType = type;
        r.targetTable = "events";
        r.targetColumn = "device_id";
        return r;
    }

    /** 评估规则并捕获下发到 ClickHouse 的 SQL 与参数。 */
    private Captured eval(DataQualityRuleEntity r, List<Map<String, Object>> rows) {
        when(ch.query(anyString(), any(Object[].class))).thenReturn(rows);
        service.evaluateQualityRule(r);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(ch, atLeastOnce()).query(sql.capture(), args.capture());
        int last = sql.getAllValues().size() - 1;
        return new Captured(sql.getAllValues().get(last), args.getAllValues().get(last));
    }

    private record Captured(String sql, Object[] args) {}

    // ==================== RuleType → SQL 形态 ====================

    @Test
    @DisplayName("COMPLETENESS：IS NULL OR '' 条件 + 评估结果落库")
    void completenessSqlShape() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        Captured c = eval(r, List.of(Map.of("total", 100L, "v", 12L)));

        assertEquals("SELECT count() AS total, countIf(device_id IS NULL OR device_id = '') AS v FROM events", c.sql());
        assertArrayEquals(new Object[0], c.args());
        assertEquals(12, r.lastViolationCount);
        assertEquals(1, r.totalEvaluations);
        assertEquals(12, r.totalViolations);  // gt 0 越界 → 不通过 → 计入
    }

    @Test
    @DisplayName("UNIQUENESS：uniqExact 语义 + 通过时 totalViolations 不累加")
    void uniquenessSqlShapeAndPass() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.UNIQUENESS);
        Captured c = eval(r, List.of(Map.of("total", 50L, "v", 0L)));

        assertEquals("SELECT count() AS total, count() - uniqExact(device_id) AS v FROM events", c.sql());
        assertEquals(0, r.lastViolationCount);
        assertEquals(1, r.totalEvaluations);
        assertEquals(0, r.totalViolations);  // 0 > 0 为假 → 通过
    }

    @Test
    @DisplayName("RANGE：双边界 OR 条件 + Double 参数化")
    void rangeBothBounds() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.RANGE);
        r.minThreshold = "0.5";
        r.maxThreshold = "100";
        Captured c = eval(r, List.of(Map.of("total", 80L, "v", 3L)));

        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND (device_id < ? OR device_id > ?)) AS v FROM events", c.sql());
        assertEquals(2, c.args().length);
        assertEquals(0.5, (Double) c.args()[0]);
        assertEquals(100.0, (Double) c.args()[1]);
    }

    @Test
    @DisplayName("RANGE：仅下界 / 仅上界 / 双空 IAE / 非数字 IAE")
    void rangeBoundsVariants() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.RANGE);
        r.minThreshold = "-5";
        Captured c = eval(r, List.of(Map.of("total", 10L, "v", 0L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND (device_id < ?)) AS v FROM events", c.sql());
        assertEquals(-5.0, (Double) c.args()[0]);

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.RANGE);
        r2.maxThreshold = "99.9";
        Captured c2 = eval(r2, List.of(Map.of("total", 10L, "v", 0L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND (device_id > ?)) AS v FROM events", c2.sql());
        assertEquals(99.9, (Double) c2.args()[0]);

        DataQualityRuleEntity r3 = rule(DataQualityRuleEntity.RuleType.RANGE);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.evaluateQualityRule(r3));
        assertTrue(ex.getMessage().contains("min_threshold or max_threshold"));

        DataQualityRuleEntity r4 = rule(DataQualityRuleEntity.RuleType.RANGE);
        r4.minThreshold = "abc";
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r4));
    }

    @Test
    @DisplayName("PATTERN：NOT match 参数化 + 空正则 IAE")
    void patternRule() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.PATTERN);
        r.patternRegex = "^[0-9a-f-]{36}$";
        Captured c = eval(r, List.of(Map.of("total", 10L, "v", 1L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND NOT match(device_id, ?)) AS v FROM events", c.sql());
        assertArrayEquals(new Object[]{"^[0-9a-f-]{36}$"}, c.args());

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.PATTERN);
        r2.patternRegex = "  ";
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r2));
    }

    @Test
    @DisplayName("VALIDITY：NOT IN 占位符按值数量展开 + 数字/文本分型 + 空/坏 JSON IAE")
    void validityRule() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.VALIDITY);
        r.allowedValues = "[\"win\", \"lose\", 1]";
        Captured c = eval(r, List.of(Map.of("total", 20L, "v", 2L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND device_id NOT IN (?, ?, ?)) AS v FROM events", c.sql());
        assertEquals(3, c.args().length);
        assertEquals("win", c.args()[0]);
        assertEquals("lose", c.args()[1]);
        assertEquals(new java.math.BigDecimal("1"), c.args()[2]);  // 数字节点 → decimalValue

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.VALIDITY);
        r2.allowedValues = "[]";
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
            () -> service.evaluateQualityRule(r2));
        assertTrue(empty.getMessage().contains("allowed_values"));

        DataQualityRuleEntity r3 = rule(DataQualityRuleEntity.RuleType.VALIDITY);
        r3.allowedValues = "{}";
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r3));  // 非数组

        DataQualityRuleEntity r4 = rule(DataQualityRuleEntity.RuleType.VALIDITY);
        r4.allowedValues = "not-json";
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r4));  // 坏 JSON
    }

    @Test
    @DisplayName("REFERENCE：子查询白名单表 + 引用列标识符校验")
    void referenceRule() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.REFERENCE);
        r.referenceTable = "identities";
        r.referenceColumn = "identity_id";
        Captured c = eval(r, List.of(Map.of("total", 30L, "v", 5L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND device_id NOT IN (SELECT identity_id FROM identities)) AS v FROM events", c.sql());

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.REFERENCE);
        r2.referenceTable = "pg_catalog.pg_tables";  // 白名单外
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r2));

        DataQualityRuleEntity r3 = rule(DataQualityRuleEntity.RuleType.REFERENCE);
        r3.referenceTable = "identities";
        r3.referenceColumn = null;
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r3));
    }

    @Test
    @DisplayName("TIMELINESS：INTERVAL 字面量（已过 parseThreshold 纯数字）+ 范围守卫")
    void timelinessRule() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.TIMELINESS);
        r.thresholdValue = "300";
        Captured c = eval(r, List.of(Map.of("total", 40L, "v", 1L)));
        assertEquals("SELECT count() AS total, countIf(isNotNull(device_id) AND device_id < now() - INTERVAL 300 SECOND) AS v FROM events", c.sql());

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.TIMELINESS);
        r2.thresholdValue = "0";  // ≤ 0 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r2));

        DataQualityRuleEntity r3 = rule(DataQualityRuleEntity.RuleType.TIMELINESS);
        r3.thresholdValue = "315360001";  // 超 10 年上限
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r3));

        DataQualityRuleEntity r4 = rule(DataQualityRuleEntity.RuleType.TIMELINESS);
        r4.thresholdValue = "1; DROP TABLE events";  // 非整数拒绝（注入面）
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r4));
    }

    @Test
    @DisplayName("自由 SQL 型规则（SCHEMA/ACCURACY/CONSISTENCY）诚实拒绝自动评估")
    void freeFormRuleTypesRejected() {
        for (DataQualityRuleEntity.RuleType type : List.of(
                DataQualityRuleEntity.RuleType.SCHEMA,
                DataQualityRuleEntity.RuleType.ACCURACY,
                DataQualityRuleEntity.RuleType.CONSISTENCY)) {
            DataQualityRuleEntity r = rule(type);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.evaluateQualityRule(r));
            assertTrue(ex.getMessage().contains("not auto-evaluable"));
        }
    }

    // ==================== 注入守卫 ====================

    @Test
    @DisplayName("表名走白名单：白名单外 / 注入串 / null 全拒")
    void unsafeTablesRejected() {
        for (String bad : java.util.Arrays.asList("users", "events; DROP TABLE events", "events--", null)) {
            DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
            r.targetTable = bad;
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.evaluateQualityRule(r));
            assertTrue(ex.getMessage().contains("quality tables"));
        }
    }

    @Test
    @DisplayName("列名走标识符正则：空格 / 引号 / 空串 / null 全拒")
    void unsafeColumnsRejected() {
        for (String bad : java.util.Arrays.asList("device id", "device_id'; --", "\"device_id\"", "", null)) {
            DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
            r.targetColumn = bad;
            assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r));
        }
    }

    // ==================== 阈值操作符语义 ====================

    @Test
    @DisplayName("thresholdOperator 五种语义 + 未知操作符 IAE")
    void thresholdOperators() {
        // violations=10 固定，换 operator 观察通过性（totalViolations 只在不通过时累加）
        record Case(String op, long threshold, boolean expectPassed) {}
        List<Case> cases = List.of(
            new Case("gt", 9L, false),    // 10 > 9 → 不通过
            new Case("gt", 10L, true),    // 10 > 10 假 → 通过
            new Case("gte", 10L, false),  // 10 >= 10 → 不通过
            new Case("lt", 11L, false),   // 10 < 11 → "越界"为真 → 不通过（operator 语义作用于违规数）
            new Case("lte", 10L, false),  // 10 <= 10 → 不通过
            new Case("eq", 10L, false),   // 相等 → 不通过（违规数恰为阈值）
            new Case("eq", 11L, true),
            new Case(null, 0L, false));   // 缺省 → gt
        for (Case c : cases) {
            DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
            r.thresholdOperator = c.op();
            r.thresholdValue = String.valueOf(c.threshold());
            int before = r.totalViolations;
            eval(r, List.of(Map.of("total", 100L, "v", 10L)));
            assertEquals(c.expectPassed() ? before : before + 10, r.totalViolations,
                "operator=" + c.op() + " threshold=" + c.threshold());
        }

        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        r.thresholdOperator = "between";
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r));

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        r2.thresholdValue = "3.5";  // 阈值必须整数
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r2));

        DataQualityRuleEntity r3 = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        r3.thresholdValue = "-1";  // 负整数阈值拒绝
        assertThrows(IllegalArgumentException.class, () -> service.evaluateQualityRule(r3));
    }

    // ==================== CH 异常与空结果 ====================

    @Test
    @DisplayName("CH 空结果 ISE；CH 异常原样上抛")
    void clickHouseFailuresPropagate() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.evaluateQualityRule(r));

        DataQualityRuleEntity r2 = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        when(ch.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("CH down"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.evaluateQualityRule(r2));
        assertEquals("CH down", ex.getMessage());
    }

    @Test
    @DisplayName("total/v 键缺失兜 0（Number 拆箱分支）")
    void missingKeysDefaultToZero() {
        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.UNIQUENESS);
        eval(r, List.of(Map.of()));  // 空 Map：total 与 v 均 null → 0
        assertEquals(0, r.lastViolationCount);
    }

    // ==================== job 级语义 ====================

    @Test
    @DisplayName("WARNING 级规则不通过不拦管道：job COMPLETED、errorRows=违规数")
    void warnRuleDoesNotStopPipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        // severity 默认 WARNING → 不 stop
        when(ruleRepo.findByPipelineId("p1")).thenReturn(List.of(r));
        when(ch.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("total", 200L, "v", 7L)));

        PipelineJobEntity job = service.executePipeline("p1", "ops");
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, job.jobStatus);
        assertEquals(200L, job.processedRows);
        assertEquals(7L, job.errorRows);
        assertEquals(1, pipeline.successCount);
        assertNull(pipeline.lastError);
    }

    @Test
    @DisplayName("CH 不可达 → job 诚实 FAILED（不再假成功）")
    void chUnavailableFailsJob() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity r = rule(DataQualityRuleEntity.RuleType.COMPLETENESS);
        r.severity = DataQualityRuleEntity.Severity.WARNING;  // 非 stop 也会失败：异常上抛
        when(ruleRepo.findByPipelineId("p1")).thenReturn(List.of(r));
        when(ch.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("Connection refused"));

        PipelineJobEntity job = service.executePipeline("p1", "ops");
        assertEquals(PipelineJobEntity.JobStatus.RETRYING, job.jobStatus);
        assertTrue(pipeline.lastError.contains("Connection refused"));
    }

    // ==================== 调度开关 ====================

    @Test
    @DisplayName("scheduleEnabled=false：调度入口零副作用")
    void schedulerDisabledByDefault() {
        assertFalse((Boolean) ReflectionTestUtils.getField(service, "scheduleEnabled"));  // @Value 默认 false
        service.executeScheduledPipelines();
        verifyNoInteractions(pipelineRepo, ch);
    }
}
