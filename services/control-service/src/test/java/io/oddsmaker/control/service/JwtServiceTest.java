package io.oddsmaker.control.service;

import io.oddsmaker.control.security.KeycloakJwtAuthenticationConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地登录 JWT 链路：签发（JwtService HS256）→ 解码（SecurityConfig 的
 * NimbusJwtDecoder 同密钥）→ 角色提取（KeycloakJwtAuthenticationConverter
 * 读顶层 roles claim）。三个环节任何一个对不上，控制面登录后就是 401。
 */
@DisplayName("本地登录 JWT 签发/解码/角色提取")
class JwtServiceTest {

    private static final String SECRET = "oddsmaker-test-jwt-secret-0123456789abcdef0123456789";

    @Test
    @DisplayName("签发 → 解码 → converter 提取 ROLE_ADMIN，principal=用户名")
    void issueDecodeAndConvert() {
        JwtService service = new JwtService(SECRET, 60);
        String token = service.issue("admin", List.of("ADMIN", "ANALYST"));

        JwtDecoder decoder = decoder(SECRET);
        Jwt jwt = decoder.decode(token);

        JwtAuthenticationToken auth =
            (JwtAuthenticationToken) new KeycloakJwtAuthenticationConverter().convert(jwt);
        assertEquals("admin", auth.getName());
        assertTrue(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertTrue(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ANALYST")));
    }

    @Test
    @DisplayName("密钥不一致时解码被拒（签名校验生效）")
    void rejectWrongKey() {
        String token = new JwtService(SECRET, 60).issue("admin", List.of("ADMIN"));
        JwtDecoder otherDecoder = decoder("another-secret-key-0123456789abcdef0123456789abcdef!!");
        assertThrows(Exception.class, () -> otherDecoder.decode(token));
    }

    @Test
    @DisplayName("secret 短于 32 字节拒绝启动")
    void rejectShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService("short", 60));
    }

    private JwtDecoder decoder(String secret) {
        SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
            secret.getBytes(StandardCharsets.UTF_8));
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Test
    @DisplayName("secret 为 null 拒绝启动（防御侧）")
    void rejectNullSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService(null, 60));
    }

}
