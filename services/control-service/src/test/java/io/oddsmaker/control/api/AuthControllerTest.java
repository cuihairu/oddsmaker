package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.service.JwtService;
import io.oddsmaker.control.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地账号登录全分支：参数缺失 400、凭据失效 401、非激活 403、成功签发 JWT。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("本地账号登录测试")
class AuthControllerTest {

    @Mock
    UserService userService;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    JwtService jwtService;

    AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(userService, passwordEncoder, jwtService);
    }

    private UserEntity activeUser() {
        UserEntity u = new UserEntity();
        u.username = "alice";
        u.passwordHash = "$2a$hash";
        u.status = UserEntity.UserStatus.ACTIVE;
        u.roles = new java.util.HashSet<>();
        u.roles.add(UserEntity.UserRole.ADMIN);
        return u;
    }

    @Test
    @DisplayName("用户名或密码缺失：400")
    void blankCredentialsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "", "password", "p")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "alice")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "alice", "password", " ")).getStatusCode());
    }

    @Test
    @DisplayName("用户不存在：401 不泄露账号存在性")
    void unknownUserRejected() {
        when(userService.findByUsername("nobody")).thenReturn(Optional.empty());
        var resp = controller.login(Map.of("username", "nobody", "password", "p"));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verify(jwtService, never()).issue(anyString(), anyList());
    }

    @Test
    @DisplayName("密码错误或哈希缺失：401")
    void wrongPasswordOrMissingHashRejected() {
        UserEntity user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.login(Map.of("username", "alice", "password", "wrong")).getStatusCode());

        UserEntity noHash = activeUser();
        noHash.passwordHash = null;
        when(userService.findByUsername("bob")).thenReturn(Optional.of(noHash));
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.login(Map.of("username", "bob", "password", "p")).getStatusCode());
    }

    @Test
    @DisplayName("账号非激活：403")
    void inactiveUserForbidden() {
        UserEntity user = activeUser();
        user.status = UserEntity.UserStatus.INACTIVE;
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("p", "$2a$hash")).thenReturn(true);
        assertEquals(HttpStatus.FORBIDDEN,
            controller.login(Map.of("username", "alice", "password", "p")).getStatusCode());
    }

    @Test
    @DisplayName("成功登录：签发 JWT + 记录登录 + 返回用户；roles 为 null 时空角色")
    void successfulLoginIssuesToken() {
        UserEntity user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("p", "$2a$hash")).thenReturn(true);
        when(jwtService.issue(anyString(), anyList())).thenReturn("jwt-token");
        var resp = controller.login(Map.of("username", "alice", "password", "p"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("jwt-token", resp.getBody().get("token"));
        assertEquals(user, resp.getBody().get("user"));
        verify(userService).recordLogin(user);

        // roles null → 空角色列表签发（签发用的是实体上的 username，需同步改名）
        UserEntity noRoles = activeUser();
        noRoles.username = "carol";
        noRoles.roles = null;
        when(userService.findByUsername("carol")).thenReturn(Optional.of(noRoles));
        var resp2 = controller.login(Map.of("username", "carol", "password", "p"));
        assertEquals(HttpStatus.OK, resp2.getStatusCode());
        verify(jwtService).issue("carol", List.of());
    }

    @Test
    @DisplayName("登出：无状态 JWT 直接 200")
    void logoutIsStateless() {
        assertEquals(HttpStatus.OK, controller.logout().getStatusCode());
        assertNotNull(controller.logout());
        verify(userService, never()).recordLogin(any(UserEntity.class));
    }
}
