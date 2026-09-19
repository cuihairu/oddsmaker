package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RoleEntity;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.security.AccessGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色清单 API：角色分配 UI 的下拉数据源（roles 表启用角色的简化视图）。
 * 历史注记：种子 8 行的 type 列值不在 RoleType 枚举内（V0.9.9 迁移修数据为 SYSTEM），
 * 此前任何 role_* 行实体化即抛——本端点在数据修复后才是可调用的。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    @Autowired
    private AccessGuard accessGuard;

    @Autowired
    private RoleRepo roleRepo;

    @GetMapping
    public List<Map<String, Object>> list() {
        accessGuard.requirePermission("user:read");
        return roleRepo.findByEnabledTrue().stream()
            .map(RoleController::toResp)
            .toList();
    }

    /** 只出下拉所需字段，不序列化 EAGER permissions 大对象。 */
    private static Map<String, Object> toResp(RoleEntity role) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", role.id);
        out.put("name", role.name);
        out.put("description", role.description);
        out.put("level", role.level);
        return out;
    }
}
