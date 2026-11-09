package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：Cohort 创建全参/计算主流程/状态校验/结果解析三分支、批量待计算/游戏统计/查询列表。与 CohortServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesCohortTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CohortRepo cohortRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ClickHouseClient clickHouse;

    @InjectMocks
    private CohortService cohortService;

    private void stubCohortClickHouse() {
        lenient().when(clickHouse.isAvailable()).thenReturn(true);
        lenient().when(clickHouse.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("INTERVAL")) {
                return List.of();
            }
            return List.of(Map.of("first_bucket", "2026-09-15 00:00:00", "size", 42L));
        });
    }

    private CohortEntity cohort(String id, String name, CohortEntity.CohortStatus status) {
        CohortEntity c = new CohortEntity();
        c.id = id;
        c.gameId = "g";
        c.name = name;
        c.cohortType = CohortEntity.CohortType.ACQUISITION;
        c.startDate = LocalDate.now().minusDays(7);
        c.endDate = LocalDate.now();
        c.status = status;
        return c;
    }

    @Test
    @DisplayName("Cohort：创建全参/计算主流程/状态校验/结果解析三分支")
    void cohortCreateCalculateAndResults() {
        lenient().when(cohortRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(cohort("c0", "dup", CohortEntity.CohortStatus.PENDING)));
        lenient().when(cohortRepo.findByGameIdAndName(eq("g2"), anyString())).thenReturn(Optional.empty());
        lenient().when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity pending = cohort("c1", "new-cohort", CohortEntity.CohortStatus.PENDING);
        CohortEntity completed = cohort("c2", "done", CohortEntity.CohortStatus.COMPLETED);
        completed.resultData = "{\"cohortCount\":42}";
        CohortEntity badJson = cohort("c3", "bad", CohortEntity.CohortStatus.COMPLETED);
        badJson.resultData = "not-json";
        lenient().when(cohortRepo.findById("c1")).thenReturn(Optional.of(pending));
        lenient().when(cohortRepo.findById("c2")).thenReturn(Optional.of(completed));
        lenient().when(cohortRepo.findById("c3")).thenReturn(Optional.of(badJson));
        lenient().when(cohortRepo.findById("none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> cohortService.createCohort(
            "g", null, "dup", "Dup", null, CohortEntity.CohortType.BEHAVIORAL,
            LocalDate.now(), LocalDate.now().plusDays(7), "week", "engagement", "session_count",
            null, null, "ops"));

        CohortEntity created = cohortService.createCohort(
            "g2", "env", "fresh", "Fresh", "desc", null,
            LocalDate.now(), LocalDate.now().plusDays(30), null, null, null,
            Map.of("event", "login"), List.of(1, 7, 30), "ops");
        assertEquals("fresh", created.name);
        assertEquals(CohortEntity.CohortType.ACQUISITION, created.cohortType);
        assertEquals("day", created.timeUnit);
        assertEquals("retention", created.analysisType);
        assertEquals("return_rate", created.metricType);
        assertEquals(CohortEntity.CohortStatus.PENDING, created.status);
        assertTrue(created.behaviorDefinition.contains("login"));
        assertTrue(created.retentionPeriods.contains("7"));

        assertEquals("no_results", cohortService.getCohortResults("c1").get("status"));
        assertThrows(IllegalArgumentException.class, () -> cohortService.calculateCohort("none"));
        assertThrows(IllegalStateException.class, () -> cohortService.calculateCohort("c2"));

        stubCohortClickHouse();
        CohortEntity calculated = cohortService.calculateCohort("c1");
        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
        // 真实查询返回的群体规模（旧实现是 Math.random 下限 1000 的假数）
        assertEquals(42L, calculated.cohortCount);
        assertNotNull(calculated.resultData);
        assertNotNull(calculated.resultSummary);
        assertEquals(Long.valueOf(1L), calculated.totalCalculations);

        Map<String, Object> results = cohortService.getCohortResults("c2");
        assertEquals(42, results.get("cohortCount"));
        assertEquals("parse_error", cohortService.getCohortResults("c3").get("status"));
        assertThrows(IllegalArgumentException.class, () -> cohortService.getCohortResults("none"));
        assertEquals("c2", cohortService.getCohort("c2").id);
    }

    @Test
    @DisplayName("Cohort：批量待计算、游戏统计与查询列表")
    void cohortBatchAndStats() {
        lenient().when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity p1 = cohort("c1", "p1", CohortEntity.CohortStatus.PENDING);
        CohortEntity done = cohort("c2", "done", CohortEntity.CohortStatus.COMPLETED);
        done.cohortCount = 500L;
        done.analysisType = "retention";
        lenient().when(cohortRepo.findPending()).thenReturn(List.of(p1));
        lenient().when(cohortRepo.findById("c1")).thenReturn(Optional.of(p1));
        stubCohortClickHouse();

        cohortService.processPendingCohorts();
        assertEquals(CohortEntity.CohortStatus.COMPLETED, p1.status);

        lenient().when(cohortRepo.findByGameId("g")).thenReturn(List.of(p1, done));
        lenient().when(cohortRepo.findCompletedByGameId("g")).thenReturn(List.of(done));
        lenient().when(cohortRepo.findRecent("g")).thenReturn(List.of(done));
        lenient().when(cohortRepo.search(eq("g"), anyString())).thenReturn(List.of(done));

        Map<String, Object> stats = cohortService.getCohortStats("g");
        assertEquals(2, stats.get("totalCohorts"));
        assertEquals(1, stats.get("completedCohorts"));
        assertEquals(0L, stats.get("pendingCohorts"));
        assertEquals(500L, stats.get("totalUsersAnalyzed"));

        assertEquals(2, cohortService.getGameCohorts("g").size());
        assertEquals(1, cohortService.getCompletedCohorts("g").size());
        assertEquals(1, cohortService.getRecentCohorts("g").size());
        assertEquals(1, cohortService.searchCohorts("g", "do").size());
    }

}
