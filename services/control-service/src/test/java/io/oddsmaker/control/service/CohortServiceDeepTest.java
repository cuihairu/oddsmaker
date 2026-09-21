package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 同期群留存引擎深度测试：ACQUISITION+retention 的 CH 首见分桶/逐周期回访 SQL 编译、
 * 语义收窄（非 ACQUISITION/非 retention 诚实 FAILED）、坏配置/CH 不可达诚实失败、结果写回真实值。
 */
@DisplayName("同期群引擎：留存 SQL 编译 + 语义收窄 + 诚实失败")
class CohortServiceDeepTest {

    private CohortRepo cohortRepo;
    private GameEnvironmentRepo gameEnvironmentRepo;
    private ClickHouseClient clickHouse;
    private CohortService service;

    @BeforeEach
    void setUp() {
        cohortRepo = mock(CohortRepo.class);
        gameEnvironmentRepo = mock(GameEnvironmentRepo.class);
        clickHouse = mock(ClickHouseClient.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        CohortService real = new CohortService();
        ReflectionTestUtils.setField(real, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(real, "gameEnvironmentRepo", gameEnvironmentRepo);
        ReflectionTestUtils.setField(real, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(real, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(real, "clickHouse", clickHouse);
        service = real;

        lenient().when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(clickHouse.isAvailable()).thenReturn(true);
        lenient().when(clickHouse.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("INTERVAL 1 ")) {
                // 含未知桶行：引擎应跳过而非崩溃
                return List.of(
                    Map.of("first_bucket", "2026-09-01 00:00:00", "returned", 30L),
                    Map.of("first_bucket", "ghost-bucket", "returned", 99L));
            }
            if (sql.contains("INTERVAL")) {
                return List.of();
            }
            return List.of(
                Map.of("first_bucket", "2026-09-01 00:00:00", "size", 100L),
                Map.of("first_bucket", "2026-09-02 00:00:00", "size", 50L));
        });
    }

    private CohortEntity cohort() {
        CohortEntity c = new CohortEntity();
        c.id = "ch_1";
        c.gameId = "g1";
        c.name = "sep-signups";
        c.cohortType = CohortEntity.CohortType.ACQUISITION;
        c.analysisType = "retention";
        c.timeUnit = "day";
        c.startDate = LocalDate.of(2026, 9, 1);
        c.endDate = LocalDate.of(2026, 9, 7);
        c.retentionPeriods = "[1, 7]";
        return c;
    }

    private CohortEntity run(CohortEntity c) {
        when(cohortRepo.findById("ch_1")).thenReturn(Optional.of(c));
        return service.calculateCohort("ch_1");
    }

    /** 失败路径：calculateCohort 落 FAILED 后向调用方上抛 RuntimeException，断言异常并回读实体。 */
    private CohortEntity runFailing(CohortEntity c) {
        when(cohortRepo.findById("ch_1")).thenReturn(Optional.of(c));
        assertThrows(RuntimeException.class, () -> service.calculateCohort("ch_1"));
        assertEquals(CohortEntity.CohortStatus.FAILED, c.status);
        return c;
    }

    @Test
    @DisplayName("主路径：首见分桶 + 逐周期回访，cohortCount=Σsize，rate=returned/size")
    void retentionMainPath() {
        CohortEntity calculated = run(cohort());

        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
        assertEquals(150L, calculated.cohortCount);
        assertNotNull(calculated.resultData);
        assertNotNull(calculated.resultSummary);
        assertTrue(calculated.lastCalculationTimeMs >= 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) service.getCohortResults("ch_1");
        assertEquals(150, result.get("cohortCount"));
        assertEquals("retention", result.get("analysisType"));
        assertEquals("day", result.get("timeUnit"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) result.get("buckets");
        assertEquals(2, buckets.size());
        // resultData 走 JSON 往返，数值反序列化为 Integer——用 Number 比较而非 Long 严格相等
        assertEquals(100, ((Number) buckets.get(0).get("size")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> retention1 = (Map<String, Object>) buckets.get(0).get("retention");
        @SuppressWarnings("unchecked")
        Map<String, Object> day1 = (Map<String, Object>) retention1.get("1");
        assertEquals(30, ((Number) day1.get("count")).intValue());
        assertEquals(0.3, (double) day1.get("rate"), 1e-9);

        // 桶 2 无回访行 → 不出键（查询没返回该桶）
        @SuppressWarnings("unchecked")
        Map<String, Object> retention2 = (Map<String, Object>) buckets.get(1).get("retention");
        assertFalse(retention2.containsKey("7"));
    }

    @Test
    @DisplayName("SQL 编译：SUBJECT 三级回落、窗口参数 [game, start, end]、INTERVAL 纯数字拼接")
    void sqlCompilation() {
        run(cohort());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        // 1 桶查询 + 2 周期回访
        verify(clickHouse, times(3)).query(sqlCap.capture(), argsCap.capture());
        List<String> sqls = sqlCap.getAllValues();

        String bucketSql = sqls.get(0);
        assertTrue(bucketSql.contains("if(player_id != '', player_id, if(user_id != '', user_id, device_id)) AS subject"), bucketSql);
        assertTrue(bucketSql.contains("min(toStartOfDay(ts_server)) AS first_bucket"), bucketSql);
        assertTrue(bucketSql.contains("FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ?"), bucketSql);
        assertTrue(bucketSql.contains("GROUP BY first_bucket ORDER BY first_bucket"), bucketSql);
        assertFalse(bucketSql.contains("INTERVAL"), bucketSql);
        assertFalse(bucketSql.contains("AND environment"), bucketSql);

        Object[] args = argsCap.getAllValues().get(0);
        assertEquals(3, args.length);
        assertEquals("g1", args[0]);
        assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 0, 0), args[1]);
        // endDate 含当天：end = 9/8 00:00
        assertEquals(java.time.LocalDateTime.of(2026, 9, 8, 0, 0), args[2]);

        String day1Sql = sqls.get(1);
        assertTrue(day1Sql.contains("INNER JOIN"), day1Sql);
        assertTrue(day1Sql.contains("ON f.subject = a.subject"), day1Sql);
        assertTrue(day1Sql.contains("f.first_bucket + INTERVAL 1 DAY"), day1Sql);
        assertArrayEquals(args, argsCap.getAllValues().get(1));
        assertTrue(sqls.get(2).contains("INTERVAL 7 DAY"), sqls.get(2));
    }

    @Test
    @DisplayName("timeUnit 映射：week→toMonday+INTERVAL N WEEK，month→toStartOfMonth+MONTH")
    void timeUnitMapping() {
        CohortEntity weekly = cohort();
        weekly.timeUnit = "week";
        run(weekly);
        CohortEntity monthly = cohort();
        monthly.timeUnit = "month";
        run(monthly);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        // 两个 cohort 各 1 桶 + 2 周期 = 6 次
        verify(clickHouse, times(6)).query(sqlCap.capture(), any(Object[].class));
        List<String> sqls = sqlCap.getAllValues();
        assertTrue(sqls.get(0).contains("min(toMonday(ts_server))"), sqls.get(0));
        assertTrue(sqls.get(1).contains("INTERVAL 1 WEEK"), sqls.get(1));
        assertTrue(sqls.get(3).contains("min(toStartOfMonth(ts_server))"), sqls.get(3));
        assertTrue(sqls.get(4).contains("INTERVAL 1 MONTH"), sqls.get(4));
    }

    @Test
    @DisplayName("环境过滤：environmentId 映射环境名，SQL 追加 AND environment = ?，args 第 4 位")
    void environmentFilter() {
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env1";
        env.name = "prod";
        when(gameEnvironmentRepo.findById("env1")).thenReturn(Optional.of(env));
        CohortEntity c = cohort();
        c.environmentId = "env1";
        run(c);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse, times(3)).query(sqlCap.capture(), argsCap.capture());
        assertTrue(sqlCap.getAllValues().get(0).contains("AND environment = ?"), sqlCap.getAllValues().get(0));
        assertEquals(4, argsCap.getAllValues().get(0).length);
        assertEquals("prod", argsCap.getAllValues().get(0)[3]);
    }

    @Test
    @DisplayName("语义收窄：BEHAVIORAL 类型与 engagement 分析均诚实 FAILED（无执行定义不造假）")
    void semanticNarrowing() {
        CohortEntity behavioral = cohort();
        behavioral.cohortType = CohortEntity.CohortType.BEHAVIORAL;
        CohortEntity calculated = runFailing(behavioral);
        assertTrue(calculated.resultSummary.contains("ACQUISITION"), calculated.resultSummary);

        CohortEntity engagement = cohort();
        engagement.analysisType = "engagement";
        runFailing(engagement);
        verify(clickHouse, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("坏配置诚实 FAILED：窗口缺失/倒挂、坏 timeUnit、坏周期、环境不存在")
    void badConfigFailsHonest() {
        // 窗口缺失
        CohortEntity noWindow = cohort();
        noWindow.startDate = null;
        runFailing(noWindow);
        assertTrue(noWindow.resultSummary.contains("startDate"), noWindow.resultSummary);
        // 窗口倒挂
        CohortEntity inverted = cohort();
        inverted.startDate = LocalDate.of(2026, 9, 7);
        inverted.endDate = LocalDate.of(2026, 9, 1);
        runFailing(inverted);
        // 坏 timeUnit
        CohortEntity badUnit = cohort();
        badUnit.timeUnit = "quarter";
        runFailing(badUnit);
        // 周期非整数
        CohortEntity badPeriod = cohort();
        badPeriod.retentionPeriods = "[\"week1\"]";
        runFailing(badPeriod);
        // 周期越界（0 与 3651）
        CohortEntity zeroPeriod = cohort();
        zeroPeriod.retentionPeriods = "[0, 7]";
        runFailing(zeroPeriod);
        assertTrue(zeroPeriod.resultSummary.contains("range"), zeroPeriod.resultSummary);
        CohortEntity hugePeriod = cohort();
        hugePeriod.retentionPeriods = "[3651]";
        runFailing(hugePeriod);
        // 环境不存在
        CohortEntity badEnv = cohort();
        badEnv.environmentId = "ghost";
        when(gameEnvironmentRepo.findById("ghost")).thenReturn(Optional.empty());
        runFailing(badEnv);

        // 全部失败都不触碰 CH
        verify(clickHouse, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("CH 不可达与查询异常诚实 FAILED（resultSummary 留原因，异常向调用方上抛）")
    void clickHouseFailureHonest() {
        when(clickHouse.isAvailable()).thenReturn(false);
        CohortEntity failed = runFailing(cohort());
        assertTrue(failed.resultSummary.contains("ClickHouse"), failed.resultSummary);

        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("CH down"));
        CohortEntity queryFailed = runFailing(cohort());
        assertTrue(queryFailed.resultSummary.contains("CH down"), queryFailed.resultSummary);
    }

    @Test
    @DisplayName("空窗口数据：零桶也 COMPLETED（cohortCount=0），不造假用户数")
    void emptyWindowCompletesHonest() {
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of());
        CohortEntity calculated = run(cohort());
        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
        assertEquals(0L, calculated.cohortCount);
        assertTrue(calculated.resultData.contains("\"cohortCount\":0"), calculated.resultData);
    }

    @Test
    @DisplayName("createCohort：behaviorDefinition 不可序列化 → RuntimeException（不做静默假创建）")
    void createCohortSerializationFailure() {
        when(cohortRepo.findByGameIdAndName("g1", "bad")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.createCohort(
            "g1", null, "bad", "Bad", null, null,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), null, null, null,
            Map.of("k", new Object()), null, "ops"));
        verify(cohortRepo, never()).save(any());
    }

    @Test
    @DisplayName("buildResultSummary：结构异常回退 '{}'（不抛出）")
    void resultSummaryFallback() {
        // buckets 不是 List → ClassCastException → "{}"
        String summary = ReflectionTestUtils.invokeMethod(service, "buildResultSummary",
            Map.of("cohortCount", 1L, "buckets", "not-a-list", "periods", List.of(1)));
        assertEquals("{}", summary);
    }

    @Test
    @DisplayName("实体分支：状态谓词、留存周期三分支、描述格式、类型谓词")
    void entityBranches() {
        CohortEntity c = cohort();
        assertTrue(c.isPending());
        c.markAsCalculating();
        assertTrue(c.isCalculating());
        assertFalse(c.isPending());
        c.markAsCompleted(5L, "{}", "{}", 10L);
        assertTrue(c.isCompleted());

        // getRetentionPeriods 三分支：null → 默认、坏 JSON → 默认、正常解析
        c.retentionPeriods = null;
        assertEquals(List.of(1, 7, 14, 30, 60, 90), c.getRetentionPeriods());
        c.retentionPeriods = "{bad json";
        assertEquals(List.of(1, 7, 14, 30, 60, 90), c.getRetentionPeriods());
        c.retentionPeriods = "[3, 14]";
        assertEquals(List.of(3, 14), c.getRetentionPeriods());

        // 描述：displayName/startDate 缺省回落
        c.displayName = "九月注册";
        assertTrue(c.getCohortDescription().contains("九月注册"));
        c.displayName = null;
        c.startDate = null;
        assertTrue(c.getCohortDescription().contains("no-date"));
        assertTrue(c.getCohortDescription().contains(c.name));

        // 类型谓词
        c.cohortType = CohortEntity.CohortType.ACQUISITION;
        assertTrue(c.isAcquisitionCohort());
        assertFalse(c.isBehavioralCohort());
        c.cohortType = CohortEntity.CohortType.BEHAVIORAL;
        assertTrue(c.isBehavioralCohort());

        // 活跃谓词：COMPLETED 未软删 → 活跃；软删 → 非活跃；归档 → 非活跃
        assertTrue(c.isActive());
        c.deletedAt = LocalDateTime.now();
        assertFalse(c.isActive());
        c.deletedAt = null;
        c.status = CohortEntity.CohortStatus.ARCHIVED;
        assertFalse(c.isActive());
        c.status = CohortEntity.CohortStatus.PENDING;
        assertTrue(c.isActive());
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("createCohort：显式 cohortType/retentionPeriods 保留（非空侧）")
    void createCohortExplicitTypeAndPeriods() {
        when(cohortRepo.findByGameIdAndName("g1", "typed")).thenReturn(Optional.empty());
        service.createCohort("g1", null, "typed", "Typed", null,
            CohortEntity.CohortType.BEHAVIORAL,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), "week", null, null,
            null, List.of(1, 7), "ops");

        ArgumentCaptor<CohortEntity> cap = ArgumentCaptor.forClass(CohortEntity.class);
        verify(cohortRepo).save(cap.capture());
        CohortEntity saved = cap.getValue();
        assertEquals(CohortEntity.CohortType.BEHAVIORAL, saved.cohortType);
        assertEquals("[1,7]", saved.retentionPeriods);
    }

    @Test
    @DisplayName("getCohortStats：cohortCount null 兜 0、analysisType null 剔除")
    void cohortStatsNullSides() {
        CohortEntity done = cohort();
        done.status = CohortEntity.CohortStatus.COMPLETED;
        done.cohortCount = null;      // null 兜 0 侧
        CohortEntity bare = cohort();
        bare.id = "ch_bare";
        bare.analysisType = null;     // filter 剔除侧
        when(cohortRepo.findByGameId("g1")).thenReturn(List.of(done, bare));
        when(cohortRepo.findCompletedByGameId("g1")).thenReturn(List.of(done));

        Map<String, Object> stats = service.getCohortStats("g1");
        assertEquals(2, ((Number) stats.get("totalCohorts")).intValue());
        assertFalse(((Map<?, ?>) stats.get("byAnalysisType")).containsKey(null));

        // 非 null 计数侧
        done.cohortCount = 42L;
        when(cohortRepo.findCompletedByGameId("g1")).thenReturn(List.of(done));
        assertTrue(service.getCohortStats("g1").toString().contains("42"));
    }

    @Test
    @DisplayName("computeCohort 对侧：gameId null、endDate null、timeUnit null、环境空白串")
    void computeCohortNullSides() {
        CohortEntity noGame = cohort();
        noGame.gameId = null;
        runFailing(noGame);

        CohortEntity noEnd = cohort();
        noEnd.endDate = null;
        runFailing(noEnd);

        // timeUnit null → 回落 day 正常计算
        CohortEntity nullUnit = cohort();
        nullUnit.timeUnit = null;
        assertEquals(CohortEntity.CohortStatus.COMPLETED, run(nullUnit).status);

        // environmentId 空白串 → 跳过环境映射与过滤
        CohortEntity blankEnv = cohort();
        blankEnv.environmentId = "   ";
        assertEquals(CohortEntity.CohortStatus.COMPLETED, run(blankEnv).status);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, atLeastOnce()).query(sqlCap.capture(), any(Object[].class));
        assertFalse(sqlCap.getAllValues().stream().anyMatch(s -> s.contains("AND environment")));
    }

    @Test
    @DisplayName("零尺寸桶回访：rate 兜 0.0（size > 0 false 侧）")
    void zeroSizeBucketRateFallsBackToZero() {
        when(clickHouse.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("INTERVAL 1 ")) {
                return List.of(Map.of("first_bucket", "2026-09-01 00:00:00", "returned", 5L));
            }
            if (sql.contains("INTERVAL")) {
                return List.of();
            }
            return List.of(Map.of("first_bucket", "2026-09-01 00:00:00", "size", 0L));
        });
        CohortEntity calculated = run(cohort());
        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
        assertTrue(calculated.resultData.contains("\"rate\":0.0"), calculated.resultData);
    }
}
