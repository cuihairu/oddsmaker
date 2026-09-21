package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
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
    @DisplayName("内部端点绕过封堵：Bearer 头不放行 /internal/（/internal/api-keys 返回 secret 本体，登录 JWT 不得绕过服务间令牌）")
    void adminFilterInternalEndpointRejectsBearer() throws Exception {
        AdminTokenFilter f = filter("secret", "internal-secret");

        // 攻击形态：持有效登录 JWT 的低权限用户带 Bearer 访问内部凭据端点。
        // 旧代码 Bearer 放行分支排在 /internal/ 检查之前 → 绕过 x-internal-token。
        SecurityContextHolder.clearContext();
        MockHttpServletRequest bearerReq = new MockHttpServletRequest("GET", "/internal/api-keys/some-key");
        bearerReq.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.valid.jwt");
        MockHttpServletResponse bearerResp = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        f.doFilter(bearerReq, bearerResp, chain);
        assertEquals(401, bearerResp.getStatus());
        assertTrue(bearerResp.getContentAsString().contains("missing_or_invalid_internal_token"));
        org.mockito.Mockito.verifyNoInteractions(chain);   // 未透传到业务链

        // Bearer 与正确 x-internal-token 同带：以服务间令牌判定（Bearer 不干扰）
        SecurityContextHolder.clearContext();
        MockHttpServletRequest bothReq = new MockHttpServletRequest("GET", "/internal/api-keys/some-key");
        bothReq.addHeader("Authorization", "Bearer anything");
        bothReq.addHeader("x-internal-token", "internal-secret");
        MockHttpServletResponse bothResp = new MockHttpServletResponse();
        f.doFilter(bothReq, bothResp, chain);
        assertEquals(200, bothResp.getStatus());
        assertEquals("internal-gateway", SecurityContextHolder.getContext().getAuthentication().getName());
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
    @DisplayName("登录路径与 Bearer 请求直接放行；空令牌比较返回不匹配")
    void adminFilterAuthPathAndBearerPassthrough() throws Exception {
        AdminTokenFilter f = filter("secret", "internal-secret");

        // /api/auth/** 由 SecurityConfig permitAll 放行，过滤器跳过
        assertTrue(f.shouldNotFilter(new MockHttpServletRequest("POST", "/api/auth/login")));

        // Bearer 请求交给 Security 链 oauth2 filter 处理，直接放行
        SecurityContextHolder.clearContext();
        MockHttpServletRequest bearerReq = new MockHttpServletRequest("GET", "/api/games");
        bearerReq.addHeader("Authorization", "Bearer some-jwt");
        MockHttpServletResponse bearerResp = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        f.doFilter(bearerReq, bearerResp, chain);
        assertEquals(200, bearerResp.getStatus());  // 未被 401 拦截

        // internal token 未配置 → matches 空参短路 401
        SecurityContextHolder.clearContext();
        MockHttpServletResponse noInternal = run(filter("secret", null), "/internal/keys", null, null);
        assertEquals(401, noInternal.getStatus());

        // 请求不带 x-admin-token 头 → supplied 为空短路 401
        SecurityContextHolder.clearContext();
        MockHttpServletResponse noAdmin = run(filter("secret", "internal-secret"), "/api/games", null, null);
        assertEquals(401, noAdmin.getStatus());
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

    // ===== AccessGuard 环境级分支（补齐） =====

    @Test
    @DisplayName("AccessGuard：requirePermission 全局级与 ROLE_INTERNAL 直通")
    void accessGuardEnvironmentScope() {
        var permissionService2 = mock(PermissionService.class);
        AccessGuard guard = new AccessGuard(permissionService2);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("tester", "pw", java.util.List.of()));

        // requirePermission 全局级
        when(permissionService2.hasPermission("tester", "user:update")).thenReturn(true);
        guard.requirePermission("user:update");

        // ROLE_INTERNAL 直通
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("gw", "pw",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INTERNAL"))));
        guard.requireGamePermission("any", "game:read");
    }

    @Test
    @DisplayName("AccessGuard：普通用户权限遍历完 authorities 后走 hasGamePermission 链路")
    void accessGuardPlainUserIteration() {
        var permissionService2 = mock(PermissionService.class);
        AccessGuard guard = new AccessGuard(permissionService2);
        // 普通用户（非 ROLE_ADMIN/ROLE_INTERNAL）：两个 for 循环均遍历至自然结束
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("u", "pw",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER"))));

        when(permissionService2.hasGamePermission("u", "g1", "game:read")).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertTrue(guard.canAccessGame("g1", "game:read"));

        when(permissionService2.hasGamePermission("u", "g2", "game:read")).thenReturn(false);
        assertThrows(SecurityException.class, () -> guard.requireGamePermission("g2", "game:read"));
    }

    @Test
    @DisplayName("JWT 转换：roles 数组 null/blank 元素被滤、realm/client 无 roles 键、principal 逐级空串回退")
    void jwtConverterBlankAndMissingKeySides() {
        KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("roles", java.util.Arrays.asList("auditor", null, "   "));
        claims.put("realm_access", java.util.Map.of("other", "x"));            // containsKey false 侧
        claims.put("resource_access", java.util.Map.of("clientA", java.util.Map.of("perm", 1))); // 74 行 false 侧
        claims.put("preferred_username", "");                                  // 99 行 isEmpty 侧
        claims.put("email", "");                                                // 105 行 isEmpty 侧
        claims.put("name", "");                                                 // 111 行 isEmpty 侧
        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt(claims));
        // username/email/name 全空串 → 回退 subject
        assertEquals("sub-1", token.getName());
        assertTrue(token.getAuthorities().stream()
            .anyMatch(a -> "ROLE_AUDITOR".equals(a.getAuthority())));
        // null 与 blank 角色元素被过滤，不产生 ROLE_NULL / 空名角色
        assertTrue(token.getAuthorities().stream()
            .noneMatch(a -> a.getAuthority().endsWith("NULL") || a.getAuthority().equals("ROLE_")));
    }

    @Test
    @DisplayName("管理令牌：Authorization 非 Bearer 形态不走放行；adminToken 空串为开发模式；空/缺失头不匹配")
    void adminFilterNonBearerAndEmptyTokenSides() throws Exception {
        // 82 行 startsWith("Bearer ") false 侧：Basic 头继续走 admin 检查
        AdminTokenFilter f = filter("secret", "internal-secret");
        SecurityContextHolder.clearContext();
        MockHttpServletRequest basicReq = new MockHttpServletRequest("GET", "/api/games");
        basicReq.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        basicReq.addHeader("x-admin-token", "secret");
        MockHttpServletResponse basicResp = new MockHttpServletResponse();
        f.doFilter(basicReq, basicResp, mock(jakarta.servlet.FilterChain.class));
        assertEquals(200, basicResp.getStatus());
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());

        // 88 行 adminToken 空串侧：同 null 开发模式
        SecurityContextHolder.clearContext();
        AdminTokenFilter emptyToken = filter("", null);
        MockHttpServletResponse devResp2 = run(emptyToken, "/api/games", null, null);
        assertEquals(200, devResp2.getStatus());
        assertEquals("dev-admin", SecurityContextHolder.getContext().getAuthentication().getName());

        // 117 行 supplied null / empty 侧：无头与空头均不匹配 → 401
        SecurityContextHolder.clearContext();
        assertEquals(401, run(f, "/api/games", null, null).getStatus());
        SecurityContextHolder.clearContext();
        assertEquals(401, run(f, "/api/games", null, "").getStatus());
    }

    @Test
    @DisplayName("matches 直调：expected==null 与 supplied==null/空串/相等/不等全边（构造器 orElse 兜底使 null 仅直调可达）")
    void matchesDirectAllSides() {
        AdminTokenFilter f = filter("secret", "internal-secret");
        // expected == null：构造器 Binder.orElse("") 使字段恒非 null，私有 matches 直调可达。
        // 显式 (Boolean) 转型——Object 直接交 assertFalse 会命中 BooleanSupplier 重载抛 CCE
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", "s", null));
        // supplied 非空 + expected 空串：第四条件真边（HTTP 链路下 internal 测试无头在第一条件短路，
        // 到不了第四条件）
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", "s", ""));
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", null, "secret"));
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", "", "secret"));
        assertTrue((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", "secret", "secret"));
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(f, "matches", "wrong", "secret"));
    }

    @Test
    @DisplayName("roles 过滤 lambda 直调：null 元素侧（getClaimAsStringList 的 Conversion 会剥掉 null 元素，仅直调可达）")
    void rolesFilterLambdaNullSide() throws Exception {
        // 52 行 filter 链的 role == null 边：经 Jwt.getClaimAsStringList 转换的列表不含 null 元素，
        // 该边只能直调 lambda 合成方法覆盖
        java.lang.reflect.Method lambda = KeycloakJwtAuthenticationConverter.class
            .getDeclaredMethod("lambda$extractKeycloakAuthorities$0", String.class);
        lambda.setAccessible(true);
        assertEquals(Boolean.FALSE, lambda.invoke(null, (Object) null));
        assertEquals(Boolean.FALSE, lambda.invoke(null, "   "));
        assertEquals(Boolean.TRUE, lambda.invoke(null, "auditor"));
    }

}
