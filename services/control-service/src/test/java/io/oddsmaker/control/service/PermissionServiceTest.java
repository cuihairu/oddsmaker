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
    void getUserPermissions_ReturnsAllPermissions() {
        when(userRoleRepo.findValidByUserId(eq("user_test123"), any(LocalDateTime.class)))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(testRole));

        Set<String> permissions = permissionService.getUserPermissions("user_test123");

        assertNotNull(permissions);
        assertTrue(permissions.contains("game:read"));
    }

    @Test
    void assignRole_Success() {
        when(userRepo.existsById("user_test123")).thenReturn(true);
        when(roleRepo.existsById("viewer")).thenReturn(true);
        when(userRoleRepo.findByUserIdAndRoleId("user_test123", "viewer"))
            .thenReturn(Optional.empty());
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
        when(userRoleRepo.findByUserIdAndRoleId("user_test123", "viewer"))
            .thenReturn(Optional.of(testUserRole));

        assertThrows(IllegalArgumentException.class, () -> {
            permissionService.assignRole("user_test123", "viewer", null, null, "admin");
        });
    }

    @Test
    void revokeRole_Success() {
        when(userRoleRepo.findByUserId("user_test123"))
            .thenReturn(List.of(testUserRole));

        permissionService.revokeRole("user_test123", "viewer", null, null);

        verify(userRoleRepo).deleteAll(List.of(testUserRole));
    }

    @Test
    void getUserRoles_ReturnsRoles() {
        when(userRoleRepo.findByUserIdAndEnabledTrue("user_test123"))
            .thenReturn(List.of(testUserRole));
        when(roleRepo.findById("viewer")).thenReturn(Optional.of(testRole));

        List<RoleEntity> roles = permissionService.getUserRoles("user_test123");

        assertNotNull(roles);
        assertEquals(1, roles.size());
        assertEquals("viewer", roles.get(0).id);
    }

    @Test
    void hasPermission_UserNotFound_ThrowsException() {
        when(userRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            permissionService.hasPermission("nonexistent", "game:read");
        });
    }

    // ========== P1 六角色矩阵测试 ==========

    private Map<String, PermissionEntity> samplePermissions() {
        Map<String, PermissionEntity> map = new HashMap<>();
        for (String id : List.of(
            "game:create", "game:read", "game:update", "game:delete",
            "environment:create", "environment:read", "environment:update", "environment:delete",
            "api_key:create", "api_key:read", "api_key:update", "api_key:delete",
            "experiment:create", "experiment:read", "experiment:update", "experiment:delete",
            "risk_rule:create", "risk_rule:read", "risk_rule:update", "risk_rule:delete",
            "user:create", "user:read", "user:update", "user:delete",
            "audit_log:read", "system:manage")) {
            PermissionEntity p = new PermissionEntity();
            p.id = id;
            p.enabled = true;
            String[] parts = id.split(":", 2);
            p.resourceType = parts[0];
            p.action = PermissionEntity.PermissionAction.valueOf(parts[1].toUpperCase());
            map.put(id, p);
        }
        return map;
    }

    /**
     * 运行 initializeDefaults() 后收集角色→权限ID 集合。
     * roleRepo.save 直接返回入参；permissionRepo 按内存数据应答。
     */
    private Map<String, Set<String>> buildRoleMatrix() {
        Map<String, PermissionEntity> permissions = samplePermissions();
        Map<String, RoleEntity> savedRoles = new HashMap<>();

        when(permissionRepo.findAll()).thenReturn(new ArrayList<>(permissions.values()));
        when(permissionRepo.findByAction(any(PermissionEntity.PermissionAction.class)))
            .thenAnswer(inv -> permissions.values().stream()
                .filter(p -> p.action == inv.getArgument(0))
                .collect(java.util.stream.Collectors.toList()));
        when(permissionRepo.findByType(any(PermissionEntity.PermissionType.class)))
            .thenAnswer(inv -> new ArrayList<>(permissions.values()));
        when(permissionRepo.findByResourceType(any(String.class)))
            .thenAnswer(inv -> permissions.values().stream()
                .filter(p -> p.resourceType.equals(inv.getArgument(0)))
                .collect(java.util.stream.Collectors.toList()));
        when(permissionRepo.findByResourceTypeAndAction(any(String.class), any()))
            .thenAnswer(inv -> permissions.values().stream()
                .filter(p -> p.resourceType.equals(inv.getArgument(0)) && p.action == inv.getArgument(1))
                .findFirst());
        when(permissionRepo.existsById(any(String.class))).thenReturn(false);
        when(roleRepo.findById(any(String.class))).thenReturn(Optional.empty());
        when(roleRepo.save(any(RoleEntity.class)))
            .thenAnswer(inv -> {
                RoleEntity r = inv.getArgument(0);
                savedRoles.put(r.id, r);
                return r;
            });

        permissionService.initializeDefaults();

        Map<String, Set<String>> matrix = new HashMap<>();
        savedRoles.forEach((id, role) ->
            matrix.put(id, role.getPermissionIds() == null ? Set.of() : role.getPermissionIds()));
        return matrix;
    }

    @Test
    void roleMatrix_OwnerHasEverything() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        assertTrue(matrix.get("owner").containsAll(List.of(
            "game:delete", "user:delete", "system:manage", "risk_rule:create", "audit_log:read")));
    }

    @Test
    void roleMatrix_OperatorManagesConfigButNotUsersOrSystem() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        Set<String> operator = matrix.get("operator");
        assertTrue(operator.containsAll(List.of(
            "game:update", "environment:delete", "api_key:create", "experiment:delete")));
        assertFalse(operator.contains("user:update"));
        assertFalse(operator.contains("system:manage"));
        assertFalse(operator.contains("game:delete"));
    }

    @Test
    void roleMatrix_AnalystReadsAllAndRunsExperiments() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        Set<String> analyst = matrix.get("analyst");
        assertTrue(analyst.containsAll(List.of(
            "game:read", "risk_rule:read", "experiment:create", "experiment:delete")));
        assertFalse(analyst.contains("risk_rule:delete"));
        assertFalse(analyst.contains("api_key:create"));
    }

    @Test
    void roleMatrix_RiskAdminOwnsRiskRulesOnly() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        Set<String> riskAdmin = matrix.get("risk_admin");
        assertTrue(riskAdmin.containsAll(List.of(
            "risk_rule:create", "risk_rule:update", "risk_rule:delete", "game:read")));
        assertFalse(riskAdmin.contains("api_key:delete"));
        assertFalse(riskAdmin.contains("user:update"));
    }

    @Test
    void roleMatrix_DeveloperManagesKeysAndExperiments() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        Set<String> developer = matrix.get("developer");
        assertTrue(developer.containsAll(List.of(
            "api_key:create", "api_key:delete", "experiment:update", "environment:create")));
        assertFalse(developer.contains("risk_rule:update"));
        assertFalse(developer.contains("user:create"));
    }

    @Test
    void roleMatrix_ViewerIsReadOnly() {
        Map<String, Set<String>> matrix = buildRoleMatrix();
        Set<String> viewer = matrix.get("viewer");
        assertTrue(viewer.containsAll(List.of(
            "game:read", "environment:read", "api_key:read", "risk_rule:read")));
        assertFalse(viewer.contains("game:update"));
        assertFalse(viewer.contains("risk_rule:create"));
        assertFalse(viewer.contains("experiment:delete"));
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
    void hasEnvironmentPermission_InvalidRolesSkipped_ReturnsFalse() {
        UserRoleEntity invalid = new UserRoleEntity();
        invalid.userId = "user_test123";
        invalid.roleId = "viewer";
        invalid.enabled = false;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("user_test123", "g1", "prod"))
            .thenReturn(List.of(invalid));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "g1"))
            .thenReturn(List.of(invalid));
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of(invalid));

        assertFalse(permissionService.hasEnvironmentPermission("user_test123", "g1", "prod", "game:read"));
    }

    @Test
    void getEnvironmentPermissions_InvalidRolesSkipped_ReturnsEmpty() {
        UserRoleEntity invalid = new UserRoleEntity();
        invalid.userId = "user_test123";
        invalid.roleId = "viewer";
        invalid.enabled = false;

        when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("user_test123", "g1", "prod"))
            .thenReturn(List.of(invalid));
        when(userRoleRepo.findByUserIdAndGameId("user_test123", "g1"))
            .thenReturn(List.of(invalid));
        when(userRoleRepo.findGlobalByUserId("user_test123"))
            .thenReturn(List.of(invalid));

        assertTrue(permissionService.getEnvironmentPermissions("user_test123", "g1", "prod").isEmpty());
    }
}