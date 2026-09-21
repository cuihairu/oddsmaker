package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·片A补充：api/ControlService 的未走分支侧
 * （null/blank 防御三元的缺侧经反射直调私有 converter 触发——公开入口恒定赋值使部分 null 侧不可达）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("片A分支补充：ControlService")
class ControlServiceBranchTopUpTest {

    @Mock ApiKeyRepo keyRepo;
    @Mock GameRepo gameRepo;
    @Mock GameEnvironmentRepo envRepo;
    @Mock StorageProfileRepo storageProfileRepo;
    @Mock io.oddsmaker.control.service.AuditLogService auditLog;

    @InjectMocks ControlService controlService;

    @BeforeEach
    void setUp() {
        lenient().when(keyRepo.searchApiKeysByScope(any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        lenient().when(storageProfileRepo.existsById(any())).thenReturn(false);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull(any())).thenReturn(false);
        lenient().when(storageProfileRepo.save(any(StorageProfileEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private GameEntity game(boolean deleted) {
        GameEntity entity = new GameEntity();
        entity.id = "g1";
        entity.deletedAt = deleted ? LocalDateTime.now() : null;
        return entity;
    }

    private GameEnvironmentEntity env(boolean deleted) {
        GameEnvironmentEntity entity = new GameEnvironmentEntity();
        entity.id = "env1";
        entity.gameId = "g1";
        entity.name = "prod";
        entity.deletedAt = deleted ? LocalDateTime.now() : null;
        return entity;
    }

    @Test
    @DisplayName("createKey：已删游戏/已删环境均拒绝；keyRole 空白回落 CLIENT")
    void createKeyDeletedGameEnvAndBlankRole() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game(true)));
        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g1", "env1", "k"));

        when(gameRepo.findById("g1")).thenReturn(Optional.of(game(false)));
        when(envRepo.findById("env_del")).thenReturn(Optional.of(env(true)));
        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g1", "env_del", "k"));

        when(envRepo.findById("env1")).thenReturn(Optional.of(env(false)));
        Models.ApiKeyResp resp = controlService.createKey("g1", "env1", "k", "   ");
        assertEquals("client", resp.keyRole);   // blank → CLIENT
    }

    @Test
    @DisplayName("searchKeys：gameId/environmentId 为 null 时空串下推")
    void searchKeysNullParams() {
        controlService.searchKeys(null, null, "q", 0, 10);
        verify(keyRepo).searchApiKeysByScope(org.mockito.ArgumentMatchers.eq(""),
            org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.eq("q"), any(Pageable.class));
    }

    @Test
    @DisplayName("createStorageProfile：id 缺失走 slug；name null/空白拒绝")
    void createStorageProfileSides() {
        Models.CreateStorageProfileReq req = new Models.CreateStorageProfileReq();
        req.name = "Shared X";   // id null → slug("shared-x")
        Models.StorageProfileResp resp = controlService.createStorageProfile(req);
        assertEquals("shared-x", resp.id);
        assertEquals("SHARED", resp.isolationStrategy);   // isolationStrategy 缺省 SHARED

        Models.CreateStorageProfileReq nullName = new Models.CreateStorageProfileReq();
        nullName.id = "sp1";
        nullName.name = null;
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(nullName));

        Models.CreateStorageProfileReq blankName = new Models.CreateStorageProfileReq();
        blankName.id = "sp1";
        blankName.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(blankName));
    }

