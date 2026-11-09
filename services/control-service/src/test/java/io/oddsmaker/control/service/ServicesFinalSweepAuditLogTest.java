package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：审计日志通用 12 参重载与 userId-first 便捷方法、权限变更记录、findById 与分页委托、专项查询与统计。与 AuditLogServiceTest / GameServiceTest / MLModelServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志 Service 深度测试（重载/查询/统计）")
class ServicesFinalSweepAuditLogTest {

    @Mock
    private AuditLogRepo auditLogRepo;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    @DisplayName("审计：通用 12 参重载按 result 映射状态并提取 metadata")
    void auditLogGenericOverloadMapsResultAndMetadata() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity ok = auditLogService.log(
            AuditLogEntity.AuditAction.BLOCK, "block_list", "bl_1", "device-1", "auto block",
            AuditLogEntity.AuditResult.SUCCESS, "risk-automation", "active", "blocked",
            "10.0.0.1", "curl", Map.of("gameId", "game_demo", "environment", "prod"));
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, ok.status);
        assertEquals("risk-automation", ok.userId);
        assertEquals("game_demo", ok.gameId);
        assertEquals("prod", ok.environment);
        assertEquals("active", ok.oldValue);
        assertEquals("blocked", ok.newValue);

        AuditLogEntity failure = auditLogService.log(
            AuditLogEntity.AuditAction.BLOCK, "block_list", "bl_2", null, null,
            AuditLogEntity.AuditResult.FAILURE, "op", null, null, null, null, null);
        assertEquals(AuditLogEntity.AuditStatus.FAILURE, failure.status);
        assertNull(failure.gameId);

        AuditLogEntity partial = auditLogService.log(
            AuditLogEntity.AuditAction.READ, "report", "r_1", null, null,
            AuditLogEntity.AuditResult.PARTIAL, "op", null, null, null, null, null);
        assertEquals(AuditLogEntity.AuditStatus.PARTIAL, partial.status);
    }

    @Test
    @DisplayName("审计：userId-first 的增删改/导出便捷方法字段组装")
    void auditLogUserFirstConvenienceMethods() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity created = auditLogService.logCreate(
            "u1", "alice", "report", "r_1", "Daily", "{\"k\":1}", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.CREATE, created.action);
        assertEquals("u1", created.userId);
        assertEquals("alice", created.username);
        assertEquals("{\"k\":1}", created.newValue);
        assertEquals("1.2.3.4", created.ipAddress);

        AuditLogEntity updated = auditLogService.logUpdate(
            "u1", "alice", "report", "r_1", "Daily", "v1", "v2", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.UPDATE, updated.action);
        assertEquals("v1", updated.oldValue);
        assertEquals("v2", updated.newValue);

        AuditLogEntity deleted = auditLogService.logDelete(
            "u1", "alice", "report", "r_1", "Daily", "v1", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.DELETE, deleted.action);
        assertEquals("v1", deleted.oldValue);
    }

    @Test
    @DisplayName("审计：logPermissionChange 以目标用户为资源")
    void auditLogPermissionChange() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity entity = auditLogService.logPermissionChange(
            "admin", "root", "u2", "bob", "GRANT_ROLE", "viewer", "operator", "1.2.3.4");

        assertEquals(AuditLogEntity.AuditAction.GRANT_ROLE, entity.action);
        assertEquals("u2", entity.resourceId);
        assertEquals("bob", entity.resourceName);
        assertEquals("viewer", entity.oldValue);
        assertEquals("operator", entity.newValue);
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, entity.status);
    }

    @Test
    @DisplayName("审计：findById 命中返回实体")
    void auditLogFindByIdPresent() {
        AuditLogEntity entity = new AuditLogEntity();
        entity.id = "9";
        lenient().when(auditLogRepo.findById("9")).thenReturn(Optional.of(entity));

        assertSame(entity, auditLogService.findById("9"));
    }

    @Test
    @DisplayName("审计：分页查询方法透传 Repo 参数")
    void auditLogPagedQueryDelegation() {
        AuditLogEntity entity = new AuditLogEntity();
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entity));
        Pageable pageable = PageRequest.of(0, 10);

        lenient().when(auditLogRepo.findByUserIdOrderByCreatedAtDesc("u1", pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByActionOrderByCreatedAtDesc(AuditLogEntity.AuditAction.CREATE, pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByStatusOrderByCreatedAtDesc(AuditLogEntity.AuditStatus.FAILURE, pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByGameIdOrderByCreatedAtDesc("g1", pageable)).thenReturn(page);

        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        lenient().when(auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable)).thenReturn(page);

        assertSame(page, auditLogService.findByUserId("u1", pageable));
        assertSame(page, auditLogService.findByAction(AuditLogEntity.AuditAction.CREATE, pageable));
        assertSame(page, auditLogService.findByStatus(AuditLogEntity.AuditStatus.FAILURE, pageable));
        assertSame(page, auditLogService.findByGameId("g1", pageable));
        assertSame(page, auditLogService.findByTimeRange(start, end, pageable));
    }

    @Test
    @DisplayName("审计：搜索专用查询")
    void auditLogSpecialQueries() {
        Page<AuditLogEntity> page = new PageImpl<>(List.of(new AuditLogEntity()));
        Pageable pageable = PageRequest.of(0, 5);

        lenient().when(auditLogRepo.searchLogs("alice", pageable)).thenReturn(page);

        assertSame(page, auditLogService.searchLogs("alice", pageable));
    }

    @Test
    @DisplayName("审计：getAuditStatistics 聚合五项统计")
    void auditLogStatistics() {
        lenient().when(auditLogRepo.countLogsSince(any(LocalDateTime.class))).thenReturn(42L);
        lenient().when(auditLogRepo.countActionsByUser(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByResourceType(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByActionType(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByHour(any(LocalDateTime.class))).thenReturn(List.of());

        Map<String, Object> stats = auditLogService.getAuditStatistics(7);

        assertEquals(42L, stats.get("totalLogs"));
        assertTrue(((List<?>) stats.get("actionsByUser")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByResourceType")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByActionType")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByHour")).isEmpty());
    }

}
