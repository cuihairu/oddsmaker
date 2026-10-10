package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import io.oddsmaker.control.jpa.DimensionPullConfigRepo;
import io.oddsmaker.control.jpa.DimensionSyncStatusRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pull 配置 YAML 导入/导出编排（真 DimensionPullService + 真 YAML 层，mock 仓储）：
 * 按 (environment, sourceKey) 定位 create/update 分支、credential 缺省=保留既有、
 * 新建无 credential 拒绝、不存在环境拒绝、空导入拒绝、失败不返回成功结果（事务回滚是
 * @Transactional 边界契约，由 create/update 既有校验链保证不半套生效）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Pull 配置 YAML 导入/导出编排")
class DimensionPullYamlServiceTest {

    private static final String KEY = "items";

    @Mock
    private DimensionPullConfigRepo configRepo;
    @Mock
    private DimensionSyncStatusRepo statusRepo;
    @Mock
    private GameRepo gameRepo;
    @Mock
    private GameEnvironmentRepo envRepo;
    @Mock
    private ApiKeyRepo keyRepo;

    /** save 落盘轨迹：每次 save 的实体入序，供部分失败用例验序 */
    private final List<DimensionPullConfigEntity> saved = new ArrayList<>();

    private final DimensionCredentialCipher cipher = new DimensionCredentialCipher("yaml-svc-key");

    private DimensionPullYamlService yamlService;

