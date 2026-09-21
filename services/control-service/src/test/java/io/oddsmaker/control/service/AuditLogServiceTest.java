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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        when(auditLogRepo.findById("1")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.findById("1"));
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("便捷方法 username null 回落 userId；metadata 各侧命中 gameId/environment")
    void usernameNullFallsBackAndMetadataSides() {
        when(auditLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // username 三元 null 侧：logCreate（resourceType-first，metadata 含 gameId）
        AuditLogEntity c = service.logCreate("game", "g1", "Demo", "ops1", null, null, Map.of("gameId", "g9"));
        assertEquals("ops1", c.username);
        assertEquals("g9", c.gameId);
        // logCreate：metadata 非 null 但无 gameId 键（gameId == null 跳过侧）
        AuditLogEntity cNoGame = service.logCreate("game", "g1", "Demo", "ops1", null, null, Map.of("k", "v"));
        assertNull(cNoGame.gameId);
        // logUpdate：metadata null 早退侧
        AuditLogEntity u = service.logUpdate("game", "g1", "Demo", "ops1", null, null, null);
        assertEquals("ops1", u.username);
        assertNull(u.gameId);
        // logUpdate：metadata 非 null 但无 gameId 键（gameId == null 跳过侧）
        AuditLogEntity uNoGame = service.logUpdate("game", "g1", "Demo", "ops1", null, null, Map.of("k", "v"));
        assertNull(uNoGame.gameId);
        // logDelete / logDataExport 三元 null 侧
        assertEquals("ops1", service.logDelete("game", "g1", "Demo", "ops1", null, null).username);
        assertEquals("ops1", service.logDataExport("player_export", "pex_2", "f.json", "ops1", null, null).username);

        // 通用 12 参 log：metadata 含 gameId+environment，result=SUCCESS
        AuditLogEntity g1 = service.log(AuditLogEntity.AuditAction.UPDATE, "risk_case", "rc_1", "案例",
            "详情", AuditLogEntity.AuditResult.SUCCESS, "admin", "old", "new", "1.1.1.1", "ua",
            Map.of("gameId", "g_demo", "environment", "env_prod"));
        assertEquals("g_demo", g1.gameId);
        assertEquals("env_prod", g1.environment);
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, g1.status);

        // metadata null + result=FAILURE
        AuditLogEntity g2 = service.log(AuditLogEntity.AuditAction.DELETE, "risk_case", "rc_1", "案例",
            null, AuditLogEntity.AuditResult.FAILURE, "admin", null, null, null, null, null);
        assertNull(g2.gameId);
        assertNull(g2.environment);
        assertEquals(AuditLogEntity.AuditStatus.FAILURE, g2.status);

        // metadata 无 gameId 键（get 返回 null 侧）+ result=PARTIAL
        AuditLogEntity g3 = service.log(AuditLogEntity.AuditAction.READ, "risk_case", "rc_1", "案例",
            null, AuditLogEntity.AuditResult.PARTIAL, "admin", null, null, null, null, Map.of());
        assertNull(g3.gameId);
        assertEquals(AuditLogEntity.AuditStatus.PARTIAL, g3.status);

        // 集成调用 result="SUCCESS" 命中 SUCCESS 侧（既有用例已走非 SUCCESS 侧）
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS,
            service.logIntegrationCall("i1", "risk_action", "SUCCESS", "g").status);
    }
}
