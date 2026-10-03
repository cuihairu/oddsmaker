package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
import io.oddsmaker.control.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 显式权限门卫：连接 Spring Security 认证与 PermissionService 的 scope 检查。
 *
 * ROLE_ADMIN / ROLE_INTERNAL（服务间令牌）直通；
 * 普通用户按 global/game scope 解析权限。
 *
 * 身份解析：Authentication.getName() 是登录名（AuthController 签发 JWT 的 subject 就是
 * username），而 PermissionService 内部按 users.id 取用户（findById）。两者只在
 * id 恰好等于登录名时才相等——种子里 admin 的 id 是 user_admin，普通账号更是
 * user_&lt;uuid&gt;，于是所有非 ROLE_ADMIN 的请求都会在权限校验里撞上
 * IllegalArgumentException("User not found: &lt;登录名&gt;") → HTTP 400，
 * 表现为「非管理员整站接口全 400，管理员却一切正常」。故这里先按登录名解析出用户 id
 * 再判权（与 AuthController 登出时 userService.findByUsername(...).id 的取法一致）；
 * 解析不到（账号被删/改名）按无权限处理，不给 500 也不放行。
 */
@Component
public class AccessGuard {

    private final PermissionService permissionService;
    private final UserService userService;

    public AccessGuard(PermissionService permissionService, UserService userService) {
        this.permissionService = permissionService;
        this.userService = userService;
    }

    public void requirePermission(String permissionId) {
        check(null, permissionId);
    }

    public void requireGamePermission(String gameId, String permissionId) {
        check(gameId, permissionId);
    }

    /** 非抛出版：判断当前用户是否对游戏具备权限（跨游戏结果过滤用） */
    public boolean canAccessGame(String gameId, String permissionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if (hasPrivilege(auth)) {
            return true;
        }
        String userId = resolveUserId(auth);
        return userId != null && permissionService.hasGamePermission(userId, gameId, permissionId);
    }

    private void check(String gameId, String permissionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new SecurityException("User not authenticated");
        }
        if (hasPrivilege(auth)) {
            return;
        }
        String userId = resolveUserId(auth);
        boolean allowed = userId != null && (gameId != null
            ? permissionService.hasGamePermission(userId, gameId, permissionId)
            : permissionService.hasPermission(userId, permissionId));
        if (!allowed) {
            throw new SecurityException(
                "Access denied: missing permission " + permissionId
                    + (gameId != null ? " for game " + gameId : ""));
        }
    }

    /** ROLE_ADMIN / ROLE_INTERNAL 直通：这两类不依赖 users 表（后者是服务间令牌）。 */
    private boolean hasPrivilege(Authentication auth) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_ADMIN".equals(name) || "ROLE_INTERNAL".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 登录名 → 用户 id；查不到返回 null（调用方按无权限处理）。 */
    private String resolveUserId(Authentication auth) {
        return userService.findByUsername(auth.getName()).map(user -> user.id).orElse(null);
    }
}
