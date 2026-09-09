package io.oddsmaker.control.api;

import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.MaintenanceService;
import io.oddsmaker.control.service.PermissionService;
import io.oddsmaker.control.service.SecurityService;
import io.oddsmaker.control.service.UserService;
import io.oddsmaker.control.security.AccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台域 Controller 测试：用户/系统配置/安全/角色分配。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("平台域 Controller 测试")
class PlatformControllersTest {

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Mock
    private AccessGuard accessGuard;

    // ===== 用户 =====

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @Test
    @DisplayName("用户：14 个端点委托")
    void userEndpoints() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("tester", "pw"));
        when(userService.searchUsers(anyString(), any())).thenReturn(org.springframework.data.domain.Page.empty());
        when(userService.listUsers(any())).thenReturn(org.springframework.data.domain.Page.empty());
        io.oddsmaker.control.jpa.UserEntity current = new io.oddsmaker.control.jpa.UserEntity();
        current.username = "tester";
        when(userService.findByUsername("tester")).thenReturn(java.util.Optional.of(current));

        assertEquals(200, userController.getCurrentUser().getStatusCode().value());
        assertEquals(200, userController.listUsers(0, 20, "createdAt", "desc").getStatusCode().value());
        assertEquals(200, userController.searchUsers("q", 0, 20).getStatusCode().value());
        assertEquals(404, userController.getUser("u1").getStatusCode().value());
        assertEquals(200, userController.createUser(new io.oddsmaker.control.jpa.UserEntity()).getStatusCode().value());
        assertEquals(200, userController.updateUser("u1", new io.oddsmaker.control.jpa.UserEntity()).getStatusCode().value());
        assertEquals(200, userController.deleteUser("u1").getStatusCode().value());
        assertEquals(200, userController.updateRoles("u1", Set.of(io.oddsmaker.control.jpa.UserEntity.UserRole.ADMIN)).getStatusCode().value());
        assertEquals(200, userController.lockUser("u1").getStatusCode().value());
        assertEquals(200, userController.unlockUser("u1").getStatusCode().value());
        assertEquals(200, userController.toggleTwoFactor("u1", Map.of("enabled", true)).getStatusCode().value());
        assertEquals(200, userController.getUserStatistics().getStatusCode().value());
        assertEquals(200, userController.getRecentLogins(10).getStatusCode().value());
        assertEquals(200, userController.getUsersByRole(io.oddsmaker.control.jpa.UserEntity.UserRole.ADMIN).getStatusCode().value());
    }

    // ===== 系统配置/维护/功能开关 =====

    @Mock
    private MaintenanceService maintenanceService;

    @InjectMocks
    private SystemController systemController;

    @Test
    @DisplayName("系统：18 个端点委托")
    void systemEndpoints() {
        assertEquals(200, systemController.createMaintenance(new SystemController.MaintenanceRequest()).getStatusCode().value());
        assertEquals(200, systemController.getActiveMaintenances().getStatusCode().value());
        assertEquals(200, systemController.getUpcomingMaintenances().getStatusCode().value());
        assertEquals(200, systemController.startMaintenance("w1").getStatusCode().value());
        assertEquals(200, systemController.completeMaintenance("w1", new SystemController.CompleteRequest()).getStatusCode().value());
        assertEquals(200, systemController.cancelMaintenance("w1", new SystemController.CancelRequest()).getStatusCode().value());
        assertEquals(200, systemController.checkMaintenance("g").getStatusCode().value());
        assertEquals(200, systemController.getAllConfigs().getStatusCode().value());
        assertEquals(200, systemController.getPublicConfigs().getStatusCode().value());
        assertEquals(200, systemController.getConfigValue("k").getStatusCode().value());
        assertEquals(200, systemController.setConfigValue("k", new SystemController.ConfigValueRequest()).getStatusCode().value());
        assertEquals(200, systemController.getAllFeatureFlags().getStatusCode().value());
        assertEquals(200, systemController.getEnabledFeatureFlags().getStatusCode().value());
        assertEquals(200, systemController.checkFeature("flag", null, null).getStatusCode().value());
        assertEquals(200, systemController.enableFeature("flag", new SystemController.ModifyRequest()).getStatusCode().value());
        assertEquals(200, systemController.disableFeature("flag", new SystemController.ModifyRequest()).getStatusCode().value());
        assertEquals(200, systemController.setFeaturePercentage("flag", new SystemController.PercentageRequest()).getStatusCode().value());
        assertEquals(200, systemController.getSystemStatus().getStatusCode().value());
        verify(maintenanceService).getActiveMaintenances();
    }

    // ===== 安全（MFA/SSO/会话/策略） =====

    @Mock
    private SecurityService securityService;

    @InjectMocks
    private SecurityController securityController;

    @Test
    @DisplayName("安全：19 个端点委托")
    void securityEndpoints() {
        assertEquals(200, securityController.enableMFA(new SecurityController.MFAEnableRequest()).getStatusCode().value());
        assertEquals(200, securityController.verifyMFA(new SecurityController.MFAVerifyRequest()).getStatusCode().value());
        assertEquals(200, securityController.disableMFA(new SecurityController.MFADisableRequest()).getStatusCode().value());
        assertEquals(200, securityController.validateMFA(new SecurityController.MFAValidateRequest()).getStatusCode().value());
        assertEquals(200, securityController.getUserMFAConfigs("u1").getStatusCode().value());
        assertEquals(200, securityController.isUserMFAEnabled("u1").getStatusCode().value());
        assertEquals(200, securityController.createSSOConfig(new SecurityController.SSOConfigRequest()).getStatusCode().value());
        assertEquals(200, securityController.getActiveSSOConfigs().getStatusCode().value());
        assertEquals(200, securityController.activateSSO("c1").getStatusCode().value());
        assertEquals(200, securityController.ssoCallback(new SecurityController.SSOCallbackRequest()).getStatusCode().value());
        SecurityController.SessionCreateRequest sessionReq = new SecurityController.SessionCreateRequest();
        sessionReq.timeoutMinutes = 30;
        assertEquals(200, securityController.createSession(sessionReq).getStatusCode().value());
        assertEquals(200, securityController.validateSession("token").getStatusCode().value());
        assertEquals(200, securityController.terminateSession("s1", new SecurityController.TerminateSessionRequest()).getStatusCode().value());
        assertEquals(200, securityController.terminateAllUserSessions("u1", new SecurityController.TerminateAllSessionsRequest()).getStatusCode().value());
        assertEquals(200, securityController.getPasswordPolicies(null).getStatusCode().value());
        assertEquals(200, securityController.getSessionPolicies(null).getStatusCode().value());
        assertEquals(200, securityController.getMFAPolicies(null).getStatusCode().value());
        assertEquals(200, securityController.isMFARequired("g").getStatusCode().value());
        verify(securityService).getActiveSSOConfigs();
    }

    // ===== 角色分配 =====

    @Mock
    private PermissionService permissionService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RoleAssignmentController roleAssignmentController;

    @Test
    @DisplayName("角色分配：列表/授予/回收与参数校验")
    void roleAssignmentEndpoints() {
        when(permissionService.listAssignments("u1")).thenReturn(List.of());
        assertEquals(200, roleAssignmentController.list("u1").getStatusCode().value());

        RoleAssignmentController.AssignReq assign = new RoleAssignmentController.AssignReq();
        assign.roleId = "operator";
        io.oddsmaker.control.jpa.UserRoleEntity assignment = new io.oddsmaker.control.jpa.UserRoleEntity();
        assignment.userId = "u1";
        assignment.roleId = "operator";
        when(permissionService.assignRole(anyString(), anyString(), any(), any(), any()))
            .thenReturn(assignment);
        assertEquals(200, roleAssignmentController.assign("u1", assign).getStatusCode().value());
        // roleId 缺失 400
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roleAssignmentController.assign("u1", new RoleAssignmentController.AssignReq()));

        assertEquals(200, roleAssignmentController.revoke("u1", "role_operator", null, null).getStatusCode().value());
    }
}
