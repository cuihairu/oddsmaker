package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RetentionEnforcementEntity;
import io.oddsmaker.control.jpa.RetentionEnforcementRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据保留对账测试：effective 链/min 归并/clamp、engine_full TTL 解析矩阵、
 * 漂移下发 ALTER 的 SQL 文本、IN_SYNC 跳过、单表失败隔离、UNKNOWN 防反复 ALTER。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("数据保留策略对账")
class RetentionEnforcementServiceTest {

    @Mock
    private ClickHouseClient clickHouse;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private RetentionEnforcementRepo enforcementRepo;

    @Mock
    private AuditLogService auditLog;

    private RetentionEnforcementService service;

    @BeforeEach
    void setUp() {
        service = new RetentionEnforcementService(
                clickHouse, gameRepo, gameEnvironmentRepo, enforcementRepo, auditLog, true, 90, 7, 3650);
        when(enforcementRepo.save(any(RetentionEnforcementEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static GameEntity game(String id, Integer retentionDays) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.dataRetentionDays = retentionDays;
        return g;
    }

    private static GameEnvironmentEntity env(String id, String gameId, Integer retentionDays) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.dataRetentionDays = retentionDays;
        return e;
    }

    /**
     * 每张受管表一行 system.tables 记录。缺省 = TTL 90 天（与游戏配置一致，IN_SYNC 基线）；
     * overrides 指定漂移天数；noTtl 指定无 TTL 的表；bogus 指定 TTL 文本不可解析的表。
     */
    private List<Map<String, Object>> engineRows(Map<String, Integer> overrides,
                                                 Set<String> noTtl, Set<String> bogus) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : RetentionEnforcementService.TABLE_TTL_COLUMNS.keySet()) {
            String engineFull;
            String column = RetentionEnforcementService.TABLE_TTL_COLUMNS.get(table);
            if (bogus.contains(table)) {
                engineFull = "TTL expression-unparseable";
            } else if (overrides.containsKey(table)) {
                engineFull = "ENGINE MergeTree ORDER BY tuple TTL `" + column
                        + "` + INTERVAL " + overrides.get(table) + " DAY DELETE";
            } else if (noTtl.contains(table)) {
                engineFull = "ENGINE MergeTree ORDER BY tuple";
            } else {
                engineFull = "ENGINE MergeTree ORDER BY tuple TTL `" + column
                        + "` + INTERVAL 90 DAY";
            }
            rows.add(Map.of("table", table, "engine_full", engineFull));
        }
        return rows;
    }

    /** 默认所有游戏/环境 90 天，所有表 TTL=90（全 IN_SYNC 基线），测试按需覆盖 */
    private void stubHappyGames() {
        when(gameRepo.findByDeletedAtIsNull()).thenReturn(List.of(game("g1", 90)));
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g1")).thenReturn(List.of());
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(engineRows(Map.of(), Set.of(), Set.of()));
        when(enforcementRepo.findById(anyString())).thenReturn(Optional.empty());
    }

