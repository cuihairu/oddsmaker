package io.oddsmaker.control.config;

import io.oddsmaker.control.security.AdminTokenFilter;
import io.oddsmaker.control.security.KeycloakJwtAuthenticationConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置类测试：安全配置 Bean（CORS/JWT 解码器/密码编码器）与 OpenAPI/公司上下文。
 */
@DisplayName("配置类测试")
class ConfigsTest {

    // ===== SecurityConfig =====

    @Test
    @DisplayName("密码编码器：BCrypt 加密与校验")
    void passwordEncoder() {
        SecurityConfig config = new SecurityConfig();
        PasswordEncoder encoder = config.passwordEncoder();
        String hash = encoder.encode("s3cret");
        assertNotEquals("s3cret", hash);
        assertTrue(encoder.matches("s3cret", hash));
        assertTrue(!encoder.matches("wrong", hash));
    }

    @Test
    @DisplayName("JWT 解码器：未配置时返回空实现（token→null）")
    void jwtDecoderEmptyWithoutConfig() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwkSetUri", "");
        ReflectionTestUtils.setField(config, "jwtIssuerUri", "");
        assertNull(config.jwtDecoder().decode("any-token"));
    }

    @Test
    @DisplayName("CORS：本地源/方法/凭证与预检缓存")
    void corsConfiguration() {
        SecurityConfig config = new SecurityConfig();
        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(
            new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/games"));
        assertTrue(cors.getAllowedOrigins().contains("http://localhost:3000"));
        assertTrue(cors.getAllowedMethods().contains("PATCH"));
        assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertTrue(cors.getMaxAge() > 0);
        assertTrue(cors.getAllowedHeaders().contains("X-Admin-Token"));
    }

    @Test
    @DisplayName("Keycloak 转换器 Bean 构造")
    void keycloakConverterBean() {
        assertNotNull(new SecurityConfig().keycloakJwtAuthenticationConverter());
        assertInstanceOf2(new SecurityConfig().keycloakJwtAuthenticationConverter());
    }

    private void assertInstanceOf2(KeycloakJwtAuthenticationConverter converter) {
        assertTrue(converter instanceof KeycloakJwtAuthenticationConverter);
    }

    // ===== OpenApiConfig =====

    @Test
    @DisplayName("OpenAPI：公司元信息注入与 contact")
    void openApiMetadata() {
        CompanyContextProperties company = new CompanyContextProperties();
        company.setName("Oddsmaker Studio");
        OpenApiConfig config = new OpenApiConfig(company);
        io.swagger.v3.oas.models.OpenAPI openApi = config.customOpenAPI();
        assertNotNull(openApi.getInfo());
        assertEquals("Oddsmaker Gaming Analytics Platform API", openApi.getInfo().getTitle());
        assertNotNull(openApi.getInfo().getContact());
    }

    // ===== CompanyContextProperties =====

    @Test
    @DisplayName("公司上下文属性默认值与读写")
    void companyContextProperties() {
        CompanyContextProperties company = new CompanyContextProperties();
        assertEquals("Oddsmaker Studio", company.getName());
        assertEquals("local", company.getDeploymentId());
        assertEquals("Asia/Shanghai", company.getDefaultTimezone());
        assertEquals("cn", company.getDataRegion());
        company.setName("ACME");
        company.setDeploymentId("prod-1");
        company.setDefaultTimezone("UTC");
        company.setDataRegion("eu");
        assertEquals("ACME", company.getName());
        assertEquals("prod-1", company.getDeploymentId());
        assertEquals("UTC", company.getDefaultTimezone());
        assertEquals("eu", company.getDataRegion());
    }

    // ===== AdminTokenFilter 快速冒烟（config 链路） =====

    @Test
    @DisplayName("AdminTokenFilter 环境绑定：空配置走开发模式")
    void adminTokenFilterWithEmptyEnv() throws Exception {
        MockEnvironment env = new MockEnvironment();
        AdminTokenFilter filter = new AdminTokenFilter(env);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/games");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("JWT 解码器：全 null 字段回落空实现（@Value 缺省注入前的裸实例侧）")
    void jwtDecoderAllNullFields() {
        SecurityConfig config = new SecurityConfig();
        assertNull(config.jwtDecoder().decode("any-token"));
    }

    @Test
    @DisplayName("JWT 解码器：jwk-set-uri 优先分支（withJwkSetUri 为惰性构造，不发请求）")
    void jwtDecoderJwkSetUriBranch() {
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "jwkSetUri", "http://127.0.0.1:1/jwks");
        assertNotNull(config.jwtDecoder());
    }

    @Test
    @DisplayName("JWT 解码器：issuer-uri 分支（本地 HttpServer 提供 OIDC discovery + 空 JWKS）")
    void jwtDecoderIssuerUriBranch() throws Exception {
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        java.nio.charset.Charset cs = java.nio.charset.StandardCharsets.UTF_8;
        server.createContext("/.well-known/openid-configuration", ex -> {
            byte[] body = ("{\"issuer\":\"" + base + "\",\"jwks_uri\":\"" + base + "/jwks\"}")
                .getBytes(cs);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/jwks", ex -> {
            // fromIssuerLocation 要求 JWKS 至少含一个带算法的 key，空 keys 会抛
            // "Failed to find any algorithms"——动态生成 RSA 公钥输出 JWK
            byte[] body;
            try {
                java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                java.security.interfaces.RSAPublicKey pub =
                    (java.security.interfaces.RSAPublicKey) kpg.generateKeyPair().getPublic();
                String n = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(pub.getModulus().toByteArray());
                String e = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(pub.getPublicExponent().toByteArray());
                body = ("{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\","
                    + "\"kid\":\"test\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}").getBytes(cs);
            } catch (java.security.NoSuchAlgorithmException nsae) {
                throw new IllegalStateException(nsae);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            SecurityConfig config = new SecurityConfig();
            ReflectionTestUtils.setField(config, "jwtIssuerUri", base);
            assertNotNull(config.jwtDecoder());
        } finally {
            server.stop(0);
        }
    }

}
