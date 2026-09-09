package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ControlService（API Key 与存储档案管理）测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControlService 测试")
class ControlServiceTest {

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private ControlService service;

    private final GameEntity game = new GameEntity();
    private final GameEnvironmentEntity env = new GameEnvironmentEntity();

    @BeforeEach
    void setUp() {
        game.id = "g";
        game.name = "Demo";
        env.id = "prod";
        env.gameId = "g";
        lenient().when(gameRepo.findById("g")).thenReturn(Optional.of(game));
        lenient().when(envRepo.findById("prod")).thenReturn(Optional.of(env));
        lenient().when(keyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("创建 Key：校验游戏/环境存在，生成前缀密钥")
    void createKeyValidatesAndGenerates() {
        Models.ApiKeyResp resp = service.createKey("g", "prod", "ops-key");
        assertNotNull(resp.apiKey);
        assertEquals("ops-key", resp.name);

        when(gameRepo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.createKey("nope", "prod", "k"));
        when(envRepo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.createKey("g", "nope", "k"));
    }

    @Test
    @DisplayName("Key 查询：详情/列表/搜索/网关侧查询")
    void keyQueries() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "ak_test";
        key.gameId = "g";
        key.environmentId = "prod";
        key.name = "ops";
        when(keyRepo.findById("ak_test")).thenReturn(Optional.of(key));
        when(keyRepo.findAll()).thenReturn(List.of(key));
        lenient().when(keyRepo.searchApiKeysByScope(any(), any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        assertNotNull(service.getKey("ak_test"));
        assertEquals(1, service.listKeys().size());
        assertEquals(0, service.searchKeys("g", "prod", null, 0, 20).items.size());
        org.junit.jupiter.api.Assertions.assertNull(service.getKey("missing"));
        assertNotNull(service.getActiveKeyForGateway("ak_test"));
    }

    @Test
    @DisplayName("Key 删除与策略更新")
    void keyDeleteAndUpdate() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "ak_test";
        when(keyRepo.findById("ak_test")).thenReturn(Optional.of(key));
        when(keyRepo.existsById("ak_test")).thenReturn(true);
        lenient().when(keyRepo.findAllById(any())).thenReturn(List.of(key));

        assertEquals(true, service.deleteKey("ak_test"));
        assertEquals(1, service.deleteKeys(List.of("ak_test")));
        Models.KeyDetailResp req = new Models.KeyDetailResp();
        assertNotNull(service.updatePolicy("ak_test", req));
    }

    @Test
    @DisplayName("存储档案：列表/详情/创建/更新/删除")
    void storageProfileCrud() {
        StorageProfileEntity profile = new StorageProfileEntity();
        profile.id = "sp_1";
        when(storageProfileRepo.findAll()).thenReturn(List.of(profile));
        when(storageProfileRepo.findById("sp_1")).thenReturn(Optional.of(profile));
        when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        
        assertEquals(1, service.listStorageProfiles().size());
        assertNotNull(service.getStorageProfile("sp_1"));
        Models.CreateStorageProfileReq req = new Models.CreateStorageProfileReq();
        req.name = "default-profile";
        assertNotNull(service.createStorageProfile(req));
        assertNotNull(service.updateStorageProfile("sp_1", req));
        assertEquals(true, service.deleteStorageProfile("sp_1"));
    }
}
