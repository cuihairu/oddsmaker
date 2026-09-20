package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * AccessGuard 单元测试：scope 化权限检查与特权角色直通
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessGuard 单元测试")
class AccessGuardTest {

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private AccessGuard accessGuard;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(
            user, "n/a",
            java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("未认证请求被拒绝")
    void unauthenticatedRejected() {
        assertThrows(SecurityException.class, () -> accessGuard.requirePermission("game:read"));
    }

    @Test
    @DisplayName("ROLE_ADMIN 直通")
    void adminBypasses() {
        login("root", "ROLE_ADMIN");
        assertDoesNotThrow(() -> accessGuard.requireGamePermission("game_1", "risk_rule:create"));
    }

    @Test
    @DisplayName("ROLE_INTERNAL 服务间令牌直通")
    void internalTokenBypasses() {
        login("gateway", "ROLE_INTERNAL");
        assertDoesNotThrow(() -> accessGuard.requireGamePermission("game_1", "risk_rule:create"));
    }

    @Test
    @DisplayName("普通用户 game scope 命中放行")
    void gameScopedPermissionGranted() {
        login("alice");
        when(permissionService.hasGamePermission("alice", "game_1", "risk_rule:read")).thenReturn(true);
        assertDoesNotThrow(() -> accessGuard.requireGamePermission("game_1", "risk_rule:read"));
    }

    @Test
    @DisplayName("普通用户 game scope 未授权被拒")
    void gameScopedPermissionDenied() {
        login("bob");
        when(permissionService.hasGamePermission("bob", "game_1", "risk_rule:delete")).thenReturn(false);
        SecurityException ex = assertThrows(SecurityException.class,
            () -> accessGuard.requireGamePermission("game_1", "risk_rule:delete"));
        assertTrue(ex.getMessage().contains("risk_rule:delete"));
        assertTrue(ex.getMessage().contains("game_1"));
    }

    @Test
    @DisplayName("全局权限走 hasPermission")
    void globalPermissionChecked() {
        login("dave");
        when(permissionService.hasPermission("dave", "user:read")).thenReturn(true);
        assertDoesNotThrow(() -> accessGuard.requirePermission("user:read"));
    }
}
