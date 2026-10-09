package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式身份合并服务测试。
 *
 * 覆盖：合并主链路（links 重挂/三元组冲突吊销、identity_id 反查链路、标量回填与计数归并、
 * mergedFrom 追加、secondary 墓碑化、12 参审计）、参数与状态护栏（同体/缺参/跨游戏/已合并）、
 * 反查链路复用（unmerge 后再合并不新建 tombstone）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("显式身份合并服务测试")
class IdentityMergeServiceTest {

    private static final String GAME = "game_demo";
    private static final String PRIMARY = "idt_" + "a".repeat(28);
    private static final String SECONDARY = "idt_" + "b".repeat(28);

    @Mock private IdentityRepo identityRepo;
    @Mock private IdentityLinkRepo identityLinkRepo;
    @Mock private AuditLogService auditLogService;

    private IdentityMergeService service;

    @BeforeEach
    void setUp() {
        service = new IdentityMergeService(identityRepo, identityLinkRepo, auditLogService);
    }

    private static IdentityEntity identity(String id, String userId, String playerId,
                                           LocalDateTime firstSeen, LocalDateTime lastSeen,
                                           long eventCount, int sessionCount) {
        IdentityEntity e = new IdentityEntity();
        e.id = id;
        e.gameId = GAME;
        e.status = IdentityEntity.IdentityStatus.ACTIVE;
        e.primaryIdentityType = IdentityEntity.IdentityType.DEVICE;
        e.primaryId = id;
        e.userId = userId;
        e.playerId = playerId;
        e.deviceId = "dev_" + id.charAt(4);
        e.firstSeenAt = firstSeen;
        e.lastSeenAt = lastSeen;
        e.eventCount = eventCount;
        e.sessionCount = sessionCount;
        return e;
    }

    private static IdentityLinkEntity link(String identityId, String type, String value) {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.id = "ilk_" + type + "_" + value;
        l.identityId = identityId;
        l.linkedIdentityType = type;
        l.linkedId = value;
        l.linkType = IdentityLinkEntity.LinkType.ASSOCIATED;
        l.status = IdentityLinkEntity.LinkStatus.ACTIVE;
        l.usageCount = 3L;
        return l;
    }

