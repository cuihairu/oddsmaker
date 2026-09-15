package io.oddsmaker.control.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 本地登录 JWT 签发（HS256 自签）。
 *
 * 此前 JwtDecoder 是空实现、Keycloak 不在部署里，前端 /api/auth/login 无对应端点，
 * 整个控制面无法登录。本服务把仓库里已依赖的 jjwt 接起来：
 * 自签 token 的 claims 与 KeycloakJwtAuthenticationConverter 的读取约定对齐
 * （subject / preferred_username = 用户名，roles = 顶层角色数组）。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${oddsmaker.auth.jwt-secret:}") String secret,
                      @Value("${oddsmaker.auth.jwt-ttl-seconds:86400}") long ttlSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "oddsmaker.auth.jwt-secret 未配置或短于 32 字节，本地登录不可用（HS256 要求 >=256bit）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(username)
            .claim("preferred_username", username)
            .claim("roles", roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }
}