    @BeforeEach
    void setUp() {
        DimensionPullService pullService = new DimensionPullService(configRepo, statusRepo, gameRepo, envRepo,
                keyRepo, cipher, new RestTemplate(), "http://localhost:8080");
        yamlService = new DimensionPullYamlService(pullService, configRepo);
        when(gameRepo.findById(anyString())).thenReturn(Optional.of(new io.oddsmaker.control.jpa.GameEntity()));
        when(envRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(List.of(env("prod")));
        when(configRepo.save(any())).thenAnswer(inv -> {
            DimensionPullConfigEntity e = inv.getArgument(0);
            saved.add(e);
            return e;
        });
    }

    private static GameEnvironmentEntity env(String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.name = name;
        e.gameId = "game_x";
        return e;
    }

    private DimensionPullConfigEntity existingConfig(String key, String env, String credentialPlaintext) {
        DimensionPullConfigEntity e = new DimensionPullConfigEntity();
        e.id = "dpc_" + key + "_" + env;
        e.gameId = "game_x";
        e.environment = env;
        e.sourceKey = key;
        e.dimType = "item";
        e.endpoint = "https://old.example.com/v1/" + key;
        e.credentialEncrypted = cipher.encrypt(credentialPlaintext);
        e.enabled = true;
        e.intervalSeconds = 300;
        e.pageLimit = 1000;
        return e;
    }

    private void stubExisting(String key, String env, DimensionPullConfigEntity e) {
        when(configRepo.findByGameIdAndEnvironmentAndSourceKey("game_x", env, key)).thenReturn(Optional.of(e));
        when(configRepo.findById(e.id)).thenReturn(Optional.of(e));
    }

    // ===== import =====

    @Test
    @DisplayName("导入：新键 create，字段按默认落库，credential 加密托管")
    void importCreates() {
        Map<String, Object> r = yamlService.importConfigs("game_x", """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/items
                    credential: token-first-1
                """);

        assertEquals(1, r.get("created"));
        assertEquals(0, r.get("updated"));
        assertEquals(1, r.get("total"));

        DimensionPullConfigEntity saved = this.saved.get(0);
        assertEquals("game_x", saved.gameId);
        assertEquals("prod", saved.environment);
        assertEquals("items", saved.sourceKey);
        assertEquals("item", saved.dimType, "dimType 缺省 item");
        assertEquals("https://api.game-x.com/v1/items", saved.endpoint);
        assertEquals(Integer.valueOf(300), saved.intervalSeconds, "intervalSeconds 缺省 300");
        assertEquals(Integer.valueOf(1000), saved.pageLimit, "pageLimit 缺省 1000");
        assertEquals(Boolean.TRUE, saved.enabled, "enabled 缺省 true");
        assertNotNull(saved.createdAt);
        assertEquals("token-first-1", cipher.decrypt(saved.credentialEncrypted), "凭证加密托管可解密回原文");
    }

    @Test
    @DisplayName("导入：同 (env, key) 已存在 → update，credential 缺省=保留既有密文")
    void importUpdatesKeepingCredential() {
        DimensionPullConfigEntity existing = existingConfig("items", "prod", "old-token");
        stubExisting("items", "prod", existing);

        Map<String, Object> r = yamlService.importConfigs("game_x", """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://new.example.com/v1/items
                    intervalSeconds: 60
                """);

        assertEquals(0, r.get("created"));
        assertEquals(1, r.get("updated"));

        DimensionPullConfigEntity after = this.saved.get(0);
        assertSame(existing, after, "更新走同一实体（非新建）");
        assertEquals("https://new.example.com/v1/items", after.endpoint);
        assertEquals(Integer.valueOf(60), after.intervalSeconds);
        assertEquals("old-token", cipher.decrypt(after.credentialEncrypted), "credential 缺省=保留既有");
        assertEquals(Integer.valueOf(1000), after.pageLimit, "未写字段保持原值");
    }

    @Test
    @DisplayName("导入：更新改 credential → 新凭证加密托管（既有凭证可轮换）")
    void importUpdatesCredential() {
        DimensionPullConfigEntity existing = existingConfig("items", "prod", "old-token");
        stubExisting("items", "prod", existing);

        yamlService.importConfigs("game_x", """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://new.example.com/v1/items
                    credential: token-rotated-2
                """);

        assertEquals("token-rotated-2", cipher.decrypt(saved.get(0).credentialEncrypted));
    }

    @Test
    @DisplayName("导入：新建档无 credential → 拒绝（不静默无凭证建档）")
    void importNewWithoutCredentialRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> yamlService.importConfigs("game_x", """
                        dimensions:
                          items:
                            environment: prod
                            endpoint: https://api.game-x.com/v1/items
                        """));
        assertTrue(e.getMessage().contains("credential"), e.getMessage());
        assertEquals(0, saved.size(), "异常上抛前不 save 任何档（事务边界保证整体回滚）");
        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("导入：环境不属于该游戏 → 拒绝并精确报环境名")
    void importUnknownEnvironmentRejected() {
        when(envRepo.findByGameIdAndNameAndDeletedAtIsNull("game_x", "staging")).thenReturn(List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> yamlService.importConfigs("game_x", """
                        dimensions:
                          skills:
                            environment: staging
                            endpoint: https://api.game-x.com/v1/skills
                            credential: t
                        """));
        assertTrue(e.getMessage().contains("staging"), e.getMessage());
    }

