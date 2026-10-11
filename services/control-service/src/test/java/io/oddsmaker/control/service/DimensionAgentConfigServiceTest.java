package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.DimensionAgentConfigEntity;
import io.oddsmaker.control.jpa.DimensionAgentConfigRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 属性面托管编排（真 cipher，mock 仓储）：建档规范化与密文托管、secrets 缺省=保留既有、
 * props 全量替换、密钥整体轮换、跨游戏/重名/未知键拒绝、resolve 注入与解密合并、停用档拒发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Agent 属性面托管编排")
class DimensionAgentConfigServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_PROPS = """
            gateway.endpoint=https://ingest.oddsmaker.local
            source.type=mysql
            source.jdbc.url=jdbc:mysql://replica.internal:3306/game
            agent.poll-seconds=300
            """;

    @Mock
    private DimensionAgentConfigRepo repo;
    @Mock
    private GameRepo gameRepo;
    @Mock
    private GameEnvironmentRepo envRepo;

    private final List<DimensionAgentConfigEntity> saved = new ArrayList<>();

    private final DimensionCredentialCipher cipher = new DimensionCredentialCipher("agent-cfg-svc-key");

    private DimensionAgentConfigService service;

    @BeforeEach
    void setUp() {
        service = new DimensionAgentConfigService(repo, gameRepo, envRepo, cipher);
        when(gameRepo.findById(anyString())).thenReturn(Optional.of(new GameEntity()));
        when(envRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(List.of(env("prod")));
        when(repo.save(any())).thenAnswer(inv -> {
            DimensionAgentConfigEntity e = inv.getArgument(0);
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

    private static DimensionAgentConfigService.AgentConfigUpsert upsert(String props, Map<String, String> secrets) {
        DimensionAgentConfigService.AgentConfigUpsert req = new DimensionAgentConfigService.AgentConfigUpsert();
        req.environment = "prod";
        req.agentKey = "items-mysql";
        req.props = props;
        req.secrets = secrets;
        return req;
    }

    private static Map<String, String> validSecrets() {
        return Map.of("gateway.api-key", "pk_game_x", "source.jdbc.password", "s3cret");
    }

    private DimensionAgentConfigEntity existing() {
        DimensionAgentConfigEntity e = new DimensionAgentConfigEntity();
        e.id = "dac_existing";
        e.gameId = "game_x";
        e.environment = "prod";
        e.agentKey = "items-mysql";
        e.enabled = true;
        e.props = "gateway.endpoint=https://old.example.local\nsource.type=mysql\n";
        e.secretsEncrypted = cipher.encrypt(
                "{\"gateway.api-key\":\"pk_old\",\"source.jdbc.password\":\"old-s3cret\"}");
        return e;
    }

    // ===== create =====

    @Test
    void createNormalizesPropsAndEncryptsSecrets() {
        Map<String, Object> view = service.create("game_x",
                upsert("source.type=mysql\ngateway.endpoint=https://ingest.oddsmaker.local\n", validSecrets()));

        DimensionAgentConfigEntity e = saved.get(0);
        assertTrue(e.id.startsWith("dac_"));
        assertEquals("game_x", e.gameId);
        assertEquals("prod", e.environment);
        assertEquals("items-mysql", e.agentKey);
        assertEquals(Boolean.TRUE, e.enabled);
        // props 规范化：TreeMap 序（gateway.endpoint 在前）
        assertTrue(e.props.startsWith("gateway.endpoint=https://ingest.oddsmaker.local\n"), e.props);
        assertTrue(e.props.endsWith("\n"));
        // 密文托管：密文不含明文、view 不回显密文也不回显值
        assertFalse(e.secretsEncrypted.contains("pk_game_x"));
        assertFalse(String.valueOf(view).contains("pk_game_x"));
        assertFalse(String.valueOf(view).contains("s3cret"));
        assertFalse(String.valueOf(view).contains(e.secretsEncrypted));
        assertEquals(List.of("gateway.api-key", "source.jdbc.password"), view.get("secretKeys"));
        assertEquals(Boolean.TRUE, view.get("enabled"));
    }

    @Test
    void createRequiresGatewayApiKeyInSecrets() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("game_x", upsert(VALID_PROPS, Map.of("source.jdbc.password", "s3cret"))));
        assertEquals("缺少必填配置: gateway.api-key", e.getMessage());
        assertTrue(saved.isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownEnvironment() {
        when(envRepo.findByGameIdAndNameAndDeletedAtIsNull("game_x", "staging")).thenReturn(List.of());
        DimensionAgentConfigService.AgentConfigUpsert req = upsert(VALID_PROPS, validSecrets());
        req.environment = "staging";
        assertEquals("Environment not found: staging",
                assertThrows(IllegalArgumentException.class, () -> service.create("game_x", req)).getMessage());
    }

    @Test
    void createRejectsDuplicateAgentKey() {
        when(repo.findByGameIdAndEnvironmentAndAgentKey("game_x", "prod", "items-mysql"))
                .thenReturn(Optional.of(existing()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create("game_x", upsert(VALID_PROPS, validSecrets())));
        assertEquals("同游戏同环境已存在同名 agentKey: items-mysql", e.getMessage());
    }

    @Test
    void createSoftDeletedGameRejected() {
        GameEntity deleted = new GameEntity();
        deleted.deletedAt = java.time.LocalDateTime.now();
        when(gameRepo.findById("game_x")).thenReturn(Optional.of(deleted));
        assertEquals("Game not found: game_x",
                assertThrows(IllegalArgumentException.class,
                        () -> service.create("game_x", upsert(VALID_PROPS, validSecrets()))).getMessage());
    }

    // ===== update =====

    @Test
    void updatePropsKeepsExistingSecrets() {
        DimensionAgentConfigEntity e = existing();
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        String newProps = "gateway.endpoint=https://new.example.local\nsource.type=mysql\nagent.poll-seconds=60\n";

        service.update("game_x", "dac_existing", upsert(newProps, null));

        assertEquals("dac_existing", saved.get(0).id);
        assertTrue(e.props.contains("https://new.example.local"));
        assertTrue(e.props.contains("agent.poll-seconds=60"));
        // secrets 缺省 = 保留既有密文（同一对象未动）
        assertEquals("{\"gateway.api-key\":\"pk_old\",\"source.jdbc.password\":\"old-s3cret\"}",
                cipher.decrypt(e.secretsEncrypted));
    }

    @Test
    void updateSecretsRotationReplacesCiphertext() throws Exception {
        DimensionAgentConfigEntity e = existing();
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        String oldCiphertext = e.secretsEncrypted;

        service.update("game_x", "dac_existing",
                upsert(null, Map.of("gateway.api-key", "pk_rotated", "source.jdbc.password", "new-s3cret")));

        assertNotEquals(oldCiphertext, e.secretsEncrypted);
        TreeMap<String, String> rotated = MAPPER.readValue(cipher.decrypt(e.secretsEncrypted),
                MAPPER.getTypeFactory().constructMapType(TreeMap.class, String.class, String.class));
        assertEquals("pk_rotated", rotated.get("gateway.api-key"));
        assertEquals("new-s3cret", rotated.get("source.jdbc.password"));
    }

    @Test
    void updateSecretsWithoutApiKeyRejectedAndKeepsOld() {
        DimensionAgentConfigEntity e = existing();
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        String oldCiphertext = e.secretsEncrypted;

        assertEquals("缺少必填配置: gateway.api-key",
                assertThrows(IllegalArgumentException.class,
                        () -> service.update("game_x", "dac_existing",
                                upsert(null, Map.of("source.jdbc.password", "x")))).getMessage());
        assertEquals(oldCiphertext, e.secretsEncrypted);
    }

    @Test
    void updateForeignGameRejected() {
        DimensionAgentConfigEntity e = existing();
        e.gameId = "game_other";
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        assertEquals("Agent config not found: dac_existing",
                assertThrows(IllegalArgumentException.class,
                        () -> service.update("game_x", "dac_existing",
                                upsert(VALID_PROPS, null))).getMessage());
    }

    @Test
    void updateUnknownPropKeyRejected() {
        DimensionAgentConfigEntity e = existing();
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        String oldProps = e.props;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.update("game_x", "dac_existing",
                        upsert("source.jdbc.pwd=oops\n" + VALID_PROPS, null))).getMessage()
                .contains("未知键 source.jdbc.pwd"));
        assertEquals(oldProps, e.props);
    }

    // ===== list / delete =====

    @Test
    void listReturnsViewsWithSecretKeyNamesOnly() {
        DimensionAgentConfigEntity e = existing();
        when(repo.findByGameId("game_x")).thenReturn(List.of(e));

        List<Map<String, Object>> views = service.list("game_x");

        assertEquals(1, views.size());
        Map<String, Object> view = views.get(0);
        assertEquals("dac_existing", view.get("id"));
        assertEquals(List.of("gateway.api-key", "source.jdbc.password"), view.get("secretKeys"));
        String rendered = String.valueOf(view);
        assertFalse(rendered.contains("pk_old"));
        assertFalse(rendered.contains("old-s3cret"));
    }

    @Test
    void deleteRemovesOwnedRow() {
        DimensionAgentConfigEntity e = existing();
        when(repo.findById("dac_existing")).thenReturn(Optional.of(e));
        service.delete("game_x", "dac_existing");
        verify(repo).delete(e);
    }

    // ===== resolve =====

    @Test
    void resolveMergesInjectionPropsAndDecryptedSecrets() throws Exception {
        DimensionAgentConfigEntity e = existing();
        e.props = "gateway.endpoint=https://ingest.oddsmaker.local\nsource.type=mysql\n";
        when(repo.findByGameIdAndEnvironmentAndAgentKey("game_x", "prod", "items-mysql"))
                .thenReturn(Optional.of(e));

        String text = service.resolve("game_x", "prod", "items-mysql");

        Properties loaded = new Properties();
        loaded.load(new StringReader(text));
        assertEquals("game_x", loaded.getProperty("game.id"));
        assertEquals("prod", loaded.getProperty("game.environment"));
        assertEquals("items-mysql", loaded.getProperty("status.source-key"));
        assertEquals("https://ingest.oddsmaker.local", loaded.getProperty("gateway.endpoint"));
        assertEquals("pk_old", loaded.getProperty("gateway.api-key"));
        assertEquals("old-s3cret", loaded.getProperty("source.jdbc.password"));
    }

    @Test
    void resolveDisabledAndMissingRejected() {
        DimensionAgentConfigEntity e = existing();
        e.enabled = false;
        when(repo.findByGameIdAndEnvironmentAndAgentKey("game_x", "prod", "items-mysql"))
                .thenReturn(Optional.of(e));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.resolve("game_x", "prod", "items-mysql")).getMessage().contains("已停用"));

        when(repo.findByGameIdAndEnvironmentAndAgentKey("game_x", "prod", "items-mysql"))
                .thenReturn(Optional.empty());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.resolve("game_x", "prod", "items-mysql")).getMessage()
                .contains("Agent config not found"));
    }
}
