package io.oddsmaker.control.service;

import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：ControlService——createKey SERVER/ADMIN/CLIENT 角色分支与 getActiveKeyForGateway 环境/吊销/删除/归属四分支、updatePolicy 全字段与 deleteKeys 空参/searchKeys 透传、StorageProfile 创建冲突与隔离策略解析/更新/删除占用分支/列表排序。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2ControlServiceTest {

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private io.oddsmaker.control.jpa.TrackingPlanRepo trackingPlanRepo;

    @Mock
    private io.oddsmaker.control.jpa.EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private ControlService controlService;

    private static GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        return g;
    }

    private static GameEnvironmentEntity environment(String gameId, String id, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.name = name;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        return e;
    }

    @Test
    @DisplayName("密钥：SERVER/ADMIN/CLIENT 角色分支与网关视图的环境校验分支")
    void controlServiceKeyRolesAndGatewayBranches() {
        GameEntity demo = game("g");
        GameEnvironmentEntity prod = environment("g", "prod", "prod");
        prod.enableSampling = false;
        prod.sampleRate = 0.5;
        demo.serverEventsEnabled = true;  // B3：SERVER 档位发放前置（发放校验细节见 ControlServiceTest）
        lenient().when(gameRepo.findById("g")).thenReturn(Optional.of(demo));
        lenient().when(gameEnvironmentRepo.findById("prod")).thenReturn(Optional.of(prod));
        lenient().when(apiKeyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        controlService.createKey("g", "prod", "server-key", "server");
        controlService.createKey("g", "prod", "admin-key", "admin");
        controlService.createKey("g", "prod", "client-key", null);

        ArgumentCaptor<ApiKeyEntity> keys = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepo, times(3)).save(keys.capture());
        ApiKeyEntity server = keys.getAllValues().get(0);
        assertEquals(ApiKeyEntity.ApiKeyType.SERVER, server.keyType);
        assertTrue(server.requireHmac);
        assertTrue(server.canWrite);
        ApiKeyEntity admin = keys.getAllValues().get(1);
        assertEquals(ApiKeyEntity.ApiKeyType.ADMIN, admin.keyType);
        assertFalse(admin.canWrite);
        assertTrue(admin.canRead);
        assertTrue(admin.canExport);
        assertTrue(admin.requireHmac);
        ApiKeyEntity client = keys.getAllValues().get(2);
        assertEquals(ApiKeyEntity.ApiKeyType.CLIENT, client.keyType);
        assertFalse(client.requireHmac);
        assertEquals("server", controlService.createKey("g", "prod", "s2", "SERVER").keyRole);

        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g", "prod", "k", "bogus"));

        GameEnvironmentEntity foreignEnv = environment("other", "qa", "qa");
        lenient().when(gameEnvironmentRepo.findById("qa")).thenReturn(Optional.of(foreignEnv));
        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g", "qa", "k", null));

        // getActiveKeyForGateway：成功映射环境策略
        ApiKeyEntity active = new ApiKeyEntity();
        active.apiKey = "pk_ok";
        active.secret = "sk_1";
        active.gameId = "g";
        active.environmentId = "prod";
        active.rpm = 100;
        active.ipRpm = 50;
        active.canWrite = true;
        active.requireHmac = true;
        active.propsAllowlist = "a,b";
        lenient().when(apiKeyRepo.findById("pk_ok")).thenReturn(Optional.of(active));
        Models.InternalApiKeyResp resp = controlService.getActiveKeyForGateway("pk_ok");
        assertEquals("sk_1", resp.secret);
        assertEquals("prod", resp.environment);
        assertEquals("active", resp.envStatus);
        assertEquals(Boolean.FALSE, resp.envEnableSampling);
        assertEquals(0.5, resp.envSampleRate);
        assertEquals(Boolean.TRUE, resp.canWrite);
        assertEquals(Boolean.TRUE, resp.requireHmac);
        assertEquals(Integer.valueOf(100), resp.rpm);
        assertEquals(List.of("a", "b"), resp.propsAllowlist);

        // 已吊销密钥 → null
        ApiKeyEntity revoked = new ApiKeyEntity();
        revoked.apiKey = "pk_revoked";
        revoked.gameId = "g";
        revoked.environmentId = "prod";
        revoked.status = ApiKeyEntity.ApiKeyStatus.REVOKED;
        lenient().when(apiKeyRepo.findById("pk_revoked")).thenReturn(Optional.of(revoked));
        assertNull(controlService.getActiveKeyForGateway("pk_revoked"));

        // 环境已删除 → null
        GameEnvironmentEntity deletedEnv = environment("g", "prod2", "prod2");
        deletedEnv.deletedAt = LocalDateTime.now();
        ApiKeyEntity keyEnvDel = new ApiKeyEntity();
        keyEnvDel.apiKey = "pk_envdel";
        keyEnvDel.gameId = "g";
        keyEnvDel.environmentId = "prod2";
        lenient().when(apiKeyRepo.findById("pk_envdel")).thenReturn(Optional.of(keyEnvDel));
        lenient().when(gameEnvironmentRepo.findById("prod2")).thenReturn(Optional.of(deletedEnv));
        assertNull(controlService.getActiveKeyForGateway("pk_envdel"));

        // 环境归属其他游戏 → null
        GameEnvironmentEntity mismatchEnv = environment("other", "prod3", "prod3");
        ApiKeyEntity keyMismatch = new ApiKeyEntity();
        keyMismatch.apiKey = "pk_mismatch";
        keyMismatch.gameId = "g";
        keyMismatch.environmentId = "prod3";
        lenient().when(apiKeyRepo.findById("pk_mismatch")).thenReturn(Optional.of(keyMismatch));
        lenient().when(gameEnvironmentRepo.findById("prod3")).thenReturn(Optional.of(mismatchEnv));
        assertNull(controlService.getActiveKeyForGateway("pk_mismatch"));
    }

    @Test
    @DisplayName("密钥：updatePolicy 全字段覆盖、deleteKeys 空参零删除、searchKeys 透传")
    void controlServiceUpdatePolicyAndDeleteKeys() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "pk_p";
        key.gameId = "g";
        key.environmentId = "prod";
        key.name = "policy-key";
        key.rpm = 600;
        key.ipRpm = 300;
        lenient().when(apiKeyRepo.findById("pk_p")).thenReturn(Optional.of(key));
        lenient().when(apiKeyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameEnvironmentRepo.findById("prod"))
            .thenReturn(Optional.of(environment("g", "prod", "prod")));

        Models.KeyDetailResp req = new Models.KeyDetailResp();
        req.rpm = 120;
        req.ipRpm = 60;
        req.propsAllowlist = List.of("a", " b ");
        req.piiEmail = "mask";
        req.piiPhone = "drop";
        req.piiIp = "coarse";
        req.denyKeys = List.of("secret_key");
        req.maskKeys = List.of("user_name");
        Models.KeyDetailResp updated = controlService.updatePolicy("pk_p", req);
        assertEquals(Integer.valueOf(120), updated.rpm);
        assertEquals(Integer.valueOf(60), updated.ipRpm);
        assertEquals(List.of("a", "b"), updated.propsAllowlist);
        assertEquals("mask", updated.piiEmail);
        assertEquals("drop", updated.piiPhone);
        assertEquals("coarse", updated.piiIp);
        assertEquals(List.of("secret_key"), updated.denyKeys);
        assertEquals(List.of("user_name"), updated.maskKeys);

        Models.KeyDetailResp partial = new Models.KeyDetailResp();
        partial.rpm = 999;
        Models.KeyDetailResp patched = controlService.updatePolicy("pk_p", partial);
        assertEquals(Integer.valueOf(999), patched.rpm);
        assertEquals(Integer.valueOf(60), patched.ipRpm);

        lenient().when(apiKeyRepo.findById("pk_missing")).thenReturn(Optional.empty());
        assertNull(controlService.updatePolicy("pk_missing", new Models.KeyDetailResp()));

        assertEquals(0L, controlService.deleteKeys(null));
        assertEquals(0L, controlService.deleteKeys(List.of()));
        verify(apiKeyRepo, never()).deleteAllById(any());

        lenient().when(apiKeyRepo.existsById("pk_missing")).thenReturn(false);
        assertFalse(controlService.deleteKey("pk_missing"));

        lenient().when(apiKeyRepo.searchApiKeysByScope(eq("g"), eq("prod"), eq("ops"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(key)));
        ControlService.Paged<Models.KeyDetailResp> paged = controlService.searchKeys("g", "prod", "ops", 0, 5);
        assertEquals(1L, paged.total);
        assertEquals(1, paged.items.size());
        assertEquals("pk_p", paged.items.get(0).apiKey);
        verify(apiKeyRepo).searchApiKeysByScope(eq("g"), eq("prod"), eq("ops"), any(Pageable.class));
    }

    @Test
    @DisplayName("存储档案：创建冲突/隔离策略解析、更新与删除占用分支、列表过滤排序")
    void controlServiceStorageProfileBranches() {
        StorageProfileEntity beta = new StorageProfileEntity();
        beta.id = "sp_1";
        beta.name = "Beta";
        StorageProfileEntity deleted = new StorageProfileEntity();
        deleted.id = "sp_del";
        deleted.name = "Alpha";
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findAll()).thenReturn(List.of(deleted, beta));
        lenient().when(storageProfileRepo.findById("sp_1")).thenReturn(Optional.of(beta));
        lenient().when(storageProfileRepo.findById("sp_del")).thenReturn(Optional.of(deleted));
        lenient().when(storageProfileRepo.save(any(StorageProfileEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertNull(controlService.getStorageProfile("sp_del"));

        lenient().when(storageProfileRepo.existsById("sp_1")).thenReturn(true);
        Models.CreateStorageProfileReq dupId = new Models.CreateStorageProfileReq();
        dupId.id = "sp_1";
        dupId.name = "Whatever";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(dupId));

        lenient().when(storageProfileRepo.existsById("beta-x")).thenReturn(false);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("Beta")).thenReturn(true);
        Models.CreateStorageProfileReq dupName = new Models.CreateStorageProfileReq();
        dupName.id = "beta-x";
        dupName.name = "Beta";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(dupName));

        Models.CreateStorageProfileReq noName = new Models.CreateStorageProfileReq();
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(noName));

        lenient().when(storageProfileRepo.existsById("sp_new")).thenReturn(false);
        lenient().when(storageProfileRepo.existsById("my-cluster-2")).thenReturn(false);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("New Profile")).thenReturn(false);
        Models.CreateStorageProfileReq explicit = new Models.CreateStorageProfileReq();
        explicit.id = "sp_new";
        explicit.name = "New Profile";
        explicit.isolationStrategy = "prod_isolated";
        explicit.active = null;
        Models.StorageProfileResp created = controlService.createStorageProfile(explicit);
        assertEquals("New Profile", created.displayName);
        assertEquals("PROD_ISOLATED", created.isolationStrategy);
        assertEquals(Boolean.TRUE, created.active);

        Models.CreateStorageProfileReq slugReq = new Models.CreateStorageProfileReq();
        slugReq.name = "My Cluster 2";
        Models.StorageProfileResp slugged = controlService.createStorageProfile(slugReq);
        assertEquals("my-cluster-2", slugged.id);
        assertEquals("SHARED", slugged.isolationStrategy);

        lenient().when(storageProfileRepo.findById("sp_x")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp_x", new Models.CreateStorageProfileReq()));

        StorageProfileEntity old = new StorageProfileEntity();
        old.id = "sp_old";
        old.name = "Old";
        lenient().when(storageProfileRepo.findById("sp_old")).thenReturn(Optional.of(old));
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("Taken")).thenReturn(true);
        Models.CreateStorageProfileReq renameToTaken = new Models.CreateStorageProfileReq();
        renameToTaken.name = "Taken";
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp_old", renameToTaken));

        Models.CreateStorageProfileReq refresh = new Models.CreateStorageProfileReq();
        refresh.name = "Fresh";
        refresh.kafkaCluster = "kafka-1";
        Models.StorageProfileResp updated = controlService.updateStorageProfile("sp_old", refresh);
        assertEquals("Fresh", updated.name);
        assertEquals("kafka-1", updated.kafkaCluster);

        assertFalse(controlService.deleteStorageProfile("sp_x"));

        StorageProfileEntity busy = new StorageProfileEntity();
        busy.id = "sp_busy";
        busy.name = "Busy";
        lenient().when(storageProfileRepo.findById("sp_busy")).thenReturn(Optional.of(busy));
        lenient().when(gameEnvironmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp_busy"))
            .thenReturn(List.of(new GameEnvironmentEntity()));
        assertThrows(IllegalStateException.class, () -> controlService.deleteStorageProfile("sp_busy"));

        StorageProfileEntity free = new StorageProfileEntity();
        free.id = "sp_free";
        free.name = "Free";
        lenient().when(storageProfileRepo.findById("sp_free")).thenReturn(Optional.of(free));
        lenient().when(gameEnvironmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp_free"))
            .thenReturn(List.of());
        assertTrue(controlService.deleteStorageProfile("sp_free"));
        assertNotNull(free.deletedAt);
        assertEquals(Boolean.FALSE, free.active);
    }

}
