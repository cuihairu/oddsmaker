package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.JwtService;
import io.oddsmaker.control.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 本地账号登录全分支：参数缺失 400、凭据失效 401、非激活 403、成功签发 JWT，
 * 以及登录/登出全程审计写入（LOGIN/LOGIN_FAILED/LOGOUT——/api/audit-logs/auth 的数据源）。
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

    @Mock
    AuditLogService auditLogService;

    AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(userService, passwordEncoder, jwtService, auditLogService);
    }

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private UserEntity activeUser() {
        UserEntity u = new UserEntity();
        u.id = "u_1";
        u.username = "alice";
        u.passwordHash = "$2a$hash";
        u.status = UserEntity.UserStatus.ACTIVE;
        u.roles = new java.util.HashSet<>();
        u.roles.add(UserEntity.UserRole.ADMIN);
        return u;
    }

    @Test
    @DisplayName("用户名或密码缺失：400 且不写审计（未到认证步骤）")
    void blankCredentialsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "", "password", "p"), request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "alice"), request).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.login(Map.of("username", "alice", "password", " "), request).getStatusCode());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("用户不存在：401 不泄露账号存在性，审计记 LOGIN_FAILED（真实原因）")
    void unknownUserRejected() {
        when(userService.findByUsername("nobody")).thenReturn(Optional.empty());
        var resp = controller.login(Map.of("username", "nobody", "password", "p"), request);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verify(jwtService, never()).issue(anyString(), anyList());
        verify(auditLogService).logLoginFailed(eq("nobody"), any(), any(), eq("invalid credentials"));
        verify(auditLogService, never()).logLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("密码错误或哈希缺失：401 + LOGIN_FAILED")
    void wrongPasswordOrMissingHashRejected() {
        UserEntity user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.login(Map.of("username", "alice", "password", "wrong"), request).getStatusCode());

        UserEntity noHash = activeUser();
        noHash.passwordHash = null;
        when(userService.findByUsername("bob")).thenReturn(Optional.of(noHash));
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.login(Map.of("username", "bob", "password", "p"), request).getStatusCode());

        verify(auditLogService, never()).logLogin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("账号非激活：403 + LOGIN_FAILED（原因 account not active）")
    void inactiveUserForbidden() {
        UserEntity user = activeUser();
        user.status = UserEntity.UserStatus.INACTIVE;
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("p", "$2a$hash")).thenReturn(true);
        assertEquals(HttpStatus.FORBIDDEN,
            controller.login(Map.of("username", "alice", "password", "p"), request).getStatusCode());
        verify(auditLogService).logLoginFailed(eq("alice"), any(), any(), eq("account not active"));
    }

    @Test
    @DisplayName("成功登录：签发 JWT + 记录登录 + LOGIN 审计（含 IP/User-Agent）；roles 为 null 时空角色")
    void successfulLoginIssuesToken() {
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("User-Agent", "jest/1.0");
        UserEntity user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("p", "$2a$hash")).thenReturn(true);
        when(jwtService.issue(anyString(), anyList())).thenReturn("jwt-token");
        var resp = controller.login(Map.of("username", "alice", "password", "p"), request);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("jwt-token", resp.getBody().get("token"));
        assertEquals(user, resp.getBody().get("user"));
        verify(userService).recordLogin(user);
        verify(auditLogService).logLogin("u_1", "alice", "10.0.0.8", "jest/1.0");

        // roles null → 空角色列表签发（签发用的是实体上的 username，需同步改名）
        UserEntity noRoles = activeUser();
        noRoles.username = "carol";
        noRoles.roles = null;
        when(userService.findByUsername("carol")).thenReturn(Optional.of(noRoles));
        var resp2 = controller.login(Map.of("username", "carol", "password", "p"), request);
        assertEquals(HttpStatus.OK, resp2.getStatusCode());
        verify(jwtService).issue("carol", List.of());
        verify(auditLogService).logLogin(eq("u_1"), eq("carol"), any(), any());
    }

    @Test
    @DisplayName("X-Forwarded-For 首跳优先于 remoteAddr")
    void forwardedForWins() {
        request.setRemoteAddr("192.168.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        when(userService.findByUsername("nobody")).thenReturn(Optional.empty());
        controller.login(Map.of("username", "nobody", "password", "p"), request);
        verify(auditLogService).logLoginFailed(eq("nobody"), eq("203.0.113.9"), any(), anyString());
    }

    @Test
    @DisplayName("审计写失败不阻断登录：仍 200 签发 token")
    void auditFailureDoesNotBlockLogin() {
        UserEntity user = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("p", "$2a$hash")).thenReturn(true);
        when(jwtService.issue(anyString(), anyList())).thenReturn("jwt-token");
        doThrow(new RuntimeException("audit db down")).when(auditLogService)
            .logLogin(any(), any(), any(), any());

        var resp = controller.login(Map.of("username", "alice", "password", "p"), request);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("jwt-token", resp.getBody().get("token"));
    }

    @Test
    @DisplayName("登出：无状态 JWT 200；能识别身份时记 LOGOUT 审计（userId 反查）")
    void logoutAuditsWhenIdentityKnown() {
        // 无认证（未带 token 的登出）→ 200 且不写审计
        assertEquals(HttpStatus.OK, controller.logout(null, request).getStatusCode());
        verifyNoInteractions(auditLogService);

        // 匿名身份（已认证但 principal 是 anonymousUser）→ 不写审计
        Authentication anonymous = new TestingAuthenticationToken("anonymousUser", "n/a", "ROLE_USER");
        controller.logout(anonymous, request);
        verifyNoInteractions(auditLogService);

        // Bearer 已解析：subject 反查 userId 后记 LOGOUT
        request.setRemoteAddr("10.0.0.9");
        UserEntity alice = activeUser();
        when(userService.findByUsername("alice")).thenReturn(Optional.of(alice));
        Authentication auth = new TestingAuthenticationToken("alice", "n/a", "ROLE_ADMIN");
        assertEquals(HttpStatus.OK, controller.logout(auth, request).getStatusCode());
        verify(auditLogService).logLogout("u_1", "alice", "10.0.0.9");

        // subject 在库中不存在：仍记 LOGOUT（userId 为 null）
        when(userService.findByUsername("ghost")).thenReturn(Optional.empty());
        Authentication ghost = new TestingAuthenticationToken("ghost", "n/a", "ROLE_ADMIN");
        controller.logout(ghost, request);
        verify(auditLogService).logLogout(isNull(), eq("ghost"), any());
    }

    @Test
    @DisplayName("LOGIN 失败审计携带真实原因（不泄露给客户端但审计留痕）")
    void loginFailedAuditCarriesReason() {
        when(userService.findByUsername("nobody")).thenReturn(Optional.empty());
        var resp = controller.login(Map.of("username", "nobody", "password", "p"), request);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("用户名或密码错误", resp.getBody().get("message"));  // 客户端只见统一文案
        verify(auditLogService).logLoginFailed(eq("nobody"), any(), any(), contains("credentials"));
    }
}
