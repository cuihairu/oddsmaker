package io.oddsmaker.control.security;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.service.PermissionService;
import io.oddsmaker.control.service.UserService;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AccessGuard 单元测试：scope 化权限检查、特权角色直通、登录名 → 用户 id 解析。
 *
 * 登录名（JWT subject）≠ users.id 是常态（admin 的 id 是 user_admin），
 * 门卫必须先解析出 id 再判权，否则非 ROLE_ADMIN 请求会在权限校验里 400。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessGuard 单元测试")
class AccessGuardTest {

    @Mock
    private PermissionService permissionService;

    @Mock
    private UserService userService;

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

    /** 登录名可解析：门卫拿到的是用户 id 而非登录名 */
    private void givenUser(String username, String userId) {
        UserEntity user = new UserEntity();
        user.id = userId;
        user.username = username;
        when(userService.findByUsername(username)).thenReturn(Optional.of(user));
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
        // 直通不查 users 表：服务间令牌（ROLE_INTERNAL）同样没有用户行
        verify(userService, never()).findByUsername(org.mockito.ArgumentMatchers.anyString());
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
        givenUser("alice", "user-alice-0001");
        when(permissionService.hasGamePermission("user-alice-0001", "game_1", "risk_rule:read")).thenReturn(true);
        assertDoesNotThrow(() -> accessGuard.requireGamePermission("game_1", "risk_rule:read"));
    }

    @Test
    @DisplayName("普通用户 game scope 未授权被拒")
    void gameScopedPermissionDenied() {
        login("bob");
        givenUser("bob", "user-bob-0001");
        when(permissionService.hasGamePermission("user-bob-0001", "game_1", "risk_rule:delete")).thenReturn(false);
        SecurityException ex = assertThrows(SecurityException.class,
            () -> accessGuard.requireGamePermission("game_1", "risk_rule:delete"));
        assertTrue(ex.getMessage().contains("risk_rule:delete"));
        assertTrue(ex.getMessage().contains("game_1"));
    }

    @Test
    @DisplayName("全局权限走 hasPermission")
    void globalPermissionChecked() {
        login("dave");
        givenUser("dave", "user-dave-0001");
        when(permissionService.hasPermission("user-dave-0001", "user:read")).thenReturn(true);
        assertDoesNotThrow(() -> accessGuard.requirePermission("user:read"));
    }

    @Test
    @DisplayName("登录名 ≠ 用户 id：按解析出的 id 判权（回归：不把登录名当 id 传下去）")
    void loginNameResolvedToUserIdBeforePermissionCheck() {
        login("demo", "ROLE_VIEWER");
        givenUser("demo", "user-demo-0001");
        when(permissionService.hasPermission("user-demo-0001", "user:read")).thenReturn(true);
        assertDoesNotThrow(() -> accessGuard.requirePermission("user:read"));
        verify(permissionService).hasPermission("user-demo-0001", "user:read");
        verify(permissionService, never()).hasPermission("demo", "user:read");
    }

    @Test
    @DisplayName("登录名解析不到用户（账号已删/改名）：按无权限拒，不落到 PermissionService")
    void unknownLoginNameFailsClosed() {
        login("ghost", "ROLE_VIEWER");
        when(userService.findByUsername("ghost")).thenReturn(Optional.empty());

        SecurityException ex = assertThrows(SecurityException.class,
            () -> accessGuard.requirePermission("user:read"));
        assertTrue(ex.getMessage().contains("user:read"));
        verify(permissionService, never()).hasPermission(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
        assertFalse(accessGuard.canAccessGame("game_1", "game:read"));
    }

    @Test
    @DisplayName("canAccessGame：非抛出版按解析出的 id 判权，未认证为 false")
    void canAccessGameUsesResolvedId() {
        assertFalse(accessGuard.canAccessGame("game_1", "game:read"));  // 未认证侧

        login("carol");
        givenUser("carol", "user-carol-0001");
        when(permissionService.hasGamePermission("user-carol-0001", "game_1", "game:read")).thenReturn(true);
        assertTrue(accessGuard.canAccessGame("game_1", "game:read"));

        when(permissionService.hasGamePermission("user-carol-0001", "game_2", "game:read")).thenReturn(false);
        assertFalse(accessGuard.canAccessGame("game_2", "game:read"));
    }

    @Test
    @DisplayName("未认证令牌（isAuthenticated=false）同样被拒；全局权限拒绝消息无 game 后缀")
    void unauthenticatedTokenAndGlobalDenialSides() {
        // 双参构造 = 未认证
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("anon", "pw"));
        SecurityException ex = assertThrows(SecurityException.class,
            () -> accessGuard.requirePermission("game:read"));
        assertTrue(ex.getMessage().contains("not authenticated"));

        // gameId null 侧：requirePermission 拒绝时消息不带 "for game"
        login("dave");
        givenUser("dave", "user-dave-0001");
        when(permissionService.hasPermission("user-dave-0001", "game:read")).thenReturn(false);
        SecurityException denied = assertThrows(SecurityException.class,
            () -> accessGuard.requirePermission("game:read"));
        assertTrue(denied.getMessage().contains("game:read"));
        assertFalse(denied.getMessage().contains("for game"));
    }

}