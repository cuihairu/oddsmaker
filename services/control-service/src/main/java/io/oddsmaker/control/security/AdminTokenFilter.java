package io.oddsmaker.control.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final String token;

    public AdminTokenFilter(Environment env) {
        this.token = Binder.get(env).bind("oddsmaker.admin.token", String.class).orElse("");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/config/")) {
            return true;
        }
        // Only protect /api/* ; allow static UI and health
        return !(path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (token == null || token.isEmpty()) { // dev mode - no auth
            filterChain.doFilter(request, response);
            return;
        }
        String hdr = request.getHeader("x-admin-token");
        if (hdr != null && hdr.equals(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        String json = "{\"code\":\"unauthorized\",\"message\":\"missing_or_invalid_admin_token\"}";
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }
}