    /** execute 回调注入 mock Statement 收集 ALTER SQL 文本 */
    private void stubExecuteCollecting(List<String> sqlLog) throws Exception {
        when(clickHouse.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<?> cb = inv.getArgument(0);
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.execute(anyString())).thenAnswer(a -> {
                sqlLog.add(a.getArgument(0));
                return true;
            });
            return cb.doInConnection(conn);
        });
    }

    // ===== 配置解析 =====

    @Test
    @DisplayName("effective：环境覆盖 > 游戏级 > 全局默认")
    void effectiveDaysPrecedence() {
        GameEntity g = game("g1", 180);
        assertThat(service.effectiveDays(g, env("e1", "g1", 30))).isEqualTo(30);
        assertThat(service.effectiveDays(g, env("e1", "g1", null))).isEqualTo(180);
        assertThat(service.effectiveDays(game("g2", null), null)).isEqualTo(90);
    }

    @Test
    @DisplayName("期望值 = 全部游戏与环境的最小值")
    void expectedTakesMinAcrossGamesAndEnvs() {
        when(gameRepo.findByDeletedAtIsNull()).thenReturn(List.of(game("g1", 90), game("g2", 180)));
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g1")).thenReturn(List.of(env("e1", "g1", 30)));
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g2")).thenReturn(List.of());

        assertThat(service.resolveExpectedDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("无未删除游戏时期望值为 null")
    void expectedNullWhenNoGames() {
        when(gameRepo.findByDeletedAtIsNull()).thenReturn(List.of());
        assertThat(service.resolveExpectedDays()).isNull();
    }

    @Test
    @DisplayName("clamp：误配 0 夹到下限 7，超大值夹到上限 3650")
    void clampToBounds() {
        assertThat(service.clamp(0)).isEqualTo(7);
        assertThat(service.clamp(-5)).isEqualTo(7);
        assertThat(service.clamp(36500)).isEqualTo(3650);
        assertThat(service.clamp(90)).isEqualTo(90);

        assertThat(service.resolveExpectedDays()).isNull(); // 未 stub gameRepo → 空列表（宽松 stub 无关紧要）
    }

    // ===== engine_full 解析 =====

    @Test
    @DisplayName("TTL 解析矩阵：标准/反引号+DELETE 后缀/无 TTL")
    void parseTtlDaysMatrix() {
        assertThat(service.parseTtlDays(
                "ENGINE MergeTree ORDER BY tuple TTL event_date + INTERVAL 365 DAY DELETE")).isEqualTo(365);
        assertThat(service.parseTtlDays(
                "ENGINE MergeTree TTL `exposure_date` + INTERVAL 400 DAY")).isEqualTo(400);
        assertThat(service.parseTtlDays("ENGINE MergeTree ORDER BY tuple")).isNull();
    }

    // ===== 对账主链路 =====

    @Test
    @DisplayName("CH 未配置：整体空转零交互")
    void enforceNowSkipsWhenUnavailable() {
        when(clickHouse.isAvailable()).thenReturn(false);
        assertThat(service.enforceNow()).isEmpty();
        verify(clickHouse, never()).query(anyString());
        verify(clickHouse, never()).execute(any());
    }

    @Test
    @DisplayName("无有效配置：全部表 SKIPPED_NO_CONFIG，不下发 ALTER")
    void enforceNowWithoutGamesMarksSkipped() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(gameRepo.findByDeletedAtIsNull()).thenReturn(List.of());
        when(clickHouse.query(anyString())).thenReturn(engineRows(Map.of(), Set.of(), Set.of()));

        List<RetentionEnforcementEntity> results = service.enforceNow();

        assertThat(results).hasSize(RetentionEnforcementService.TABLE_TTL_COLUMNS.size());
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status).isEqualTo(RetentionEnforcementEntity.Status.SKIPPED_NO_CONFIG);
            assertThat(r.desiredDays).isNull();
        });
        verify(clickHouse, never()).execute(any());
        // 无配置状态也落库（前端可见"无有效配置"），但零 ALTER 零审计
        verify(enforcementRepo, times(RetentionEnforcementService.TABLE_TTL_COLUMNS.size()))
                .save(any(RetentionEnforcementEntity.class));
        verify(auditLog, never()).log(any(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("漂移修正：无 TTL 与天数漂移下发 ALTER，IN_SYNC 跳过，坏 TTL 与缺席表 UNKNOWN，审计一条")
    void enforceNowAltersDriftedTables() throws Exception {
        Map<String, Integer> overrides = new LinkedHashMap<>();
        overrides.put("events", 365);        // 天数漂移
        List<String> sqlLog = new ArrayList<>();
        stubHappyGames();
        // sessions 无 TTL；funnels TTL 不可解析；risk_actions 表整体缺席
        List<Map<String, Object>> rows = new ArrayList<>(
                engineRows(overrides, Set.of("sessions"), Set.of("funnels")));
        rows.removeIf(r -> "risk_actions".equals(r.get("table")));
        when(clickHouse.query(anyString())).thenReturn(rows);
        stubExecuteCollecting(sqlLog);

        RetentionEnforcementEntity existing = new RetentionEnforcementEntity();
        existing.chTable = "events";
        existing.updateCount = 3;
        when(enforcementRepo.findById(anyString())).thenAnswer(inv ->
                "events".equals(inv.getArgument(0)) ? Optional.of(existing) : Optional.empty());

        List<RetentionEnforcementEntity> results = service.enforceNow();

        // SQL 文本：白名单表/列 + 期望 90 天
        assertThat(sqlLog).containsExactlyInAnyOrder(
                "ALTER TABLE `events` MODIFY TTL `event_date` + INTERVAL 90 DAY",
                "ALTER TABLE `sessions` MODIFY TTL `session_start` + INTERVAL 90 DAY");

        RetentionEnforcementEntity events = results.stream()
                .filter(r -> r.chTable.equals("events")).findFirst().orElseThrow();
        assertThat(events.status).isEqualTo(RetentionEnforcementEntity.Status.UPDATING);
        assertThat(events.actualDays).isEqualTo(90);
        assertThat(events.updateCount).isEqualTo(4);   // 3 + 1
        assertThat(events.lastUpdatedAt).isNotNull();

        assertThat(statusOf(results, "sessions")).isEqualTo(RetentionEnforcementEntity.Status.UPDATING);
        assertThat(statusOf(results, "risk_events")).isEqualTo(RetentionEnforcementEntity.Status.IN_SYNC);
        assertThat(statusOf(results, "funnels")).isEqualTo(RetentionEnforcementEntity.Status.UNKNOWN);
        RetentionEnforcementEntity funnels = results.stream()
                .filter(r -> r.chTable.equals("funnels")).findFirst().orElseThrow();
        assertThat(funnels.errorMessage).contains("unrecognized TTL");

        RetentionEnforcementEntity missing = results.stream()
                .filter(r -> r.chTable.equals("risk_actions")).findFirst().orElseThrow();
        assertThat(missing.status).isEqualTo(RetentionEnforcementEntity.Status.UNKNOWN);
        assertThat(missing.errorMessage).contains("table not found");

        ArgumentCaptor<RetentionEnforcementEntity> saved =
                ArgumentCaptor.forClass(RetentionEnforcementEntity.class);
        verify(enforcementRepo, times(RetentionEnforcementService.TABLE_TTL_COLUMNS.size()))
                .save(saved.capture());
        assertThat(saved.getAllValues()).extracting(r -> r.chTable)
                .containsExactlyInAnyOrderElementsOf(RetentionEnforcementService.TABLE_TTL_COLUMNS.keySet());
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("retention_enforcement"),
                eq("clickhouse_ttl"), eq("ClickHouse TTL"), anyString(),
                eq(AuditLogEntity.AuditResult.SUCCESS), eq("scheduler"), any(), any(),
                any(), any(), any());
    }

    private static RetentionEnforcementEntity.Status statusOf(
            List<RetentionEnforcementEntity> results, String table) {
        return results.stream().filter(r -> r.chTable.equals(table))
                .findFirst().orElseThrow().status;
    }

    @Test
    @DisplayName("全部 IN_SYNC：零 ALTER 零审计，状态仅刷新检查时间")
    void enforceNowAllInSync() {
        stubHappyGames();
        when(enforcementRepo.findById(anyString())).thenReturn(Optional.empty());

        List<RetentionEnforcementEntity> results = service.enforceNow();

        assertThat(results).allSatisfy(r ->
                assertThat(r.status).isEqualTo(RetentionEnforcementEntity.Status.IN_SYNC));
        verify(clickHouse, never()).execute(any());
        verify(auditLog, never()).log(any(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("单表失败隔离：ALTER 抛异常仅该表 FAILED，其余继续")
    void enforceNowIsolatesPerTableFailure() throws Exception {
        // 第一张受管表 events 漂移且 execute 失败，sessions 漂移且成功
        Map<String, Integer> overrides = new LinkedHashMap<>();
        overrides.put("events", 365);
        stubHappyGames();
        // events 漂移且 execute 抛异常；sessions 无 TTL 漂移且成功
        when(clickHouse.query(anyString())).thenReturn(engineRows(overrides, Set.of("sessions"), Set.of()));
        List<String> sqlLog = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(clickHouse.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("clickhouse down");
            }
            ConnectionCallback<?> cb = inv.getArgument(0);
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.execute(anyString())).thenAnswer(a -> {
                sqlLog.add(a.getArgument(0));
                return true;
            });
            return cb.doInConnection(conn);
        });

        List<RetentionEnforcementEntity> results = service.enforceNow();

        RetentionEnforcementEntity events = results.stream()
                .filter(r -> r.chTable.equals("events")).findFirst().orElseThrow();
        assertThat(events.status).isEqualTo(RetentionEnforcementEntity.Status.FAILED);
        assertThat(events.errorMessage).isEqualTo("clickhouse down");
        assertThat(sqlLog).containsExactly(
                "ALTER TABLE `sessions` MODIFY TTL `session_start` + INTERVAL 90 DAY");
        assertThat(statusOf(results, "sessions")).isEqualTo(RetentionEnforcementEntity.Status.UPDATING);
    }

    // ===== 状态查询与 upsert =====

    @Test
    @DisplayName("状态查询：isConfigured/isEnabled/listStates 直通")
    void stateQueries() {
        when(clickHouse.isAvailable()).thenReturn(true);
        RetentionEnforcementEntity one = new RetentionEnforcementEntity();
        one.chTable = "events";
        when(enforcementRepo.findAllByOrderByChTable()).thenReturn(List.of(one));

        assertThat(service.isConfigured()).isTrue();
        assertThat(service.isEnabled()).isTrue();
        assertThat(service.listStates()).containsExactly(one);
    }

    @Test
    @DisplayName("upsert：非 UPDATING 轮次保留历史累计修正次数")
    void upsertPreservesUpdateCount() {
        stubHappyGames();
        RetentionEnforcementEntity existing = new RetentionEnforcementEntity();
        existing.chTable = "events";
        existing.updateCount = 5;
        when(enforcementRepo.findById(anyString())).thenAnswer(inv ->
                "events".equals(inv.getArgument(0)) ? Optional.of(existing) : Optional.empty());

        List<RetentionEnforcementEntity> results = service.enforceNow();

        RetentionEnforcementEntity events = results.stream()
                .filter(r -> r.chTable.equals("events")).findFirst().orElseThrow();
        assertThat(events.status).isEqualTo(RetentionEnforcementEntity.Status.IN_SYNC);
        assertThat(events.updateCount).isEqualTo(5);
        verify(clickHouse, never()).execute(any());
    }

    // ===== 调度门 =====

    @Test
    @DisplayName("scheduled：enabled=false 零交互（默认关闭防误清数据）")
    void scheduledDisabledDoesNothing() {
        RetentionEnforcementService disabled = new RetentionEnforcementService(
                clickHouse, gameRepo, gameEnvironmentRepo, enforcementRepo, auditLog, false, 90, 7, 3650);
        disabled.scheduledEnforce();
        verify(clickHouse, never()).isAvailable();
        verify(gameRepo, never()).findByDeletedAtIsNull();
    }

    @Test
    @DisplayName("scheduled：enabled=true 走 enforceNow（CH 未配置空转）")
    void scheduledEnabledRuns() {
        when(clickHouse.isAvailable()).thenReturn(false);
        service.scheduledEnforce();
        verify(clickHouse).isAvailable();
    }

    @Test
    @DisplayName("分支对侧：无环境且无游戏 → 全局默认；engine_full 行缺键被跳过")
    void retentionCounterSides() {
        assertThat(service.effectiveDays(null, null)).isEqualTo(90);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("table", "t1");
        full.put("engine_full", "ENGINE X");
        rows.add(full);
        rows.add(Map.of("table", "t2"));            // engine_full 缺键 → null 侧
        rows.add(Map.of("engine_full", "ENGINE"));  // table 缺键 → null 侧
        when(clickHouse.query(anyString())).thenReturn(rows);
        @SuppressWarnings("unchecked")
        Map<String, String> fulls = (Map<String, String>)
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "loadEngineFulls");
        assertThat(fulls).containsOnlyKeys("t1");
    }

}
