package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 审计日志 Service 测试：各资源域的便捷记录方法组装正确字段并落库。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审计日志 Service 测试")
class AuditLogServiceTest {

    @Mock
    private AuditLogRepo auditLogRepo;

    @InjectMocks
    private AuditLogService service;

    @Test
    @DisplayName("log：直接保存并返回")
    void logSaves() {
        when(auditLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuditLogEntity entity = new AuditLogEntity();
        assertEquals(entity, service.log(entity));
    }

    @Test
    @DisplayName("登录/登出/失败登录：action 与主体字段")
    void authLogs() {
        when(auditLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity login = service.logLogin("u1", "alice", "1.2.3.4", "ua");
        assertEquals(AuditLogEntity.AuditAction.LOGIN, login.action);
        assertEquals("u1", login.userId);

        AuditLogEntity failed = service.logLoginFailed("bob", "1.2.3.4", "ua", "bad pw");
        assertEquals(AuditLogEntity.AuditStatus.FAILURE, failed.status);

        AuditLogEntity logout = service.logLogout("u1", "alice", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.LOGOUT, logout.action);
    }

    @Test
    @DisplayName("增删改/导出/集成便捷方法：action 与资源字段组装")
    void resourceLogs() {
        when(auditLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(AuditLogEntity.AuditAction.CREATE,
            service.logCreate("game", "g1", "Demo", "ops", "ops", null, (Map<String, Object>) null).action);
        assertEquals(AuditLogEntity.AuditAction.UPDATE,
            service.logUpdate("game", "g1", "Demo", "ops", "ops", null, Map.of("gameId", "g1")).action);
        assertEquals("g1", service.logUpdate("game", "g1", "Demo", "ops", "ops", null,
            Map.of("gameId", "g1")).gameId);
        assertEquals(AuditLogEntity.AuditAction.DELETE,
            service.logDelete("game", "g1", "Demo", "ops", "ops", (String) null).action);
        assertEquals("player_export", service.logDataExport(
            "player_export", "pex_1", "f.json", "ops", "ops", null).resourceType);
        assertNotNull(service.logIntegrationCreate("i1", "hook", "webhook", "ops", "g"));
        assertNotNull(service.logIntegrationCall("i1", "risk_action", "ok", "g"));
        assertNotNull(service.logIntegrationDisable("i1", "hook", "g"));
        assertNotNull(service.logIntegrationDelete("i1", "hook", "g"));
    }

    @Test
    @DisplayName("findById：不存在抛异常")
    void findById() {
        when(auditLogRepo.findById(1L)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.findById(1L));
    }
}
