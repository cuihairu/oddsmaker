package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 安全组件测试：Admin Token 过滤器/JWT 转换器/权限切面/安全响应头。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("安全组件测试")
class SecurityComponentsTest {

    @AfterEach
    @BeforeEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    // ===== AdminTokenFilter =====

    private org.springframework.mock.env.MockEnvironment env(String adminToken, String internalToken) {
        org.springframework.mock.env.MockEnvironment e = new org.springframework.mock.env.MockEnvironment();
        if (adminToken != null) {
            e.setProperty("oddsmaker.admin.token", adminToken);
        }
        if (internalToken != null) {
            e.setProperty("oddsmaker.internal.token", internalToken);
        }
        return e;
    }

    private AdminTokenFilter filter(String adminToken, String internalToken) {
        return new AdminTokenFilter(env(adminToken, internalToken));
    }

    private MockHttpServletResponse run(AdminTokenFilter f, String uri, String internalToken, String adminToken)
            throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (internalToken != null) {
            request.addHeader("x-internal-token", internalToken);
        }
        if (adminToken != null) {
            request.addHeader("x-admin-token", adminToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        f.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("shouldNotFilter：仅 /api/ 与 /internal/ 参与过滤")
    void adminFilterShouldNotFilter() throws Exception {
        AdminTokenFilter f = filter("secret", "internal-secret");
        assertFalse(f.shouldNotFilter(new MockHttpServletRequest("GET", "/api/games")));
        assertFalse(f.shouldNotFilter(new MockHttpServletRequest("GET", "/internal/keys")));
        assertTrue(f.shouldNotFilter(new MockHttpServletRequest("GET", "/web/index.html")));
        assertTrue(f.shouldNotFilter(new MockHttpServletRequest("GET", "/api/config/public")));
    }

    @Test
    @DisplayName("内部端点：正确令牌获 ROLE_INTERNAL，错误令牌 401")
    void adminFilterInternalToken() throws Exception {
        AdminTokenFilter f = filter("secret", "internal-secret");
        MockHttpServletResponse ok = run(f, "/internal/keys", "internal-secret", null);
        assertEquals(200, ok.getStatus());
        assertEquals("internal-gateway", SecurityContextHolder.getContext().getAuthentication().getName());

        SecurityContextHolder.clearContext();
        MockHttpServletResponse bad = run(f, "/internal/keys", "wrong", null);
        assertEquals(401, bad.getStatus());
        assertTrue(bad.getContentAsString().contains("missing_or_invalid_internal_token"));
    }

    @Test
    @DisplayName("管理令牌：匹配获 ROLE_ADMIN；未配置走开发模式；不匹配 401")
    void adminFilterAdminToken() throws Exception {
        AdminTokenFilter f = filter("secret", "internal-secret");
        MockHttpServletResponse ok = run(f, "/api/games", null, "secret");
        assertEquals(200, ok.getStatus());
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));

        SecurityContextHolder.clearContext();
        MockHttpServletResponse bad = run(f, "/api/games", null, "wrong");
        assertEquals(401, bad.getStatus());
        assertTrue(bad.getContentAsString().contains("missing_or_invalid_admin_token"));

        // 未配置 admin token：开发模式自动认证
        AdminTokenFilter dev = filter(null, null);
        MockHttpServletResponse devResp = run(dev, "/api/games", null, null);
        assertEquals(200, devResp.getStatus());
        assertEquals("dev-admin", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    @DisplayName("已有 JwtAuthenticationToken 时跳过 Admin 检查")
    void adminFilterSkipsExistingJwt() throws Exception {
        AdminTokenFilter f = filter("secret", null);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none").subject("keycloak-user").build()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        f.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertInstanceOf(JwtAuthenticationToken.class,
            SecurityContextHolder.getContext().getAuthentication());
    }

    // ===== KeycloakJwtAuthenticationConverter =====

    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token-value")
            .header("alg", "RS256")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .subject("sub-1")
            .claims(c -> c.putAll(claims))
            .build();
    }

