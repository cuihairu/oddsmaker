package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.PermissionEntity;
import io.oddsmaker.control.jpa.PermissionRepo;
import io.oddsmaker.control.jpa.RoleEntity;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.jpa.UserRoleRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * ML 模型与实验平台 Service 深度单元测试（LAY-005 拆分）：资源+动作权限判定、角色分配与失效行复活、作用域清单、锁定用户权限。与 MLModelServiceTest / ExperimentServiceTest / TrackingPlanServiceTest / PermissionServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("权限 Service 深度测试（判定/分配/作用域）")
class PermissionServiceDeepTest {

    // ==================== PermissionService 依赖 ====================

    @Mock
    private UserRepo userRepo;

    @Mock
    private RoleRepo roleRepo;

    @Mock
    private PermissionRepo permissionRepo;

    @Mock
    private UserRoleRepo userRoleRepo;

    @InjectMocks
    private PermissionService permissionService;

    // ==================== 测试数据构造 ====================

    private static UserEntity user(UserEntity.UserStatus status) {
        UserEntity u = new UserEntity();
        u.id = "u1";
        u.username = "alice";
        u.status = status;
        return u;
    }

    private static PermissionEntity permission(String id) {
        PermissionEntity p = new PermissionEntity();
        p.id = id;
        p.enabled = true;
        p.type = PermissionEntity.PermissionType.API;
        p.action = PermissionEntity.PermissionAction.READ;
        p.scope = PermissionEntity.PermissionScope.GLOBAL;
        int colon = id.indexOf(':');
        p.resourceType = colon > 0 ? id.substring(0, colon) : id;
        return p;
    }

    private static RoleEntity role(String id, PermissionEntity... perms) {
        RoleEntity r = new RoleEntity();
        r.id = id;
        r.name = id.toUpperCase();
        r.type = RoleEntity.RoleType.SYSTEM;
        r.enabled = true;
        r.permissions = new HashSet<>(Arrays.asList(perms));
        return r;
    }

    private static UserRoleEntity assignment(String roleId, String gameId, String environment) {
        UserRoleEntity a = new UserRoleEntity();
        a.userId = "u1";
        a.roleId = roleId;
        a.gameId = gameId;
        a.environment = environment;
        a.enabled = true;
        return a;
    }

    @Test
    @DisplayName("hasPermission(resourceType, action) - 命中权限定义后按权限 ID 检查")
    void hasPermission_byResourceAndAction_found() {
        PermissionEntity read = permission("game:read");
        lenient().when(permissionRepo.findByResourceTypeAndAction("game", PermissionEntity.PermissionAction.READ))
            .thenReturn(Optional.of(read));
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findValidByUserId(eq("u1"), any(LocalDateTime.class)))
            .thenReturn(List.of(assignment("operator", null, null)));
        lenient().when(roleRepo.findById("operator"))
            .thenReturn(Optional.of(role("operator", read)));

        assertThat(permissionService.hasPermission("u1", "game", PermissionEntity.PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("hasPermission(resourceType, action) - 权限定义缺失直接返回 false")
    void hasPermission_byResourceAndAction_permissionMissing() {
        lenient().when(permissionRepo.findByResourceTypeAndAction("game", PermissionEntity.PermissionAction.EXPORT))
            .thenReturn(Optional.empty());

        assertThat(permissionService.hasPermission("u1", "game", PermissionEntity.PermissionAction.EXPORT)).isFalse();
    }

    @Test
    @DisplayName("assignRole - 用户不存在抛参数异常")
    void assignRole_userNotFoundThrows() {
        lenient().when(userRepo.existsById("u_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.assignRole("u_x", "operator", null, null, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("assignRole - 角色不存在抛参数异常")
    void assignRole_roleNotFoundThrows() {
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(roleRepo.existsById("r_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.assignRole("u1", "r_x", null, null, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Role not found");
    }

    @Test
    @DisplayName("assignRole - 已失效的历史分配复活该行而非另起新行")
    void assignRole_reassignAfterDisabled() {
        UserRoleEntity disabled = assignment("operator", null, null);
        disabled.enabled = false;
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(roleRepo.existsById("operator")).thenReturn(true);
        lenient().when(userRoleRepo.findByUserId("u1"))
            .thenReturn(List.of(disabled));
        lenient().when(userRoleRepo.save(any(UserRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserRoleEntity saved = permissionService.assignRole("u1", "operator", null, null, "admin");

        assertThat((Object) saved).isSameAs(disabled);
        assertThat(saved.userId).isEqualTo("u1");
        assertThat(saved.roleId).isEqualTo("operator");
        assertThat(saved.gameId).isNull();
        assertThat(saved.environment).isNull();
        assertThat(saved.assignedBy).isEqualTo("admin");
        assertThat(saved.enabled).isTrue();
        verify(userRoleRepo).save(any(UserRoleEntity.class));
    }

    @Test
    @DisplayName("listAssignments - 用户不存在抛参数异常")
    void listAssignments_userNotFoundThrows() {
        lenient().when(userRepo.existsById("u_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.listAssignments("u_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("listAssignments - 输出 global/game/environment 三种 scope")
    void listAssignments_reportsScopes() {
        UserRoleEntity global = assignment("viewer", null, null);
        UserRoleEntity gameScoped = assignment("operator", "g1", null);
        UserRoleEntity envScoped = assignment("analyst", "g1", "prod");
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(userRoleRepo.findByUserId("u1"))
            .thenReturn(List.of(global, gameScoped, envScoped));
        lenient().when(roleRepo.findById("viewer")).thenReturn(Optional.of(role("viewer")));
        lenient().when(roleRepo.findById("operator")).thenReturn(Optional.empty());
        lenient().when(roleRepo.findById("analyst")).thenReturn(Optional.of(role("analyst")));

        List<Map<String, Object>> assignments = permissionService.listAssignments("u1");

        assertThat(assignments).hasSize(3);
        assertThat(assignments.get(0))
            .containsEntry("scope", "global")
            .containsEntry("roleName", "VIEWER")
            .containsEntry("valid", true);
        assertThat(assignments.get(1))
            .containsEntry("scope", "game")
            .containsEntry("roleName", "operator");
        assertThat(assignments.get(2))
            .containsEntry("scope", "environment")
            .containsEntry("gameId", "g1")
            .containsEntry("environment", "prod");
    }

    @Test
    @DisplayName("hasGamePermission - 用户被锁定返回 false")
    void hasGamePermission_lockedUser() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.LOCKED)));

        assertThat(permissionService.hasGamePermission("u1", "g1", "game:read")).isFalse();
    }

}
