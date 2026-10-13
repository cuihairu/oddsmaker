package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API Key 自动轮换。
 *
 * auto_rotate / rotation_days 配置列 V0.2.0 已随表但无任何执行端（死字段），
 * V0.9.10 起由 {@code rotateDueApiKeys} 定时扫描到期 key 换发签名密钥：
 * <ul>
 *   <li>到期判定：last_rotated_at（空回落 created_at）+ rotation_days 与 now 比较；</li>
 *   <li>rotation_days 为空或 ≤0 回落默认 90 天；</li>
 *   <li>密钥标识 pk_ 保持不动，只换 sk_（下游凭据缓存切换窗口见文档）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("API Key 自动轮换")
class ApiKeyRotationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private ControlService service;

    @BeforeEach
    void setUp() {
        lenient().when(keyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of());
    }

    /** 一个已启用自动轮换的 ACTIVE key；createdAt 仅用于 lastRotatedAt 为空的回落场景。 */
    private ApiKeyEntity key(Integer rotationDays, LocalDateTime lastRotated, LocalDateTime created) {
        ApiKeyEntity e = new ApiKeyEntity();
        e.apiKey = "pk_1";
        e.secret = "sk_old";
        e.name = "ops-key";
        e.gameId = "g";
        e.environmentId = "prod";
        e.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        e.autoRotate = true;
        e.rotationDays = rotationDays;
        e.lastRotatedAt = lastRotated;
        e.createdAt = created;
        return e;
    }

    @Test
    @DisplayName("到期 key 换发签名密钥：secret 变更、pk_ 不动、lastRotatedAt 记 now、写审计")
    void rotatesOverdueKey() {
        ApiKeyEntity k = key(90, NOW.minusDays(91), null);
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(k));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(1, rotated);
        assertNotEquals("sk_old", k.secret);
        assertTrue(k.secret.startsWith("sk_"));
        assertEquals("pk_1", k.apiKey);
        assertEquals(NOW, k.lastRotatedAt);
        assertEquals(ApiKeyEntity.ApiKeyStatus.ACTIVE, k.status);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).logUpdate(eq("api_key"), eq("pk_1"), eq("ops-key"),
            eq("api"), eq("api"), isNull(), captor.capture());
        assertEquals("auto_rotate", captor.getValue().get("action"));
        assertEquals("g", captor.getValue().get("gameId"));
    }

    @Test
    @DisplayName("未到期 key 不轮换：不写密钥、不写审计")
    void skipsKeyBeforeDueDate() {
        ApiKeyEntity k = key(90, NOW.minusDays(1), null);
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(k));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(0, rotated);
        assertEquals("sk_old", k.secret);
        verify(keyRepo, never()).save(any(ApiKeyEntity.class));
        verify(auditLog, never()).logUpdate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("lastRotatedAt 为空回落 created_at：超过轮换期则轮换")
    void fallsBackToCreatedAtWhenLastRotatedNull() {
        ApiKeyEntity k = key(90, null, NOW.minusDays(100));
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(k));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(1, rotated);
        assertNotEquals("sk_old", k.secret);
        assertEquals(NOW, k.lastRotatedAt);
    }

    @Test
    @DisplayName("lastRotatedAt 为空且 created_at 未超期：不轮换")
    void notDueWhenFallingBackToRecentCreatedAt() {
        ApiKeyEntity k = key(90, null, NOW.minusDays(10));
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(k));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(0, rotated);
        assertEquals("sk_old", k.secret);
    }

    @Test
    @DisplayName("rotationDays 为空或 ≤0 回落默认 90 天")
    void fallsBackToDefaultRotationDaysWhenConfigMissing() {
        ApiKeyEntity dueNull = key(null, null, NOW.minusDays(100));
        ApiKeyEntity dueZero = key(0, null, NOW.minusDays(100));
        ApiKeyEntity notDue = key(0, null, NOW.minusDays(10));
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(dueNull, dueZero, notDue));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(2, rotated);
        assertNotEquals("sk_old", dueNull.secret);
        assertNotEquals("sk_old", dueZero.secret);
        assertEquals("sk_old", notDue.secret);
    }

    @Test
    @DisplayName("批量扫描只轮换到期 key：返回轮换数")
    void batchRotatesOnlyDueKeys() {
        ApiKeyEntity due = key(30, NOW.minusDays(31), null);
        ApiKeyEntity notDue = key(30, NOW.minusDays(1), null);
        when(keyRepo.findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE))
            .thenReturn(List.of(due, notDue));

        int rotated = service.rotateDueApiKeys(NOW);

        assertEquals(1, rotated);
        assertNotEquals("sk_old", due.secret);
        assertEquals("sk_old", notDue.secret);
    }

    @Test
    @DisplayName("扫描面收敛：只查 ACTIVE 且 autoRotate=TRUE 的 key")
    void scansOnlyActiveAutoRotateKeys() {
        service.rotateDueApiKeys(NOW);

        verify(keyRepo).findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE);
    }

    @Test
    @DisplayName("无参重载委托 LocalDateTime.now()：查询与到期判定照常")
    void noArgOverloadDelegatesToNow() {
        service.rotateDueApiKeys();

        verify(keyRepo).findByStatusAndAutoRotate(ApiKeyEntity.ApiKeyStatus.ACTIVE, Boolean.TRUE);
    }

    @Test
    @DisplayName("策略更新：autoRotate/rotationDays 落库并在详情返回，lastRotatedAt 可回读")
    void policyUpdatePersistsRotationConfig() {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = "pk_1";
        k.name = "ops-key";
        k.gameId = "g";
        k.environmentId = "prod";
        k.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        k.lastRotatedAt = LocalDateTime.of(2026, 10, 1, 8, 0);
        when(keyRepo.findById("pk_1")).thenReturn(Optional.of(k));

        Models.KeyDetailResp req = new Models.KeyDetailResp();
        req.autoRotate = true;
        req.rotationDays = 30;
        Models.KeyDetailResp out = service.updatePolicy("pk_1", req);

        assertNotNull(out);
        assertEquals(Boolean.TRUE, k.autoRotate);
        assertEquals(Integer.valueOf(30), k.rotationDays);
        assertEquals(Boolean.TRUE, out.autoRotate);
        assertEquals(Integer.valueOf(30), out.rotationDays);
        assertEquals("2026-10-01T08:00", out.lastRotatedAt);
    }

    @Test
    @DisplayName("策略更新请求未带轮换字段时保持原值（null 不覆盖）")
    void policyUpdateKeepsRotationConfigWhenNull() {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = "pk_1";
        k.name = "ops-key";
        k.gameId = "g";
        k.environmentId = "prod";
        k.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        k.autoRotate = true;
        k.rotationDays = 60;
        when(keyRepo.findById("pk_1")).thenReturn(Optional.of(k));

        Models.KeyDetailResp req = new Models.KeyDetailResp();
        Models.KeyDetailResp out = service.updatePolicy("pk_1", req);

        assertEquals(Boolean.TRUE, k.autoRotate);
        assertEquals(Integer.valueOf(60), k.rotationDays);
        assertEquals(Boolean.TRUE, out.autoRotate);
        assertEquals(Integer.valueOf(60), out.rotationDays);
    }
}