    @Test
    @DisplayName("JWT 转换：realm/resource 角色提取与 principal 回退链")
    void jwtConverterExtractsRolesAndPrincipal() {
        KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt(Map.of(
            "preferred_username", "alice",
            "realm_access", Map.of("roles", List.of("admin", "operator")),
            "resource_access", Map.of("control-api", Map.of("roles", List.of("analyst"))))));
        assertEquals("alice", token.getName());
        assertTrue(token.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
        assertTrue(token.getAuthorities().stream()
            .anyMatch(a -> "ROLE_OPERATOR".equals(a.getAuthority())));
        assertTrue(token.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ANALYST".equals(a.getAuthority())));

        // principal 回退：preferred_username 缺失 → email → name → subject
        assertEquals("a@b.c", converter.convert(jwt(Map.of("email", "a@b.c"))).getName());
        assertEquals("Alice Chen", converter.convert(jwt(Map.of("name", "Alice Chen"))).getName());
        assertEquals("sub-1", converter.convert(jwt(Map.of())).getName());
        // 无角色时默认 ROLE_USER
        assertTrue(converter.convert(jwt(Map.of("preferred_username", "u1"))).getAuthorities().stream()
            .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
        // resource_access 中非 Map 值被跳过
        assertEquals("u2", converter.convert(jwt(Map.of(
            "preferred_username", "u2",
            "resource_access", Map.of("bad", "not-a-map")))).getName());
    }

    // ===== SecurityHeadersFilter =====

    @Test
    @DisplayName("安全响应头：全部注入，API 路径追加缓存控制")
    void securityHeaders() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.init(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(request, response, chain);

        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("max-age=31536000; includeSubDomains; preload",
            response.getHeader("Strict-Transport-Security"));
        assertEquals("no-store, no-cache, must-revalidate, max-age=0", response.getHeader("Cache-Control"));
        assertEquals("", response.getHeader("Server"));
        assertTrue(String.valueOf(response.getHeader("Content-Security-Policy")).contains("default-src 'self'"));

        // 非 API 路径无缓存头
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), other, chain);
        org.junit.jupiter.api.Assertions.assertNull(other.getHeader("Cache-Control"));
        filter.destroy();
    }

    // ===== PermissionAspect =====

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private PermissionAspect aspect;

    @RequirePermission(value = "game:read", gameIdParam = "gameId")
    public String gameScoped(String gameId) {
        return "ok-game";
    }

    @RequirePermission(value = "user:update")
    public String globalScoped() {
        return "ok-global";
    }

    public String noAnnotation() {
        return "ok-none";
    }

    private ProceedingJoinPoint joinPoint(Method method, Object... args) {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        org.mockito.Mockito.lenient().when(signature.getMethod()).thenReturn(method);
        org.mockito.Mockito.lenient().when(jp.getSignature()).thenReturn(signature);
        org.mockito.Mockito.lenient().when(jp.getArgs()).thenReturn(args);
        return jp;
    }

    @Test
    @DisplayName("权限切面：无注解放行；未认证拒绝；三级 scope 检查")
    void permissionAspectScopes() throws Throwable {
        // 无注解直接放行（无需认证）
        ProceedingJoinPoint none = joinPoint(SecurityComponentsTest.class.getMethod("noAnnotation"));
        when(none.proceed()).thenReturn("ok-none");
        assertEquals("ok-none", aspect.checkPermission(none));

        // 未认证
        ProceedingJoinPoint game = joinPoint(
            SecurityComponentsTest.class.getMethod("gameScoped", String.class), "g1");
        assertThrows(SecurityException.class, () -> aspect.checkPermission(game));

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("tester", "pw", java.util.List.of()));

        // 游戏级
        when(permissionService.hasGamePermission("tester", "g1", "game:read")).thenReturn(true);
        when(game.proceed()).thenReturn("ok-game");
        assertEquals("ok-game", aspect.checkPermission(game));

        // 拒绝
        when(permissionService.hasGamePermission("tester", "g2", "game:read")).thenReturn(false);
        ProceedingJoinPoint denied = joinPoint(
            SecurityComponentsTest.class.getMethod("gameScoped", String.class), "g2");
        assertThrows(SecurityException.class, () -> aspect.checkPermission(denied));

        // 全局级（无 gameIdParam）
        when(permissionService.hasPermission("tester", "user:update")).thenReturn(true);
        ProceedingJoinPoint global = joinPoint(SecurityComponentsTest.class.getMethod("globalScoped"));
        when(global.proceed()).thenReturn("ok-global");
        assertEquals("ok-global", aspect.checkPermission(global));
    }

    // ===== AccessGuard 环境级分支（补齐） =====

    @Test
    @DisplayName("AccessGuard：requireEnvironmentPermission 三级检查")
    void accessGuardEnvironmentScope() {
        var permissionService2 = mock(PermissionService.class);
        AccessGuard guard = new AccessGuard(permissionService2);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("tester", "pw", java.util.List.of()));

        when(permissionService2.hasEnvironmentPermission("tester", "g1", "prod", "game:read")).thenReturn(true);
        guard.requireEnvironmentPermission("g1", "prod", "game:read");

        when(permissionService2.hasEnvironmentPermission("tester", "g2", "prod", "game:read")).thenReturn(false);
        assertThrows(SecurityException.class, () -> guard.requireEnvironmentPermission("g2", "prod", "game:read"));

        // requirePermission 全局级
        when(permissionService2.hasPermission("tester", "user:update")).thenReturn(true);
        guard.requirePermission("user:update");

        // ROLE_INTERNAL 直通
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("gw", "pw",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INTERNAL"))));
        guard.requireGamePermission("any", "game:read");
    }
}
