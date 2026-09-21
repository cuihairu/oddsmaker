package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

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

    private UserEntity testUser;
    private RoleEntity testRole;
    private PermissionEntity testPermission;
    private UserRoleEntity testUserRole;

    @BeforeEach
    void setUp() {
        // 创建测试用户
        testUser = new UserEntity();
        testUser.id = "user_test123";
        testUser.username = "testuser";
        testUser.status = UserEntity.UserStatus.ACTIVE;
        testUser.roles = Set.of(UserEntity.UserRole.VIEWER);

        // 创建测试权限
        testPermission = new PermissionEntity();
        testPermission.id = "game:read";
        testPermission.name = "Read games";
        testPermission.type = PermissionEntity.PermissionType.API;
        testPermission.resourceType = "game";
        testPermission.action = PermissionEntity.PermissionAction.READ;
        testPermission.scope = PermissionEntity.PermissionScope.GLOBAL;
        testPermission.enabled = true;

        // 创建测试角色
        testRole = new RoleEntity();
        testRole.id = "viewer";
        testRole.name = "Viewer";
        testRole.type = RoleEntity.RoleType.SYSTEM;
        testRole.level = 50;
        testRole.enabled = true;
        testRole.permissions = Set.of(testPermission);

        // 创建测试用户角色关联
        testUserRole = new UserRoleEntity();
        testUserRole.id = 1L;
        testUserRole.userId = "user_test123";
        testUserRole.roleId = "viewer";
        testUserRole.enabled = true;
    }

    @Test
    void hasPermission_UserHasPermission_ReturnsTrue() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findValidByUserId(eq("user_test123"), any(LocalDateTime.class)))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(testRole));

        boolean result = permissionService.hasPermission("user_test123", "game:read");

        assertTrue(result);
    }

    @Test
    void hasPermission_UserNotActive_ReturnsFalse() {
        testUser.status = UserEntity.UserStatus.INACTIVE;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));

        boolean result = permissionService.hasPermission("user_test123", "game:read");

        assertFalse(result);
    }

    @Test
    void hasPermission_UserLocked_ReturnsFalse() {
        testUser.status = UserEntity.UserStatus.LOCKED;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));

        boolean result = permissionService.hasPermission("user_test123", "game:read");

        assertFalse(result);
    }

    @Test
    void hasPermission_NoRoleAssignment_ReturnsFalse() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findValidByUserId(eq("user_test123"), any(LocalDateTime.class)))
            .thenReturn(Collections.emptyList());

        boolean result = permissionService.hasPermission("user_test123", "game:read");

        assertFalse(result);
    }

    @Test
    void hasPermission_RoleDoesNotHavePermission_ReturnsFalse() {
        RoleEntity roleWithoutPermission = new RoleEntity();
        roleWithoutPermission.id = "basic";
        roleWithoutPermission.enabled = true;
        roleWithoutPermission.permissions = Collections.emptySet();

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findValidByUserId(eq("user_test123"), any(LocalDateTime.class)))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(roleWithoutPermission));

        boolean result = permissionService.hasPermission("user_test123", "game:read");

        assertFalse(result);
    }

    @Test
    void hasGamePermission_UserHasGlobalPermission_ReturnsTrue() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(Collections.emptyList());
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(testRole));

        boolean result = permissionService.hasGamePermission("user_test123", "game_123", "game:read");

        assertTrue(result);
    }

    @Test
    void hasGamePermission_UserHasGamePermission_ReturnsTrue() {
        UserRoleEntity gameRole = new UserRoleEntity();
        gameRole.userId = "user_test123";
        gameRole.roleId = "viewer";
        gameRole.gameId = "game_123";
        gameRole.enabled = true;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of(gameRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(testRole));

        boolean result = permissionService.hasGamePermission("user_test123", "game_123", "game:read");

        assertTrue(result);
    }

    @Test
    void assignRole_Success() {
        when(userRepo.existsById("user_test123")).thenReturn(true);
        when(roleRepo.existsById("viewer")).thenReturn(true);
        when(userRoleRepo.findByUserId("user_test123")).thenReturn(List.of());
        when(userRoleRepo.save(any(UserRoleEntity.class))).thenReturn(testUserRole);

        UserRoleEntity result = permissionService.assignRole("user_test123", "viewer", null, null, "admin");

        assertNotNull(result);
        assertEquals("user_test123", result.userId);
        assertEquals("viewer", result.roleId);
        verify(userRoleRepo).save(any(UserRoleEntity.class));
    }

    @Test
    void assignRole_AlreadyAssigned_ThrowsException() {
        when(userRepo.existsById("user_test123")).thenReturn(true);
        when(roleRepo.existsById("viewer")).thenReturn(true);
        when(userRoleRepo.findByUserId("user_test123")).thenReturn(List.of(testUserRole));

        assertThrows(IllegalArgumentException.class, () -> {
            permissionService.assignRole("user_test123", "viewer", null, null, "admin");
        });
    }

    @Test
    void assignRole_SameRoleDifferentScope_CreatesNewAssignment() {
        // global 已有 viewer 时，game 级再分 viewer 不受"已分配"检查误伤（scope-aware）
        when(userRepo.existsById("user_test123")).thenReturn(true);
        when(roleRepo.existsById("viewer")).thenReturn(true);
        when(userRoleRepo.findByUserId("user_test123")).thenReturn(List.of(testUserRole));
        when(userRoleRepo.save(any(UserRoleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        UserRoleEntity result = permissionService.assignRole("user_test123", "viewer", "g1", null, "admin");

        assertEquals("g1", result.gameId);
        verify(userRoleRepo).save(any(UserRoleEntity.class));
    }

    @Test
    void assignRole_ExistingInvalidRow_RevivesInsteadOfDuplicating() {
        // 同 scope 已有失效行 → 复用该行（表无唯一约束，不另起新行防重复堆积）
        testUserRole.enabled = false;
        when(userRepo.existsById("user_test123")).thenReturn(true);
        when(roleRepo.existsById("viewer")).thenReturn(true);
        when(userRoleRepo.findByUserId("user_test123")).thenReturn(List.of(testUserRole));
        when(userRoleRepo.save(any(UserRoleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        UserRoleEntity result = permissionService.assignRole("user_test123", "viewer", null, null, "admin2");

        assertSame(testUserRole, result);
        assertTrue(result.isEnabled());
        assertEquals("admin2", result.assignedBy);
        verify(userRoleRepo).save(testUserRole);
    }

    @Test
    void revokeRole_Success() {
        when(userRoleRepo.findByUserId("user_test123"))
            .thenReturn(List.of(testUserRole));

        permissionService.revokeRole("user_test123", "viewer", null, null);

        verify(userRoleRepo).deleteAll(List.of(testUserRole));
    }

    @Test
    void hasPermission_UserNotFound_ThrowsException() {
        when(userRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            permissionService.hasPermission("nonexistent", "game:read");
        });
    }

    @Test
    void revokeRole_Scoped_OnlyMatchesExactScope() {
        UserRoleEntity globalAssignment = new UserRoleEntity();
        globalAssignment.id = 1L;
        globalAssignment.userId = "u1";
        globalAssignment.roleId = "operator";
        globalAssignment.enabled = true;

        UserRoleEntity gameAssignment = new UserRoleEntity();
        gameAssignment.id = 2L;
        gameAssignment.userId = "u1";
        gameAssignment.roleId = "operator";
        gameAssignment.gameId = "game_1";
        gameAssignment.enabled = true;

        when(userRoleRepo.findByUserId("u1"))
            .thenReturn(List.of(globalAssignment, gameAssignment));

        // 只回收 game_1 上的 operator，global 保留
        permissionService.revokeRole("u1", "operator", "game_1", null);

        verify(userRoleRepo).deleteAll(List.of(gameAssignment));
    }

    @Test
    void revokeRole_NotFound_Throws() {
        when(userRoleRepo.findByUserId("u2")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
            () -> permissionService.revokeRole("u2", "viewer", null, null));
    }

    @Test
    void hasGamePermission_InvalidRolesSkipped_ReturnsFalse() {
        // enabled=false 的角色分配 isValid() 为 false → continue 跳过，循环自然结束后返回 false
        UserRoleEntity invalid = new UserRoleEntity();
        invalid.userId = "user_test123";
        invalid.roleId = "viewer";
        invalid.enabled = false;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of(invalid));
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of(invalid));

        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));
    }

    @Test
    void hasGamePermission_GameRoleValidButPermissionMissing_ReturnsFalse() {
        // valid 且 enabled 但不含目标权限的角色走完循环体（continue 短路不经过循环回边）
        UserRoleEntity valid = new UserRoleEntity();
        valid.userId = "user_test123";
        valid.roleId = "viewer";
        valid.enabled = true;

        RoleEntity roleWithoutPermission = new RoleEntity();
        roleWithoutPermission.id = "viewer";
        roleWithoutPermission.enabled = true;
        roleWithoutPermission.permissions = Collections.emptySet();

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of(valid));
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of());
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(roleWithoutPermission));

        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));
    }

    @Test
    void hasGamePermission_GlobalRoleValidButPermissionMissing_ReturnsFalse() {
        // 游戏角色为空、全局角色 valid 但无权限——覆盖全局角色循环体落空
        UserRoleEntity valid = new UserRoleEntity();
        valid.userId = "user_test123";
        valid.roleId = "viewer";
        valid.enabled = true;

        RoleEntity roleWithoutPermission = new RoleEntity();
        roleWithoutPermission.id = "viewer";
        roleWithoutPermission.enabled = true;
        roleWithoutPermission.permissions = Collections.emptySet();

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of());
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of(valid));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(roleWithoutPermission));

        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));
    }

    @Test
    void hasPermissionAndGamePermission_RoleMissingOrDisabled_ReturnsFalse() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));

        // 63 行 role == null 侧（findById 空）
        when(userRoleRepo.findValidByUserId(eq("user_test123"), any(LocalDateTime.class)))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.empty());
        assertFalse(permissionService.hasPermission("user_test123", "game:read"));

        // 121/138 行 role == null 侧（游戏与全局两循环各一）
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of(testUserRole));
        when(userRoleRepo.findGlobalByUserId("user_test123")).thenReturn(List.of(testUserRole));
        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));

        // 角色存在但 disabled：63/121/138 行 isEnabled()==false 侧
        RoleEntity disabled = new RoleEntity();
        disabled.id = "viewer";
        disabled.enabled = false;
        disabled.permissions = java.util.Set.of(testPermission);
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(disabled));
        assertFalse(permissionService.hasPermission("user_test123", "game:read"));

        // 121 行 disabled 侧（仅游戏角色循环）
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123"))
            .thenReturn(List.of(testUserRole));
        when(userRoleRepo.findGlobalByUserId("user_test123")).thenReturn(List.of());
        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));

        // 138 行 disabled 侧（仅全局角色循环）
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "game_123")).thenReturn(List.of());
        when(userRoleRepo.findGlobalByUserId("user_test123")).thenReturn(List.of(testUserRole));
        assertFalse(permissionService.hasGamePermission("user_test123", "game_123", "game:read"));
    }

}
