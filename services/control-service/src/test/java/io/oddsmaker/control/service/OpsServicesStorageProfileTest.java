package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：存储配置创建（slug 生成/重复/空名校验）与更新、删除占用校验与各类查询、分支对侧（id 空白走 slug/软删 profile/slug null 拒绝）。与 StorageProfileServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesStorageProfileTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @InjectMocks
    private StorageProfileService storageProfileService;

    private StorageProfileEntity storageProfile(String id, String name, StorageProfileEntity.IsolationStrategy strategy) {
        StorageProfileEntity e = new StorageProfileEntity();
        e.id = id;
        e.name = name;
        e.displayName = name;
        e.isolationStrategy = strategy;
        e.active = true;
        return e;
    }

    @Test
    @DisplayName("存储配置：创建（slug 生成/重复校验/空名校验）与更新")
    void storageProfileCreateAndUpdate() {
        lenient().when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageProfileRepo.existsById("prod-dedicated-cluster")).thenReturn(false);
        lenient().when(storageProfileRepo.existsById("sp-exist")).thenReturn(true);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("dup-name")).thenReturn(true);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("brand-new")).thenReturn(false);

        StorageProfileDTO dto = new StorageProfileDTO();
        dto.name = "Prod Dedicated Cluster";
        dto.kafkaCluster = "k1";
        StorageProfileDTO created = storageProfileService.createStorageProfile(dto);
        assertEquals("prod-dedicated-cluster", created.id);
        assertEquals(StorageProfileEntity.IsolationStrategy.SHARED, created.isolationStrategy);
        assertEquals(Boolean.TRUE, created.active);

        StorageProfileDTO dupId = new StorageProfileDTO();
        dupId.id = "sp-exist";
        dupId.name = "whatever";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(dupId));

        StorageProfileDTO dupName = new StorageProfileDTO();
        dupName.id = "sp-new";
        dupName.name = "dup-name";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(dupName));

        StorageProfileDTO blank = new StorageProfileDTO();
        blank.id = null;
        blank.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(blank));

        StorageProfileEntity entity = storageProfile("sp1", "old-name", StorageProfileEntity.IsolationStrategy.SHARED);
        lenient().when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(entity));
        lenient().when(storageProfileRepo.findById("none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("none", dto));

        StorageProfileDTO update = new StorageProfileDTO();
        update.name = "brand-new";
        update.displayName = "Brand New";
        update.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        StorageProfileDTO updated = storageProfileService.updateStorageProfile("sp1", update);
        assertEquals("brand-new", updated.name);
        assertEquals(StorageProfileEntity.IsolationStrategy.DEDICATED, updated.isolationStrategy);

        StorageProfileDTO conflict = new StorageProfileDTO();
        conflict.name = "dup-name";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("sp1", conflict));

        StorageProfileEntity deleted = storageProfile("sp-gone", "gone", StorageProfileEntity.IsolationStrategy.SHARED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findById("sp-gone")).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("sp-gone", update));
    }

    @Test
    @DisplayName("存储配置：删除（占用校验）与各类查询（详情/列表/策略/活跃/分页）")
    void storageProfileDeleteAndQueries() {
        lenient().when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StorageProfileEntity shared = storageProfile("sp1", "shared-pool", StorageProfileEntity.IsolationStrategy.SHARED);
        StorageProfileEntity dedicated = storageProfile("sp2", "dedicated-pool", StorageProfileEntity.IsolationStrategy.DEDICATED);
        StorageProfileEntity deleted = storageProfile("sp3", "deleted-pool", StorageProfileEntity.IsolationStrategy.SHARED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(shared));
        lenient().when(storageProfileRepo.findById("sp2")).thenReturn(Optional.of(dedicated));
        lenient().when(storageProfileRepo.findById("none")).thenReturn(Optional.empty());
        lenient().when(environmentRepo.findByStorageProfileIdAndDeletedAtIsNull(anyString())).thenReturn(List.of());
        lenient().when(environmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp1")).thenReturn(List.of(new GameEnvironmentEntity(), new GameEnvironmentEntity()));

        assertThrows(IllegalArgumentException.class, () -> storageProfileService.deleteStorageProfile("none"));
        assertThrows(IllegalStateException.class, () -> storageProfileService.deleteStorageProfile("sp1"));

        assertEquals(2, storageProfileService.getStorageProfile("sp1").orElseThrow().totalEnvironments);
        assertTrue(storageProfileService.getStorageProfile("none").isEmpty());

        lenient().when(storageProfileRepo.findAll()).thenReturn(List.of(deleted, dedicated, shared));
        List<StorageProfileDTO> all = storageProfileService.getStorageProfiles();
        assertEquals(2, all.size());
        assertEquals("dedicated-pool", all.get(0).name);
        assertEquals(1, storageProfileService.getStorageProfilesByStrategy(StorageProfileEntity.IsolationStrategy.DEDICATED).size());
        assertEquals(1, storageProfileService.getStorageProfilesByStrategy(StorageProfileEntity.IsolationStrategy.SHARED).size());

        lenient().when(storageProfileRepo.findByActiveTrueAndDeletedAtIsNullOrderByNameAsc()).thenReturn(List.of(shared));
        assertEquals(1, storageProfileService.getActiveStorageProfiles().size());

        lenient().when(storageProfileRepo.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(shared, dedicated)));
        org.springframework.data.domain.Page<StorageProfileDTO> page =
            storageProfileService.getStorageProfiles(PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements());
        assertEquals("shared-pool", page.getContent().get(0).name);

        storageProfileService.deleteStorageProfile("sp2");
        assertNotNull(dedicated.deletedAt);
        assertFalse(dedicated.active);
    }

    @Test
    @DisplayName("分支对侧：id 空白走 slug、name null/同名不查重、软删 profile 的 get/delete、slug null 拒绝")
    void storageProfileBranchSides() {
        lenient().when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(false);

        // dto.id 空白串（trim().isEmpty() 侧）→ 由 name 生成 slug
        StorageProfileDTO blankId = new StorageProfileDTO();
        blankId.id = "   ";
        blankId.name = "Blank Id Pool";
        assertEquals("blank-id-pool", storageProfileService.createStorageProfile(blankId).id);

        // name=null 且 id=null → slug(null) 抛 IAE（value == null 侧）
        StorageProfileDTO noName = new StorageProfileDTO();
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(noName));

        // update：name null → 跳过重名校验（第一条件 false 侧）
        StorageProfileEntity entity = storageProfile("sp-b", "keep-name", StorageProfileEntity.IsolationStrategy.SHARED);
        lenient().when(storageProfileRepo.findById("sp-b")).thenReturn(Optional.of(entity));
        StorageProfileDTO noNameUpdate = new StorageProfileDTO();
        noNameUpdate.displayName = "仅改显示名";
        assertEquals("keep-name", storageProfileService.updateStorageProfile("sp-b", noNameUpdate).name);

        // update：name 与实体同名 → 不查重（第二条件 false 侧）
        StorageProfileDTO sameName = new StorageProfileDTO();
        sameName.name = "keep-name";
        assertEquals("keep-name", storageProfileService.updateStorageProfile("sp-b", sameName).name);
        verify(storageProfileRepo, org.mockito.Mockito.never()).existsByNameAndDeletedAtIsNull("keep-name");

        // 软删 profile：get → empty（filter false 侧）；delete → IAE
        StorageProfileEntity deleted = storageProfile("sp-del", "gone", StorageProfileEntity.IsolationStrategy.SHARED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findById("sp-del")).thenReturn(Optional.of(deleted));
        assertTrue(storageProfileService.getStorageProfile("sp-del").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.deleteStorageProfile("sp-del"));
    }

}
