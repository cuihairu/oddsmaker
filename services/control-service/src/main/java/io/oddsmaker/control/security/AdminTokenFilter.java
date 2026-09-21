package io.oddsmaker.control.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Admin Token过滤器
 * 支持x-admin-token头认证，作为Keycloak OAuth2认证的备用方案
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final String adminToken;
    private final String internalToken;

    public AdminTokenFilter(Environment env) {
        this.adminToken = Binder.get(env).bind("oddsmaker.admin.token", String.class).orElse("");
        this.internalToken = Binder.get(env).bind("oddsmaker.internal.token", String.class).orElse("");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/config/")) {
            return true;
        }
        // 登录/登出由 SecurityConfig 的 permitAll 放行：登录请求本身不带任何 token，
        // 在此拦截会永远 401（AuthController 也就无法签发 token）。
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        // 只处理 API 和内部端点的请求
        return !path.startsWith("/api/") && !path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // /internal/ 只认服务间令牌，必须最先判定：下面的 Bearer 放行分支会给持有效
        // 登录 JWT 的请求让路（oauth2 链认证通过 + 授权层放行），任何低权限用户都能
        // 绕过 x-internal-token 直读内部凭据端点（InternalApiKeyResp 含 secret 本体）。
        if (path.startsWith("/internal/")) {
            String suppliedToken = request.getHeader("x-internal-token");
            if (matches(suppliedToken, internalToken)) {
                var auth = new UsernamePasswordAuthenticationToken(
                    "internal-gateway", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                filterChain.doFilter(request, response);
                return;
            }
            unauthorized(response, "missing_or_invalid_internal_token");
            return;
        }

        // 如果已经有OAuth2认证，跳过Admin Token检查
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bearer 请求（本地登录 JWT / Keycloak）交给 Security 链的 oauth2 filter：
        // 本过滤器注册在 oauth2 解码之前，此时 SecurityContext 尚无 JwtAuthenticationToken，
        // 不放行会把合法登录会话误杀成 401。无效 token 由 oauth2 filter 拒绝。
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 如果没有配置 Admin Token，开发模式自动认证。
        // adminToken 经构造器 Binder.bind(...).orElse("") 兜底，恒非 null——null 子条件不可达，等价删去
        if (adminToken.isEmpty()) {
            // 开发模式 - 设置匿名认证
            var auth = new UsernamePasswordAuthenticationToken(
                "dev-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        // 检查x-admin-token头
        String hdr = request.getHeader("x-admin-token");
        if (matches(hdr, adminToken)) {
            // 有效的Admin Token - 设置认证
            var auth = new UsernamePasswordAuthenticationToken(
                "admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        // 没有有效的认证
        unauthorized(response, "missing_or_invalid_admin_token");
    }

    private boolean matches(String supplied, String expected) {
        if (supplied == null || supplied.isEmpty() || expected == null || expected.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        String json = "{\"code\":\"unauthorized\",\"message\":\"" + message + "\"}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }
}