    @Test
    @DisplayName("主链路：冲突 link 吊销 + 新 link 重挂 + 反查链路 + 标量/计数归并 + 墓碑化 + 审计")
    void mergeHappyPath() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 1, 2, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 1, 3, 10, 0);
        IdentityEntity primary = identity(PRIMARY, null, "p1", t2, t2, 10L, 2);
        IdentityEntity secondary = identity(SECONDARY, "u2", null, t1, t3, 5L, 1);
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(primary));
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(secondary));

        IdentityLinkEntity dupDevice = link(SECONDARY, "device_id", "dev_" + PRIMARY.charAt(4));
        IdentityLinkEntity extraPlayer = link(SECONDARY, "player_id", "p2");
        when(identityLinkRepo.findByIdentityId(SECONDARY)).thenReturn(List.of(dupDevice, extraPlayer));
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(PRIMARY, "device_id", dupDevice.linkedId))
                .thenReturn(Optional.of(link(PRIMARY, "device_id", dupDevice.linkedId)));
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(PRIMARY, "player_id", "p2"))
                .thenReturn(Optional.empty());
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(PRIMARY, "identity_id", SECONDARY))
                .thenReturn(Optional.empty());

        Map<String, Object> out = service.merge(GAME, PRIMARY, SECONDARY, "同设备多账号确认", "admin");

        // 摘要
        assertEquals(PRIMARY, out.get("identityId"));
        assertEquals(SECONDARY, out.get("mergedSecondaryId"));
        assertEquals(1, out.get("movedLinks"));
        assertEquals(1, out.get("dedupedLinks"));
        assertEquals("MERGED", out.get("status"));

        // 标量回填 + 计数归并 + 首末见
        assertEquals("u2", primary.userId);
        assertEquals("p1", primary.playerId);
        assertEquals(15L, primary.eventCount);
        assertEquals(3, primary.sessionCount);
        assertEquals(t1, primary.firstSeenAt);
        assertEquals(t3, primary.lastSeenAt);
        assertTrue(primary.mergedFrom.contains(SECONDARY));
        assertEquals("同设备多账号确认", primary.mergeReason);

        // secondary 墓碑化，标识列保留（erasure AnyStatus 反查仍可命中）
        assertEquals(IdentityEntity.IdentityStatus.MERGED, secondary.status);
        assertEquals(IdentityEntity.IdentityType.MERGED, secondary.primaryIdentityType);
        assertNotNull(secondary.deviceId);

        // 冲突 link 吊销，新 link 重挂为 MERGED/CONFIRMED/manual_merge
        assertEquals(IdentityLinkEntity.LinkStatus.REVOKED, dupDevice.status);
        assertNotNull(dupDevice.deletedAt);
        assertEquals(PRIMARY, extraPlayer.identityId);
        assertEquals(IdentityLinkEntity.LinkType.MERGED, extraPlayer.linkType);
        assertEquals(IdentityLinkEntity.VerificationStatus.CONFIRMED, extraPlayer.verificationStatus);
        assertEquals("manual_merge", extraPlayer.verificationMethod);

        // identity_id 反查链路落库
        ArgumentCaptor<IdentityLinkEntity> linkCaptor = ArgumentCaptor.forClass(IdentityLinkEntity.class);
        verify(identityLinkRepo, times(3)).save(linkCaptor.capture());
        IdentityLinkEntity tombstone = linkCaptor.getAllValues().stream()
                .filter(l -> "identity_id".equals(l.linkedIdentityType))
                .findFirst().orElseThrow();
        assertEquals(PRIMARY, tombstone.identityId);
        assertEquals(SECONDARY, tombstone.linkedId);
        assertEquals(IdentityLinkEntity.LinkType.MERGED, tombstone.linkType);

        // 12 参审计（资源=幸存身份，metadata 携带游戏/被并入方/link 计数/原因）
        ArgumentCaptor<Map<String, ?>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("identity"), eq(PRIMARY),
                anyString(), anyString(), eq(AuditLogEntity.AuditResult.SUCCESS), eq("admin"),
                isNull(), anyString(), isNull(), isNull(), metaCaptor.capture());
        Map<String, ?> meta = metaCaptor.getValue();
        assertEquals(GAME, meta.get("gameId"));
        assertEquals(SECONDARY, meta.get("secondaryIdentityId"));
        assertEquals(1, meta.get("movedLinks"));
        assertEquals(1, meta.get("dedupedLinks"));
        assertEquals("同设备多账号确认", meta.get("reason"));
    }

    @Test
    @DisplayName("护栏：同体合并 / 缺参 / 空原因 / 身份不存在 / 跨游戏 / 已软删 均拒绝")
    void guardRejections() {
        when(identityRepo.findById("idt_" + "c".repeat(28))).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.merge(GAME, PRIMARY, PRIMARY, "r", "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.merge(GAME, " ", SECONDARY, "r", "admin"));
        assertThrows(IllegalArgumentException.class, () -> service.merge(GAME, PRIMARY, SECONDARY, "  ", "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> service.merge(GAME, "idt_" + "c".repeat(28), SECONDARY, "r", "admin"));

        IdentityEntity otherGame = identity(SECONDARY, null, null, null, null, 0L, 0);
        otherGame.gameId = "game_other";
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(otherGame));
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(identity(PRIMARY, null, null, null, null, 0L, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.merge(GAME, PRIMARY, SECONDARY, "r", "admin"));

        IdentityEntity deleted = identity(SECONDARY, null, null, null, null, 0L, 0);
        deleted.deletedAt = LocalDateTime.now();
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class,
                () -> service.merge(GAME, PRIMARY, SECONDARY, "r", "admin"));

        verify(auditLogService, never()).log(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("护栏：secondary 或 primary 已 MERGED 拒绝（人工合并决定不被二次覆盖）")
    void alreadyMergedRejected() {
        IdentityEntity primary = identity(PRIMARY, null, null, null, null, 0L, 0);
        IdentityEntity mergedSecondary = identity(SECONDARY, null, null, null, null, 0L, 0);
        mergedSecondary.status = IdentityEntity.IdentityStatus.MERGED;
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(primary));
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(mergedSecondary));
        assertThrows(IllegalStateException.class,
                () -> service.merge(GAME, PRIMARY, SECONDARY, "r", "admin"));

        IdentityEntity mergedPrimary = identity(PRIMARY, null, null, null, null, 0L, 0);
        mergedPrimary.status = IdentityEntity.IdentityStatus.MERGED;
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(mergedPrimary));
        // primary 已 MERGED 时护栏先行抛出，findById(SECONDARY) 不会再被调用（沿用上半段桩即可）
        assertThrows(IllegalStateException.class,
                () -> service.merge(GAME, PRIMARY, SECONDARY, "r", "admin"));

        verify(identityLinkRepo, never()).findByIdentityId(anyString());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("反查链路复用：已有 identity_id tombstone（拆分后再合并）原位更新，不新建")
    void reusesExistingTombstoneLink() {
        IdentityEntity primary = identity(PRIMARY, null, null, null, null, 0L, 0);
        IdentityEntity secondary = identity(SECONDARY, null, null, null, null, 0L, 0);
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(primary));
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(secondary));
        when(identityLinkRepo.findByIdentityId(SECONDARY)).thenReturn(List.of());
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(PRIMARY, "identity_id", SECONDARY))
                .thenReturn(Optional.of(link(PRIMARY, "identity_id", SECONDARY)));

        Map<String, Object> out = service.merge(GAME, PRIMARY, SECONDARY, "r2", "admin");

        assertEquals(0, out.get("movedLinks"));
        ArgumentCaptor<IdentityLinkEntity> linkCaptor = ArgumentCaptor.forClass(IdentityLinkEntity.class);
        verify(identityLinkRepo, times(1)).save(linkCaptor.capture());
        IdentityLinkEntity saved = linkCaptor.getValue();
        assertEquals(PRIMARY, saved.identityId);
        assertEquals("identity_id", saved.linkedIdentityType);
        assertEquals(SECONDARY, saved.linkedId);
        assertNull(saved.deletedAt);
        assertEquals(IdentityLinkEntity.LinkStatus.ACTIVE, saved.status);
        assertEquals(IdentityEntity.IdentityStatus.MERGED, secondary.status);
    }

    @Test
    @DisplayName("mergedFrom 追加保留既有合并来源（多次合并不覆盖）")
    void mergedFromAppendsNotOverwrites() {
        IdentityEntity primary = identity(PRIMARY, null, null, null, null, 0L, 0);
        primary.mergedFrom = "[\"idt_" + "c".repeat(28) + "\"]";
        IdentityEntity secondary = identity(SECONDARY, null, null, null, null, 0L, 0);
        when(identityRepo.findById(PRIMARY)).thenReturn(Optional.of(primary));
        when(identityRepo.findById(SECONDARY)).thenReturn(Optional.of(secondary));
        when(identityLinkRepo.findByIdentityId(SECONDARY)).thenReturn(List.of());
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(PRIMARY, "identity_id", SECONDARY))
                .thenReturn(Optional.empty());

        Map<String, Object> out = service.merge(GAME, PRIMARY, SECONDARY, "r3", "admin");

        @SuppressWarnings("unchecked")
        List<String> mergedFrom = (List<String>) out.get("mergedFrom");
        assertEquals(2, mergedFrom.size());
        assertTrue(mergedFrom.contains("idt_" + "c".repeat(28)));
        assertTrue(mergedFrom.contains(SECONDARY));
    }
}
