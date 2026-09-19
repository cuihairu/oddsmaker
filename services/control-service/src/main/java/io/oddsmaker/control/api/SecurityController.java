package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.*;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.SecurityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 安全API控制器
 * 提供MFA、SSO和安全会话管理的接口；鉴权走 AccessGuard 行内风格（security:read / security:manage），
 * MFA 自助端点与 SSO 回调/会话创建/校验等运行时口子保持无 guard。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_SECURITY_SETTINGS') 静态式与 isAuthenticated() 混合，
 * 而全仓只签发 ROLE_* authority，方法安全开启后静态式注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    @Autowired
    private SecurityService securityService;

    @Autowired
    private AccessGuard accessGuard;

    // ============== MFA Endpoints ==============

    /**
     * 启用MFA
     */
    @PostMapping("/mfa/enable")
    public ResponseEntity<MFAConfigEntity> enableMFA(@RequestBody MFAEnableRequest request) {
        MFAConfigEntity config = securityService.enableMFA(
            request.userId,
            request.mfaMethod,
            request.phoneNumber,
            request.emailAddress
        );
        return ResponseEntity.ok(config);
    }

    /**
     * 验证并激活MFA
     */
    @PostMapping("/mfa/verify")
    public ResponseEntity<MFAConfigEntity> verifyMFA(@RequestBody MFAVerifyRequest request) {
        MFAConfigEntity config = securityService.verifyAndActivateMFA(request.configId, request.code);
        return ResponseEntity.ok(config);
    }

    /**
     * 禁用MFA
     */
    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMFA(@RequestBody MFADisableRequest request) {
        securityService.disableMFA(request.configId, request.userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 验证MFA代码
     */
    @PostMapping("/mfa/validate")
    public ResponseEntity<Boolean> validateMFA(@RequestBody MFAValidateRequest request) {
        boolean valid = securityService.verifyMFA(request.userId, request.code);
        return ResponseEntity.ok(valid);
    }

    /**
     * 获取用户的MFA配置
     */
    @GetMapping("/mfa/user/{userId}")
    public ResponseEntity<List<MFAConfigEntity>> getUserMFAConfigs(@PathVariable String userId) {
        accessGuard.requirePermission("security:read");
        List<MFAConfigEntity> configs = securityService.getUserMFAConfigs(userId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 检查用户是否启用了MFA
     */
    @GetMapping("/mfa/user/{userId}/enabled")
    public ResponseEntity<Boolean> isUserMFAEnabled(@PathVariable String userId) {
        accessGuard.requirePermission("security:read");
        boolean enabled = securityService.isUserMFAEnabled(userId);
        return ResponseEntity.ok(enabled);
    }

    // ============== SSO Endpoints ==============

    /**
     * 创建SSO配置
     */
    @PostMapping("/sso/configs")
    public ResponseEntity<SSOConfigEntity> createSSOConfig(@RequestBody SSOConfigRequest request) {
        accessGuard.requirePermission("security:manage");
        SSOConfigEntity config = securityService.createSSOConfig(
            request.name,
            request.description,
            request.ssoProtocol,
            request.config,
            request.createdBy
        );
        return ResponseEntity.ok(config);
    }

    /**
     * 获取活跃的SSO配置
     */
    @GetMapping("/sso/configs/active")
    public ResponseEntity<List<SSOConfigEntity>> getActiveSSOConfigs() {
        List<SSOConfigEntity> configs = securityService.getActiveSSOConfigs();
        return ResponseEntity.ok(configs);
    }

    /**
     * 激活SSO配置
     */
    @PostMapping("/sso/configs/{configId}/activate")
    public ResponseEntity<SSOConfigEntity> activateSSO(@PathVariable String configId) {
        accessGuard.requirePermission("security:manage");
        SSOConfigEntity config = securityService.activateSSO(configId);
        return ResponseEntity.ok(config);
    }

    /**
     * SSO登录回调——诚实失败：无 IdP（SAML/OIDC）集成，回调没有可执行的处理逻辑，
     * 不再返回硬编码假成功+假 userId（全仓无调用方，行为变更无消费方影响）。
     */
    @PostMapping("/sso/callback")
    public ResponseEntity<Map<String, String>> ssoCallback(@RequestBody SSOCallbackRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", "SSO callback processing is not implemented: no IdP integration (SAML/OIDC) exists"));
    }

    // ============== Session Endpoints ==============

    /**
     * 创建会话
     */
    @PostMapping("/sessions/create")
    public ResponseEntity<SecuritySessionEntity> createSession(@RequestBody SessionCreateRequest request) {
        SecuritySessionEntity session = securityService.createSession(
            request.userId,
            request.authMethod,
            request.ipAddress,
            request.userAgent,
            request.timeoutMinutes
        );
        return ResponseEntity.ok(session);
    }

    /**
     * 验证会话
     */
    @GetMapping("/sessions/validate")
    public ResponseEntity<SecuritySessionEntity> validateSession(@RequestParam String token) {
        SecuritySessionEntity session = securityService.validateSession(token);
        return ResponseEntity.ok(session);
    }

    /**
     * 终止会话
     */
    @PostMapping("/sessions/{sessionId}/terminate")
    public ResponseEntity<Void> terminateSession(
            @PathVariable String sessionId,
            @RequestBody TerminateSessionRequest request) {
        securityService.terminateSession(sessionId, request.terminatedBy, request.reason);
        return ResponseEntity.ok().build();
    }

    /**
     * 终止用户的所有会话
     */
    @PostMapping("/sessions/user/{userId}/terminate-all")
    public ResponseEntity<Void> terminateAllUserSessions(
            @PathVariable String userId,
            @RequestBody TerminateAllSessionsRequest request) {
        accessGuard.requirePermission("security:manage");
        securityService.terminateAllUserSessions(userId, request.terminatedBy, request.reason);
        return ResponseEntity.ok().build();
    }

    // ============== Policy Endpoints ==============

    /**
     * 获取密码策略
     */
    @GetMapping("/policies/password")
    public ResponseEntity<List<SecurityPolicyEntity>> getPasswordPolicies(@RequestParam(required = false) String gameId) {
        accessGuard.requirePermission("security:read");
        List<SecurityPolicyEntity> policies = securityService.getPasswordPolicies(gameId);
        return ResponseEntity.ok(policies);
    }

    /**
     * 获取会话策略
     */
    @GetMapping("/policies/session")
    public ResponseEntity<List<SecurityPolicyEntity>> getSessionPolicies(@RequestParam(required = false) String gameId) {
        accessGuard.requirePermission("security:read");
        List<SecurityPolicyEntity> policies = securityService.getSessionPolicies(gameId);
        return ResponseEntity.ok(policies);
    }

    /**
     * 获取MFA策略
     */
    @GetMapping("/policies/mfa")
    public ResponseEntity<List<SecurityPolicyEntity>> getMFAPolicies(@RequestParam(required = false) String gameId) {
        accessGuard.requirePermission("security:read");
        List<SecurityPolicyEntity> policies = securityService.getMFAPolicies(gameId);
        return ResponseEntity.ok(policies);
    }

    /**
     * 检查游戏是否需要MFA
     */
    @GetMapping("/policies/mfa/required")
    public ResponseEntity<Boolean> isMFARequired(@RequestParam String gameId) {
        boolean required = securityService.isMFARequired(gameId);
        return ResponseEntity.ok(required);
    }

    // Request DTOs

    public static class MFAEnableRequest {
        public String userId;
        public MFAConfigEntity.MFAMethod mfaMethod;
        public String phoneNumber;
        public String emailAddress;
    }

    public static class MFAVerifyRequest {
        public String configId;
        public String code;
    }

    public static class MFADisableRequest {
        public String configId;
        public String userId;
    }

    public static class MFAValidateRequest {
        public String userId;
        public String code;
    }

    public static class SSOConfigRequest {
        public String name;
        public String description;
        public SSOConfigEntity.SSOProtocol ssoProtocol;
        public Map<String, Object> config;
        public String createdBy;
    }

    public static class SSOCallbackRequest {
        public String code;
        public String state;
        public String provider;
    }

    public static class SessionCreateRequest {
        public String userId;
        public SecuritySessionEntity.AuthMethod authMethod;
        public String ipAddress;
        public String userAgent;
        public Integer timeoutMinutes;
    }

    public static class TerminateSessionRequest {
        public String terminatedBy;
        public String reason;
    }

    public static class TerminateAllSessionsRequest {
        public String terminatedBy;
        public String reason;
    }
}
