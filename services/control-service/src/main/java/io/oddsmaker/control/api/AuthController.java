package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.JwtService;
import io.oddsmaker.control.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本地账号登录（控制面前端的唯一入口）。
 *
 * <p>前端契约（web/src/stores/auth.js）：POST /api/auth/login {username,password}
 * → 200 {token, user}，token 放 localStorage，后续请求带 Bearer 头；
 * GET /api/users/me 401 时前端自动清除会话跳登录页。
 * Keycloak 不在部署里，此端点补上后控制面才可用。
 *
 * <p>登录/登出全程写审计（LOGIN/LOGIN_FAILED/LOGOUT）——
 * /api/audit-logs/auth 查询端点依赖这些 action，此前无写入方永远空页。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;

    public AuthController(UserService userService, PasswordEncoder passwordEncoder,
                          JwtService jwtService, AuditLogService auditLogService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request) {
        String ip = clientIp(request);
        String userAgent = request.getHeader("User-Agent");

        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "用户名与密码不能为空"));
        }

        UserEntity user = userService.findByUsername(username).orElse(null);
        // 用户不存在与密码错误统一返回 401，不泄露账号是否存在（审计里保留真实原因）
        if (user == null || user.passwordHash == null
                || !passwordEncoder.matches(password, user.passwordHash)) {
            logger.warn("Login failed for username={}", username);
            auditQuietly(() -> auditLogService.logLoginFailed(username, ip, userAgent, "invalid credentials"));
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
        if (user.status != UserEntity.UserStatus.ACTIVE) {
            auditQuietly(() -> auditLogService.logLoginFailed(username, ip, userAgent, "account not active"));
            return ResponseEntity.status(403).body(Map.of("message", "账号已被禁用或锁定"));
        }

        List<String> roles = user.roles == null ? List.of() : user.roles.stream().map(Enum::name).toList();
        String token = jwtService.issue(user.username, roles);

        userService.recordLogin(user);
        auditQuietly(() -> auditLogService.logLogin(user.id, user.username, ip, userAgent));

        logger.info("Login ok for username={} roles={}", username, roles);
        return ResponseEntity.ok(Map.of("token", token, "user", user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request) {
        // 无状态 JWT：客户端删除本地 token 即完成登出；
        // 能识别调用方身份（Bearer 已被 oauth2 过滤器解析）时补登出审计
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            String username = authentication.getName();
            String userId = userService.findByUsername(username).map(u -> u.id).orElse(null);
            auditQuietly(() -> auditLogService.logLogout(userId, username, clientIp(request)));
        }
        return ResponseEntity.ok().build();
    }

    /** 审计写失败不阻断登录主流程（登录是控制面唯一入口）。 */
    private void auditQuietly(Runnable auditWrite) {
        try {
            auditWrite.run();
        } catch (Exception e) {
            logger.warn("Failed to write auth audit log: {}", e.getMessage());
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
