package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 公司内 RBAC 角色分配 API：global/game/environment 三级 scope。
 * 角色 id 以 roles 表为准（role_operator/role_game_admin/role_analyst/role_marketing/
 * role_finance/role_developer/role_viewer/role_qa），可分配集合动态取启用角色——
 * 历史注记：静态白名单曾用 code 形态（owner/operator/…）与 assignRole 按 id 查找断裂，
 * assign 对任何输入都失败（V0.9.9 前该端点无成功调用者）。
 */
@RestController
@RequestMapping("/api/users/{userId}/role-assignments")
public class RoleAssignmentController {

    private final PermissionService permissionService;
    private final AccessGuard accessGuard;
    private final AuditLogService auditLog;
    private final RoleRepo roleRepo;

    public RoleAssignmentController(PermissionService permissionService,
                                    AccessGuard accessGuard,
                                    AuditLogService auditLog,
                                    RoleRepo roleRepo) {
        this.permissionService = permissionService;
        this.accessGuard = accessGuard;
        this.auditLog = auditLog;
        this.roleRepo = roleRepo;
    }

    public static class AssignReq {
        public String roleId;       // roles 表主键（role_* 形态）
        public String gameId;       // null = global
        public String environment;  // null = game/global
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String userId) {
        accessGuard.requirePermission("user:read");
        return ResponseEntity.ok(permissionService.listAssignments(userId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> assign(@PathVariable String userId,
                                                      @RequestBody AssignReq req) {
        accessGuard.requirePermission("user:update");
        validateScope(req);
        UserRoleEntity assignment = permissionService.assignRole(
            userId, req.roleId, req.gameId, req.environment, currentOperator());
        auditLog.logPermissionChange(currentOperator(), currentOperator(), userId,
            userId, "GRANT_ROLE", null,
            "roleId=" + req.roleId + ",gameId=" + req.gameId + ",environment=" + req.environment, null);
        return ResponseEntity.ok(toResp(assignment));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String userId,
                                                      @RequestParam String roleId,
                                                      @RequestParam(required = false) String gameId,
                                                      @RequestParam(required = false) String environment) {
        accessGuard.requirePermission("user:update");
        permissionService.revokeRole(userId, roleId, gameId, environment);
        auditLog.logPermissionChange(currentOperator(), currentOperator(), userId,
            userId, "REVOKE_ROLE",
            "roleId=" + roleId + ",gameId=" + gameId + ",environment=" + environment, null, null);
        return ResponseEntity.ok(Map.of("revoked", true, "userId", userId, "roleId", roleId));
    }

    /** 白名单动态取启用角色（roles 表为单一事实来源），防再与种子漂移。 */
    private void validateScope(AssignReq req) {
        if (req.roleId == null || req.roleId.isBlank()) {
            throw new IllegalArgumentException("roleId is required");
        }
        boolean assignable = roleRepo.findByEnabledTrue().stream()
            .anyMatch(r -> req.roleId.equals(r.id));
        if (!assignable) {
            throw new IllegalArgumentException("Unknown or disabled role: " + req.roleId);
        }
        if (req.environment != null && req.gameId == null) {
            throw new IllegalArgumentException("environment scope requires gameId");
        }
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }

    private static Map<String, Object> toResp(UserRoleEntity assignment) {
        return Map.of(
            "userId", assignment.userId,
            "roleId", assignment.roleId,
            "gameId", assignment.gameId == null ? "" : assignment.gameId,
            "environment", assignment.environment == null ? "" : assignment.environment,
            "enabled", assignment.isEnabled());
    }
}
