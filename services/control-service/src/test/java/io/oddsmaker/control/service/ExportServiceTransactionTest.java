package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ExportService 事务毒化回归（T-REV05）：类级 @Transactional 下 catch 内 markAsFailed+save
 * 再抛 RuntimeException，API 代理路径整个回滚、FAILED 标记丢失、作业卡 PENDING。
 * 修复后 processExportJob 无外层事务，各 save 独立提交，FAILED 在抛异常后仍持久化。
 */
@DisplayName("ExportService 事务毒化回归")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:exporttx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-smoke",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.kafka.listener.auto-startup=false",
        "management.health.kafka.enabled=false",
        "oddsmaker.clickhouse.url=",
        "oddsmaker.export.storage-dir=target/export-tx-test",
})
class ExportServiceTransactionTest {

    @Autowired
    private ExportService exportService;

    @Autowired
    private ExportJobRepo exportJobRepo;

    @MockitoBean
    private ClickHouseClient clickHouse;

    @MockitoBean
    private WebhookService webhookService;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("外层事务抛错回滚后 FAILED 标记仍持久化")
    void failedMarkSurvivesRethrow() {
        ExportJobEntity job = new ExportJobEntity();
        job.id = "ex_txtest";
        job.gameId = "g1";
        job.userId = "u1";
        job.exportType = "events";
        job.exportFormat = "csv";
        job.exportStatus = ExportJobEntity.ExportStatus.PENDING;
        job.fileName = "events_g1.csv";
        exportJobRepo.save(job);

        Mockito.doThrow(new RuntimeException("CH down")).when(clickHouse).query(anyString(), any(Object[].class));

        // 经 Spring 代理直调（与 ExportController 同路径）：类级 @Transactional 若仍存在，
        // 代理会开启事务并在 rethrow 时整体回滚，FAILED 标记丢失（T-REV05）。
        assertThrows(RuntimeException.class, () -> exportService.processExportJob(job.id));

        ExportJobEntity reloaded = exportJobRepo.findById(job.id).orElseThrow();
        assertEquals(ExportJobEntity.ExportStatus.FAILED, reloaded.exportStatus);
        assertTrue(reloaded.errorMessage.contains("CH down"));
    }
}
