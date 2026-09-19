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
        // 读 6 → user:read；写 5 + updateUser/toggleTwoFactor（"u1"≠本人"tester"）→ user:update 7；/me 无 guard
        verify(accessGuard, org.mockito.Mockito.times(6)).requirePermission("user:read");
        verify(accessGuard, org.mockito.Mockito.times(7)).requirePermission("user:update");
    }

    @Test
    @DisplayName("用户：updateUser/toggleTwoFactor 本人自助放行（不触发 guard）")
    void userSelfServiceBypassesGuard() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("tester", "pw"));
        io.oddsmaker.control.jpa.UserEntity current = new io.oddsmaker.control.jpa.UserEntity();
        when(userService.updateUser(org.mockito.ArgumentMatchers.eq("tester"), any(), org.mockito.ArgumentMatchers.eq("tester")))
            .thenReturn(current);
        when(userService.toggleTwoFactor(org.mockito.ArgumentMatchers.eq("tester"), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq("tester")))
            .thenReturn(current);

        assertEquals(200, userController.updateUser("tester", new io.oddsmaker.control.jpa.UserEntity()).getStatusCode().value());
        assertEquals(200, userController.toggleTwoFactor("tester", Map.of("enabled", true)).getStatusCode().value());
        // 本人操作不触发权限门
        verify(accessGuard, org.mockito.Mockito.times(0)).requirePermission(anyString());
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
        // maintenance manage 4/read 2；system read 3/manage 1；featureflag read 2/manage 3；check/public/checkFeature 无 guard
        verify(accessGuard, org.mockito.Mockito.times(4)).requirePermission("maintenance:manage");
        verify(accessGuard, org.mockito.Mockito.times(2)).requirePermission("maintenance:read");
        verify(accessGuard, org.mockito.Mockito.times(3)).requirePermission("system:read");
        verify(accessGuard, org.mockito.Mockito.times(1)).requirePermission("system:manage");
        verify(accessGuard, org.mockito.Mockito.times(2)).requirePermission("featureflag:read");
        verify(accessGuard, org.mockito.Mockito.times(3)).requirePermission("featureflag:manage");
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
        // 诚实失败：无 IdP 集成，回调 501（不再假成功返回假 userId）
        assertEquals(501, securityController.ssoCallback(new SecurityController.SSOCallbackRequest()).getStatusCode().value());
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
        // MFA 自助 4 + 会话 create/validate/terminate + SSO active/callback + mfa-required 无 guard；
        // MFA 配置读 2 + 策略读 3 → security:read；SSO 写 2 + terminateAll → security:manage
        verify(accessGuard, org.mockito.Mockito.times(5)).requirePermission("security:read");
        verify(accessGuard, org.mockito.Mockito.times(3)).requirePermission("security:manage");
    }

    // ===== 角色分配 =====

    @Mock
    private PermissionService permissionService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private io.oddsmaker.control.jpa.RoleRepo roleRepo;

    @InjectMocks
    private RoleAssignmentController roleAssignmentController;

    @InjectMocks
    private RoleController roleController;

    @Test
    @DisplayName("角色分配：列表/授予（动态白名单）/回收与参数校验")
    void roleAssignmentEndpoints() {
        io.oddsmaker.control.jpa.RoleEntity role = new io.oddsmaker.control.jpa.RoleEntity();
        role.id = "role_operator";
        when(roleRepo.findByEnabledTrue()).thenReturn(List.of(role));
        when(permissionService.listAssignments("u1")).thenReturn(List.of());
        assertEquals(200, roleAssignmentController.list("u1").getStatusCode().value());

        RoleAssignmentController.AssignReq assign = new RoleAssignmentController.AssignReq();
        assign.roleId = "role_operator";
        io.oddsmaker.control.jpa.UserRoleEntity assignment = new io.oddsmaker.control.jpa.UserRoleEntity();
        assignment.userId = "u1";
        assignment.roleId = "role_operator";
        when(permissionService.assignRole(anyString(), anyString(), any(), any(), any()))
            .thenReturn(assignment);
        assertEquals(200, roleAssignmentController.assign("u1", assign).getStatusCode().value());
        // roleId 缺失 400
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roleAssignmentController.assign("u1", new RoleAssignmentController.AssignReq()));
        // roles 表不存在的 id 拒绝（白名单动态取启用角色）
        RoleAssignmentController.AssignReq unknown = new RoleAssignmentController.AssignReq();
        unknown.roleId = "super_admin";
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> roleAssignmentController.assign("u1", unknown));

        assertEquals(200, roleAssignmentController.revoke("u1", "role_operator", null, null).getStatusCode().value());
        verify(accessGuard).requirePermission("user:read");
        verify(accessGuard, org.mockito.Mockito.times(4)).requirePermission("user:update");
    }

    @Test
    @DisplayName("角色清单：委托启用角色并只出下拉字段")
    void roleListEndpoints() {
        io.oddsmaker.control.jpa.RoleEntity role = new io.oddsmaker.control.jpa.RoleEntity();
        role.id = "role_operator";
        role.name = "运营管理员";
        role.description = "公司级运营管理员，拥有所有权限";
        role.level = 0;
        when(roleRepo.findByEnabledTrue()).thenReturn(List.of(role));

        var result = roleController.list();

        assertEquals(1, result.size());
        assertEquals("role_operator", result.get(0).get("id"));
        assertEquals("运营管理员", result.get(0).get("name"));
        verify(accessGuard).requirePermission("user:read");
    }
}
