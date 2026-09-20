package io.oddsmaker.control.config;

import io.oddsmaker.control.security.AdminTokenFilter;
import io.oddsmaker.control.security.KeycloakJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * 安全配置
 * 支持Keycloak OAuth2认证和Admin Token认证
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String jwtIssuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${oddsmaker.auth.jwt-secret:}")
    private String jwtSecret;

    /**
     * 公开端点列表（不需要认证）
     */
    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/api/config/**",
        "/api/auth/login",
        "/api/auth/logout",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs/**",
        "/swagger-resources/**",
        "/webjars/**",
        "/error"
    };

    /**
     * API端点列表
     */
    private static final String[] API_ENDPOINTS = {
        "/api/**"
    };

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 主安全过滤器链 - 支持OAuth2和Admin Token认证
     */
    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(HttpSecurity http, AdminTokenFilter adminTokenFilter) throws Exception {
        return http
            // CSRF配置 - 禁用（API服务使用token认证）
            .csrf(csrf -> csrf.disable())

            // CORS配置
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 会话管理 - 无状态
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // HTTP Basic禁用
            .httpBasic(basic -> basic.disable())

            // 表单登录禁用
            .formLogin(form -> form.disable())

            // 登出禁用
            .logout(logout -> logout.disable())

            // OAuth2资源服务器配置
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())
                )
            )

            // 授权配置
            .authorizeHttpRequests(auth -> auth
                // 公开端点
                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                // 内部端点只认服务间令牌：AdminTokenFilter 校验 x-internal-token 后设
                // ROLE_INTERNAL，此处要求该角色是第二道防线（登录 JWT/ApiKey 全拒）——
                // InternalApiKeyResp 返回 secret 本体，绝不能走 permitAll 裸奔。
                .requestMatchers("/internal/**").hasRole("INTERNAL")
                // API端点需要认证
                .requestMatchers(API_ENDPOINTS).authenticated()
                // 其他请求拒绝
                .anyRequest().denyAll()
            )

            // 添加Admin Token过滤器在OAuth2过滤器之前
            .addFilterBefore(adminTokenFilter, UsernamePasswordAuthenticationFilter.class)

            // 安全头配置
            .headers(headers -> headers
                // 内容安全策略
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; frame-ancestors 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' https:;"))

                // XSS防护
                .xssProtection(xss -> xss
                    .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))

                // HSTS
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                    .preload(true))

                // Referrer策略
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))

                // 权限策略
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), camera=(), microphone=(), payment=(), usb=(), magnetometer=(), gyroscope=(), accelerometer=()"))
            )

            // 异常处理
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
                })
            )

            .build();
    }

    /**
     * Keycloak JWT认证转换器
     */
    @Bean
    KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    /**
     * JWT解码器：优先 Keycloak（jwk-set-uri/issuer-uri），其次本地自签 HS256
     * （oddsmaker.auth.jwt-secret，配合 AuthController 的本地登录），都没有才回落
     * 空实现（仅 Admin Token 认证可用）。
     */
    @Bean
    JwtDecoder jwtDecoder() {
        // 分支选择静默是排障黑洞（曾出现同 jar 容器/裸机行为分叉），启动即打分支依据
        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).warn(
            "jwtDecoder branch inputs: jwkSetUri='{}', jwtIssuerUri='{}', jwtSecretLen={}",
            jwkSetUri, jwtIssuerUri,
            jwtSecret == null ? -1 : jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if (jwkSetUri != null && !jwkSetUri.isEmpty()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        if (jwtIssuerUri != null && !jwtIssuerUri.isEmpty()) {
            return NimbusJwtDecoder.withIssuerLocation(jwtIssuerUri).build();
        }
        if (jwtSecret != null && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32) {
            javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
        }
        // 如果没有配置JWT，返回一个空实现（依赖Admin Token认证）
        return token -> null;
    }

    /**
     * CORS配置
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 允许的源（生产环境应限制为具体域名）
        configuration.setAllowedOrigins(Arrays.asList(
            "http://localhost:3000",
            "http://localhost:8080",
            "http://localhost:8085",
            "https://*.oddsmaker.local"
        ));

        // 允许的HTTP方法
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));

        // 允许的头
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers",
            "X-Api-Key",
            "X-Signature",
            "X-Admin-Token"
        ));

        // 暴露的头
        configuration.setExposedHeaders(Arrays.asList(
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Credentials",
            "Authorization",
            "Content-Disposition"
        ));

        // 允许凭证
        configuration.setAllowCredentials(true);

        // 预检请求缓存时间
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }
}
