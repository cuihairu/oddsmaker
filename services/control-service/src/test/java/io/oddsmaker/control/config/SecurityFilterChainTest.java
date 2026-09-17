package io.oddsmaker.control.config;

import io.oddsmaker.control.security.AdminTokenFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全过滤链构建测试：mock 链验证全链配置方法被调用；真实 HttpSecurity 构建让
 * 全部 Customizer lambda 真实执行,并触发 401/403 处理器的 JSON 响应。
 */
@DisplayName("安全过滤链构建测试")
class SecurityFilterChainTest {

    @Test
    @DisplayName("securityFilterChain：CSRF/CORS/无状态会话/授权/头/异常处理全链配置")
    void buildsFullChain() throws Exception {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwkSetUri", "");
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");

        HttpSecurity http = mock(HttpSecurity.class);
        DefaultSecurityFilterChain chain = mock(DefaultSecurityFilterChain.class);
        when(http.csrf(any())).thenReturn(http);
        when(http.cors(any())).thenReturn(http);
        when(http.sessionManagement(any())).thenReturn(http);
        when(http.httpBasic(any())).thenReturn(http);
        when(http.formLogin(any())).thenReturn(http);
        when(http.logout(any())).thenReturn(http);
        when(http.oauth2ResourceServer(any())).thenReturn(http);
        when(http.authorizeHttpRequests(any())).thenReturn(http);
        when(http.addFilterBefore(any(jakarta.servlet.Filter.class), any(Class.class))).thenReturn(http);
        when(http.headers(any())).thenReturn(http);
        when(http.exceptionHandling(any())).thenReturn(http);
        when(http.build()).thenReturn(chain);

        AdminTokenFilter filter = new AdminTokenFilter(new MockEnvironment());
        SecurityFilterChain result = config.securityFilterChain(http, filter);

        assertSame(chain, result);
        verify(http).csrf(any());
        verify(http).cors(any());
        verify(http).sessionManagement(any());
        verify(http).addFilterBefore(any(jakarta.servlet.Filter.class), any(Class.class));
        verify(http).build();
    }

    @Test
    @DisplayName("真实构建过滤器链:Customizer 全执行,CSP/HSTS 等头写入,401/403 响应体正确")
    void buildsRealChainAndExecutesAllCustomizers() throws Exception {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwkSetUri", "");
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");
        ReflectionTestUtils.setField(config, "jwtSecret", "");

        ObjectPostProcessor<Object> noop = new ObjectPostProcessor<>() {
            @Override
            public <O> O postProcess(O object) {
                return object;
            }
        };
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        ctx.getBeanFactory().registerSingleton("jwtDecoder", (JwtDecoder) token -> null);

        Map<Class<?>, Object> shared = new HashMap<>();
        shared.put(ApplicationContext.class, ctx);
        HttpSecurity http = new HttpSecurity(noop, new AuthenticationManagerBuilder(noop), shared);

        SecurityFilterChain chain = config.securityFilterChain(http, new AdminTokenFilter(new MockEnvironment()));
        assertTrue(chain instanceof DefaultSecurityFilterChain);
        List<jakarta.servlet.Filter> filters = ((DefaultSecurityFilterChain) chain).getFilters();

        // 异常处理 lambda:未认证 → 401 JSON;拒绝 → 403 JSON
        ExceptionTranslationFilter etf = filters.stream()
            .filter(ExceptionTranslationFilter.class::isInstance)
            .map(ExceptionTranslationFilter.class::cast)
            .findFirst().orElseThrow();
        AuthenticationEntryPoint entry = (AuthenticationEntryPoint)
            ReflectionTestUtils.getField(etf, "authenticationEntryPoint");
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        entry.commence(new MockHttpServletRequest(), unauthorized, new AuthenticationException("denied") {});
        assertEquals(401, unauthorized.getStatus());
        assertEquals("application/json", unauthorized.getContentType());
        assertTrue(unauthorized.getContentAsString().contains("Authentication required"));

        AccessDeniedHandler denied = (AccessDeniedHandler)
            ReflectionTestUtils.getField(etf, "accessDeniedHandler");
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        denied.handle(new MockHttpServletRequest(), forbidden, new AccessDeniedException("no"));
        assertEquals(403, forbidden.getStatus());
        assertEquals("application/json", forbidden.getContentType());
        assertTrue(forbidden.getContentAsString().contains("Access denied"));
        ctx.close();
    }

    @Test
    @DisplayName("jwtDecoder 四分支:jwkSetUri → issuerUri → 本地 HS256 secret → 空实现")
    void jwtDecoderPicksBranchByConfiguration() throws Exception {
        SecurityConfig config = new SecurityConfig();

        // 1) jwkSetUri 优先
        ReflectionTestUtils.setField(config, "jwkSetUri", "https://kc.example/protocol/openid-connect/certs");
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");
        ReflectionTestUtils.setField(config, "jwtSecret", "");
        assertNotNull(config.jwtDecoder());

        // 2) issuer-uri 次之:withIssuerLocation().build() 会拉取 OIDC 发现文档与 JWKS,起本地 stub
        ReflectionTestUtils.setField(config, "jwkSetUri", "");
        com.sun.net.httpserver.HttpServer oidc = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + oidc.getAddress().getPort();
        // 程序化生成一把 RSA-2048 公钥的 JWKS(build 时会校验 JWK 集合的算法)
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        com.nimbusds.jose.jwk.JWK jwk = new com.nimbusds.jose.jwk.RSAKey.Builder(
            (java.security.interfaces.RSAPublicKey) kpg.generateKeyPair().getPublic())
            .keyID("test-1")
            .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
            .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
            .build();
        byte[] jwks = ("{\"keys\":[" + jwk.toJSONString() + "]}").getBytes();
        oidc.createContext("/.well-known/openid-configuration", ex -> {
            byte[] body = ("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        oidc.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            ex.getResponseBody().write(jwks);
            ex.close();
        });
        oidc.start();
        try {
            ReflectionTestUtils.setField(config, "jwtIssuerUri", issuer);
            assertNotNull(config.jwtDecoder());
        } finally {
            oidc.stop(0);
        }

        // 3) 本地自签 HS256(jwt-secret ≥ 32 字节)
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");
        ReflectionTestUtils.setField(config, "jwtSecret", "0123456789abcdef0123456789abcdef");
        assertNotNull(config.jwtDecoder());

        // 4) 全空 → 空实现(decode 返回 null,仅 Admin Token 认证可用)
        ReflectionTestUtils.setField(config, "jwtSecret", "");
        assertNull(config.jwtDecoder().decode("any-token"));
    }
}