    @Test
    @DisplayName("导入：空 dimensions / 无 dimensions 根键 → 拒绝（空导入无副作用语义）")
    void importEmptyRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> yamlService.importConfigs("game_x", "dimensions: {}"));
        assertTrue(e.getMessage().contains("没有可导入"), e.getMessage());
        assertTrue(saved.isEmpty());
        verify(configRepo, never()).save(any());
    }

    @Test
    @DisplayName("导入：第二条非法 → 异常上抛（@Transactional 整体回滚，不半套生效）")
    void importPartialFailureRejects() {
        String yaml = """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/items
                    credential: token-first-1
                  skills:
                    environment: ghost
                    endpoint: https://api.game-x.com/v1/skills
                    credential: t
                """;
        when(envRepo.findByGameIdAndNameAndDeletedAtIsNull("game_x", "ghost")).thenReturn(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> yamlService.importConfigs("game_x", yaml));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        assertEquals(1, saved.size(), "第一条已 save：真实事务边界会回滚，mock 层仅验证不返回成功结果");
    }

    @Test
    @DisplayName("导入：不同 environment 同名 sourceKey → 两环境各一档（唯一键同口径）")
    void importSameKeyDifferentEnvironment() {
        Map<String, Object> r = yamlService.importConfigs("game_x", """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/items
                    credential: t1
                  levels:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/levels
                    credential: t2
                """);
        assertEquals(2, r.get("created"));
    }

    // ===== export =====

    @Test
    @DisplayName("导出：该游戏配置 → YAML 头 + 键 + 无凭证，游戏不存在拒绝")
    void exportConfigs() {
        DimensionPullConfigEntity a = existingConfig("items", "prod", "secret-a");
        a.endpoint = "https://api.game-x.com/v1/items";
        DimensionPullConfigEntity b = existingConfig("levels", "prod", "secret-b");
        b.endpoint = "https://api.game-x.com/v1/levels";
        when(configRepo.findByGameId("game_x")).thenReturn(List.of(a, b));

        String yaml = yamlService.export("game_x");

        assertTrue(yaml.startsWith(DimensionPullYaml.EXPORT_HEADER));
        assertTrue(yaml.contains("  items:"));
        assertTrue(yaml.contains("  levels:"));
        assertTrue(yaml.contains("https://api.game-x.com/v1/items"));
        assertTrue(yaml.contains("environment: prod"));
        assertTrue(!yaml.contains("secret-a") && !yaml.contains("secret-b"), "凭证永不导出");
        assertTrue(!yaml.contains("credential:"), "导出物不含 credential 字段");
    }

    @Test
    @DisplayName("导出：游戏不存在 → 拒绝")
    void exportUnknownGameRejected() {
        when(gameRepo.findById("ghost")).thenReturn(Optional.empty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> yamlService.export("ghost"));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        verify(configRepo, never()).findByGameId(anyString());
    }

    // ===== round trip =====

    @Test
    @DisplayName("往返：导出→解析非凭证字段全保真（credential 语义=导入保留既有/首建必填）")
    void exportParseRoundTrip() {
        DimensionPullConfigEntity a = existingConfig("items", "prod", "secret-a");
        a.intervalSeconds = 42;
        a.pageLimit = 7;
        a.enabled = false;
        when(configRepo.findByGameId("game_x")).thenReturn(List.of(a));

        List<DimensionPullService.PullConfigUpsert> parsed =
                DimensionPullYaml.parse(yamlService.export("game_x"));

        assertEquals(1, parsed.size());
        DimensionPullService.PullConfigUpsert up = parsed.get(0);
        assertEquals("items", up.sourceKey);
        assertEquals("prod", up.environment);
        assertEquals("https://old.example.com/v1/items", up.endpoint);
        assertEquals(42, up.intervalSeconds);
        assertEquals(7, up.pageLimit);
        assertEquals(Boolean.FALSE, up.enabled);
        assertEquals(null, up.credential);
    }

    @Test
    @DisplayName("往返：导出→导入（既有档）只改 endpoint 不动凭证")
    void exportImportRoundTripOnExisting() {
        DimensionPullConfigEntity a = existingConfig("items", "prod", "secret-a");
        a.endpoint = "https://old.example.com/v1/items";
        stubExisting("items", "prod", a);
        when(configRepo.findByGameId("game_x")).thenReturn(List.of(a));

        Map<String, Object> r = yamlService.importConfigs("game_x", yamlService.export("game_x"));

        assertEquals(0, r.get("created"));
        assertEquals(1, r.get("updated"));
        assertEquals("secret-a", cipher.decrypt(saved.get(0).credentialEncrypted));
        assertEquals("https://old.example.com/v1/items", saved.get(0).endpoint, "导出值原样写回");
        verify(configRepo).save(a);
    }

    @Test
    @DisplayName("ArgumentCaptor 佐证：create 路径实体的凭证密文与表字段一一对应")
    void saveCaptureShape() {
        yamlService.importConfigs("game_x", """
                dimensions:
                  items:
                    environment: prod
                    endpoint: https://api.game-x.com/v1/items
                    credential: captured-token
                """);
        ArgumentCaptor<DimensionPullConfigEntity> captor =
                ArgumentCaptor.forClass(DimensionPullConfigEntity.class);
        verify(configRepo).save(captor.capture());
        DimensionPullConfigEntity e = captor.getValue();
        assertEquals("captured-token", cipher.decrypt(e.credentialEncrypted));
    }
}
