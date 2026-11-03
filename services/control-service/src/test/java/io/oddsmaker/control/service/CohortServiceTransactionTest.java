package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * CohortService 事务毒化回归（T-REV06）：类级 @Transactional 下 catch 内 markAsFailed+save
 * 再抛 RuntimeException，API 代理路径整个回滚、FAILED 标记丢失、作业卡 CALCULATING。
 * 修复后 calculateCohort 无外层事务，各 save 独立提交，FAILED 在抛异常后仍持久化。
 */
@DisplayName("CohortService 事务毒化回归")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:cohorttx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-smoke",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.kafka.listener.auto-startup=false",
        "management.health.kafka.enabled=false",
        "oddsmaker.clickhouse.url=",
})
class CohortServiceTransactionTest {

    @Autowired
    private CohortService cohortService;

    @Autowired
    private CohortRepo cohortRepo;

    @MockitoBean
    private ClickHouseClient clickHouse;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Test
    @DisplayName("代理直调 rethrow 后 FAILED 标记仍持久化")
    void failedMarkSurvivesRethrow() {
        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch_txtest";
        cohort.gameId = "g1";
        cohort.environmentId = null;
        cohort.name = "tx-test";
        cohort.cohortType = CohortEntity.CohortType.ACQUISITION;
        cohort.analysisType = "retention";
        cohort.timeUnit = "day";
        cohort.startDate = java.time.LocalDate.of(2026, 1, 1);
        cohort.endDate = java.time.LocalDate.of(2026, 1, 31);
        cohort.status = CohortEntity.CohortStatus.PENDING;
        cohortRepo.save(cohort);

        when(clickHouse.isAvailable()).thenReturn(false);

        // 经 Spring 代理直调（与 CohortController 同路径）：类级 @Transactional 若仍存在，
        // 代理会开启事务并在 rethrow 时整体回滚，FAILED 标记丢失（T-REV06）。
        assertThrows(RuntimeException.class, () -> cohortService.calculateCohort(cohort.id));

        CohortEntity reloaded = cohortRepo.findById(cohort.id).orElseThrow();
        assertEquals(CohortEntity.CohortStatus.FAILED, reloaded.status);
        assertTrue(reloaded.resultSummary.contains("ClickHouse is not configured"));
    }
}