    @Test
    @DisplayName("updateStorageProfile：已删拒绝；同名不判重名直接应用")
    void updateStorageProfileSides() {
        StorageProfileEntity deleted = new StorageProfileEntity();
        deleted.id = "sp_del";
        deleted.name = "p";
        deleted.deletedAt = LocalDateTime.now();
        when(storageProfileRepo.findById("sp_del")).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp_del", new Models.CreateStorageProfileReq()));

        StorageProfileEntity existing = new StorageProfileEntity();
        existing.id = "sp1";
        existing.name = "p";
        when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(existing));
        Models.CreateStorageProfileReq sameName = new Models.CreateStorageProfileReq();
        sameName.name = "p";   // 与现值相同 → 跳过重名检查
        assertEquals("p", controlService.updateStorageProfile("sp1", sameName).name);
    }

    @Test
    @DisplayName("deleteStorageProfile：已删返回 false")
    void deleteStorageProfileDeleted() {
        StorageProfileEntity deleted = new StorageProfileEntity();
        deleted.id = "sp_del";
        deleted.deletedAt = LocalDateTime.now();
        when(storageProfileRepo.findById("sp_del")).thenReturn(Optional.of(deleted));
        assertFalse(controlService.deleteStorageProfile("sp_del"));
    }

    @Test
    @DisplayName("createStorageProfile：id 显式直用与 blank 回落 slug；updateStorageProfile：name null 跳过判重与改名判重两侧")
    void storageProfileIdAndNameSides() {
        // 199 行：id 非 null 非 blank → 直用（整体 false 边）
        Models.CreateStorageProfileReq explicit = new Models.CreateStorageProfileReq();
        explicit.id = "custom-id";
        explicit.name = "Custom";
        assertEquals("custom-id", controlService.createStorageProfile(explicit).id);
        // 199 行：id 非 null 但 blank → slug 侧（c1F c2T 边）
        Models.CreateStorageProfileReq blankId = new Models.CreateStorageProfileReq();
        blankId.id = "   ";
        blankId.name = "Blank Id";
        assertEquals("blank-id", controlService.createStorageProfile(blankId).id);

        // 217 行：req.name == null → c1F 跳过重名检查；随后的 applyStorageProfile 拒绝无 name
        // （IAE 在 217 行条件求值之后抛出，c1F 边已覆盖）
        StorageProfileEntity existing = new StorageProfileEntity();
        existing.id = "sp2";
        existing.name = "orig";
        when(storageProfileRepo.findById("sp2")).thenReturn(Optional.of(existing));
        Models.CreateStorageProfileReq nullName = new Models.CreateStorageProfileReq();
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp2", nullName));

        // 217 行：name 不同且不重名（c1T c2T c3F）→ 正常改名
        Models.CreateStorageProfileReq rename = new Models.CreateStorageProfileReq();
        rename.name = "fresh-name";
        assertEquals("fresh-name", controlService.updateStorageProfile("sp2", rename).name);

        // 217 行：name 不同且重名（c3T）→ 拒绝
        when(storageProfileRepo.existsByNameAndDeletedAtIsNull("dup-name")).thenReturn(true);
        Models.CreateStorageProfileReq dup = new Models.CreateStorageProfileReq();
        dup.name = "dup-name";
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp2", dup));
    }

    @Test
    @DisplayName("converter 非 null 侧：createdAt 有值时 toString 输出")
    void converterCreatedAtNonNullSide() {
        ApiKeyEntity keyed = new ApiKeyEntity();
        keyed.createdAt = LocalDateTime.of(2026, 9, 21, 5, 0);
        Models.KeyDetailResp detail = ReflectionTestUtils.invokeMethod(controlService, "toDetail", keyed);
        assertEquals("2026-09-21T05:00", detail.createdAt);   // 268 行非 null toString 侧
    }

    @Test
    @DisplayName("converter null 侧：keyType/status/createdAt/isolationStrategy 缺省时回 null")
    void converterNullSides() {
        ApiKeyEntity bare = new ApiKeyEntity();   // keyType/status 初始化器须显式置 null 才能覆盖 null 侧
        bare.keyType = null;
        bare.status = null;
        Models.ApiKeyResp resp = ReflectionTestUtils.invokeMethod(controlService, "toResp", bare);
        assertNull(resp.keyRole);

        Models.KeyDetailResp detail = ReflectionTestUtils.invokeMethod(controlService, "toDetail", bare);
        assertNull(detail.keyRole);
        assertNull(detail.status);
        assertNull(detail.createdAt);

        GameEnvironmentEntity env = new GameEnvironmentEntity();   // status 初始化器 ACTIVE，须显式置 null
        env.status = null;
        Models.InternalApiKeyResp internal = ReflectionTestUtils.invokeMethod(controlService,
            "toInternalDetail", bare, env);
        assertNull(internal.keyRole);
        assertNull(internal.envStatus);

        StorageProfileEntity storage = new StorageProfileEntity(); // isolationStrategy 初始化器 SHARED
        storage.isolationStrategy = null;
        Models.StorageProfileResp profile = ReflectionTestUtils.invokeMethod(controlService,
            "toStorageProfile", storage);
        assertNull(profile.isolationStrategy);

        // split：空白 → null；空 token 剔除
        assertNull(ReflectionTestUtils.invokeMethod(controlService, "split", "   "));
        assertEquals(List.of("a", "b"), ReflectionTestUtils.invokeMethod(controlService, "split", "a, ,b"));

        // storageProfileIdFor：环境已删 → null
        when(envRepo.findById("env_del")).thenReturn(Optional.of(env(true)));
        assertNull(ReflectionTestUtils.invokeMethod(controlService, "storageProfileIdFor", "env_del"));
    }
}
