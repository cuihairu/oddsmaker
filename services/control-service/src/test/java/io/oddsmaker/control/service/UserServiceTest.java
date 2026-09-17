package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private UserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserEntity();
        testUser.id = "user_test123";
        testUser.username = "testuser";
        testUser.email = "test@example.com";
        testUser.displayName = "Test User";
        testUser.status = UserEntity.UserStatus.ACTIVE;
        testUser.roles = Set.of(UserEntity.UserRole.VIEWER);
        testUser.createdAt = LocalDateTime.now();
        testUser.updatedAt = LocalDateTime.now();
    }

    @Test
    void createUser_Success() {
        when(userRepo.existsByUsername("testuser")).thenReturn(false);
        when(userRepo.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        UserEntity result = userService.createUser(testUser, "operator");

        assertNotNull(result);
        assertEquals("testuser", result.username);
        verify(userRepo).save(any(UserEntity.class));
        verify(auditLogRepo).save(any());
    }

    @Test
    void createUser_UsernameExists_ThrowsException() {
        when(userRepo.existsByUsername("testuser")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> {
            userService.createUser(testUser, "operator");
        });

        verify(userRepo, never()).save(any());
    }

    @Test
    void createUser_EmailExists_ThrowsException() {
        when(userRepo.existsByUsername("testuser")).thenReturn(false);
        when(userRepo.existsByEmail("test@example.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> {
            userService.createUser(testUser, "operator");
        });

        verify(userRepo, never()).save(any());
    }

    @Test
    void updateUser_Success() {
        UserEntity updates = new UserEntity();
        updates.displayName = "Updated Name";
        updates.email = "updated@example.com";

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.existsByEmail("updated@example.com")).thenReturn(false);
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        UserEntity result = userService.updateUser("user_test123", updates, "operator");

        assertNotNull(result);
        verify(userRepo).save(any(UserEntity.class));
    }

    @Test
    void updateUser_UserNotFound_ThrowsException() {
        when(userRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            userService.updateUser("nonexistent", new UserEntity(), "operator");
        });
    }

    @Test
    void deleteUser_Success() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        userService.deleteUser("user_test123", "operator");

        assertNotNull(testUser.deletedAt);
        assertEquals(UserEntity.UserStatus.INACTIVE, testUser.status);
        verify(userRepo).save(testUser);
    }

    @Test
    void findById_Success() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));

        Optional<UserEntity> result = userService.findById("user_test123");

        assertTrue(result.isPresent());
        assertEquals("testuser", result.get().username);
    }

    @Test
    void findById_NotFound_ReturnsEmpty() {
        when(userRepo.findById("nonexistent")).thenReturn(Optional.empty());

        Optional<UserEntity> result = userService.findById("nonexistent");

        assertFalse(result.isPresent());
    }

    @Test
    void findByUsername_Success() {
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        Optional<UserEntity> result = userService.findByUsername("testuser");

        assertTrue(result.isPresent());
        assertEquals("user_test123", result.get().id);
    }

    @Test
    void recordLogin_Success() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        userService.recordLogin("user_test123", "192.168.1.1");

        assertNotNull(testUser.lastLoginAt);
        assertEquals("192.168.1.1", testUser.lastLoginIp);
        assertEquals(1L, testUser.loginCount);
        verify(userRepo).save(testUser);
        verify(auditLogRepo).save(any());
    }

    @Test
    void updateRoles_Success() {
        Set<UserEntity.UserRole> newRoles = Set.of(UserEntity.UserRole.ADMIN, UserEntity.UserRole.MANAGER);

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        UserEntity result = userService.updateRoles("user_test123", newRoles, "operator");

        assertNotNull(result);
        assertEquals(newRoles, result.roles);
        verify(userRepo).save(testUser);
        verify(auditLogRepo).save(any());
    }

    @Test
    void lockUser_Success() {
        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        UserEntity result = userService.lockUser("user_test123", "operator");

        assertNotNull(result);
        assertEquals(UserEntity.UserStatus.LOCKED, result.status);
        verify(userRepo).save(testUser);
        verify(auditLogRepo).save(any());
    }

    @Test
    void unlockUser_Success() {
        testUser.status = UserEntity.UserStatus.LOCKED;

        when(userRepo.findById("user_test123")).thenReturn(Optional.of(testUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(testUser);

        UserEntity result = userService.unlockUser("user_test123", "operator");

        assertNotNull(result);
        assertEquals(UserEntity.UserStatus.ACTIVE, result.status);
        verify(userRepo).save(testUser);
        verify(auditLogRepo).save(any());
    }

    @Test
    void createUser_DefaultsApplied() {
        UserEntity fresh = new UserEntity();  // id/status/roles 全空 → 走默认填充
        fresh.username = "newuser";
        fresh.email = "new@example.com";
        when(userRepo.existsByUsername("newuser")).thenReturn(false);
        when(userRepo.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity saved = userService.createUser(fresh, "operator");
        assertNotNull(saved.id);
        assertEquals(UserEntity.UserStatus.ACTIVE, saved.status);
        assertFalse(saved.roles.isEmpty());
    }

    @Test
    void recordLoginEntityOverload_DoesNotAudit() {
        testUser.loginCount = 2L;
        userService.recordLogin(testUser);

        assertNotNull(testUser.lastLoginAt);
        assertEquals(3L, testUser.loginCount);
        verify(userRepo).save(testUser);
        verify(auditLogRepo, never()).save(any());  // 登录不写业务审计
    }

    @Test
    void listSearchAndRoleQueries_Delegate() {
        org.springframework.data.domain.Pageable pageable =
            org.springframework.data.domain.PageRequest.of(0, 10);
        when(userRepo.findByStatusAndDeletedAtIsNull(UserEntity.UserStatus.ACTIVE, pageable))
            .thenReturn(org.springframework.data.domain.Page.empty());
        when(userRepo.searchByName("test", pageable))
            .thenReturn(org.springframework.data.domain.Page.empty());
        when(userRepo.findByRole(UserEntity.UserRole.VIEWER))
            .thenReturn(java.util.List.of(testUser));

        assertEquals(0, userService.listUsers(pageable).getTotalElements());
        assertEquals(0, userService.searchUsers("test", pageable).getTotalElements());
        assertEquals(1, userService.findByRole(UserEntity.UserRole.VIEWER).size());
    }
}