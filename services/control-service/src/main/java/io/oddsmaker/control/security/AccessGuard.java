package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 显式权限门卫：连接 Spring Security 认证与 PermissionService 的 scope 检查。
 *
 * ROLE_ADMIN / ROLE_INTERNAL（服务间令牌）直通；
 * 普通用户按 global/game scope 解析权限。
 */
@Component
public class AccessGuard {

    private final PermissionService permissionService;

    public AccessGuard(PermissionService permissionService) {
        this.permissionService = permissionService;
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
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_ADMIN".equals(name) || "ROLE_INTERNAL".equals(name)) {
                return true;
            }
        }
        return permissionService.hasGamePermission(auth.getName(), gameId, permissionId);
    }

    private void check(String gameId, String permissionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new SecurityException("User not authenticated");
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_ADMIN".equals(name) || "ROLE_INTERNAL".equals(name)) {
                return;
            }
        }
        String userId = auth.getName();
        boolean allowed = gameId != null
            ? permissionService.hasGamePermission(userId, gameId, permissionId)
            : permissionService.hasPermission(userId, permissionId);
        if (!allowed) {
            throw new SecurityException(
                "Access denied: missing permission " + permissionId
                    + (gameId != null ? " for game " + gameId : ""));
        }
    }
}
