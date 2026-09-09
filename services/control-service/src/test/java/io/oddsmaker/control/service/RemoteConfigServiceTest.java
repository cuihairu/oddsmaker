package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RemoteConfigEntity;
import io.oddsmaker.control.jpa.RemoteConfigRepo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Remote Config 测试：CRUD 校验、环境覆盖解析、聚合版本、304 增量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Remote Config 测试")
class RemoteConfigServiceTest {

    @Mock
    private RemoteConfigRepo repo;

    @Mock
    private GameRepo gameRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RemoteConfigService service;

    private final GameEntity game = new GameEntity();

    @BeforeEach
    void setUp() {
        game.id = "game_demo";
        game.name = "Demo";
    }

    private static RemoteConfigEntity config(String env, String key, String value, long version,
                                              RemoteConfigEntity.Status status) {
        RemoteConfigEntity c = new RemoteConfigEntity();
        c.id = "rc_" + key + env;
        c.gameId = "game_demo";
        c.environmentId = env;
        c.configKey = key;
        c.configValue = value;
        c.version = version;
        c.status = status;
        return c;
    }

    @Test
    @DisplayName("创建：校验 key 格式 / JSON 合法 / 唯一性，赋 ID 与版本")
    void createValidatesAndSaves() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(repo.findByGameIdAndEnvironmentIdAndConfigKeyAndDeletedAtIsNull("game_demo", "", "shop.discount"))
            .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RemoteConfigEntity c = new RemoteConfigEntity();
        c.gameId = "game_demo";
        c.configKey = "shop.discount";
        c.configValue = "{\"rate\":0.8}";
        RemoteConfigEntity saved = service.create(c, "ops1");

        assertTrue(saved.id.startsWith("rc_"));
        assertEquals(1L, saved.version);
        assertEquals("", saved.environmentId);

        // key 非法
        RemoteConfigEntity badKey = new RemoteConfigEntity();
        badKey.gameId = "game_demo";
        badKey.configKey = "9bad-key!";
        badKey.configValue = "1";
        assertThrows(IllegalArgumentException.class, () -> service.create(badKey, "ops1"));

        // 值非法 JSON
        RemoteConfigEntity badJson = new RemoteConfigEntity();
        badJson.gameId = "game_demo";
        badJson.configKey = "shop.rate";
        badJson.configValue = "{not-json";
        assertThrows(IllegalArgumentException.class, () -> service.create(badJson, "ops1"));
    }

    @Test
    @DisplayName("创建：同 (game, env, key) 重复拒绝")
    void createRejectsDuplicate() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(repo.findByGameIdAndEnvironmentIdAndConfigKeyAndDeletedAtIsNull("game_demo", "", "k"))
            .thenReturn(Optional.of(config("", "k", "1", 1, RemoteConfigEntity.Status.ACTIVE)));

        RemoteConfigEntity dup = new RemoteConfigEntity();
        dup.gameId = "game_demo";
        dup.configKey = "k";
        dup.configValue = "2";
        assertThrows(IllegalStateException.class, () -> service.create(dup, "ops1"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("更新：改值/改状态各自自增版本")
    void updateBumpsVersion() {
        RemoteConfigEntity existing = config("", "k", "1", 3, RemoteConfigEntity.Status.ACTIVE);
        when(repo.findById("rc_1")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RemoteConfigEntity updated = service.update("rc_1", "42", null, "desc", "ops1");
        assertEquals(4L, updated.version);
        assertEquals("42", updated.configValue);

        RemoteConfigEntity disabled = service.update("rc_1", null, RemoteConfigEntity.Status.INACTIVE, null, "ops1");
        assertEquals(5L, disabled.version);
        assertEquals(RemoteConfigEntity.Status.INACTIVE, disabled.status);
    }

    @Test
    @DisplayName("解析：环境特定覆盖全环境，INACTIVE 剔除，类型还原，版本累加")
    void resolveEnvironmentOverride() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(repo.findEffective("game_demo", "prod")).thenReturn(List.of(
            config("", "shop.discount", "{\"rate\":0.8}", 2, RemoteConfigEntity.Status.ACTIVE),
            config("", "feature.new_ui", "true", 1, RemoteConfigEntity.Status.ACTIVE),
            config("", "maintenance", "false", 4, RemoteConfigEntity.Status.INACTIVE),
            config("prod", "shop.discount", "{\"rate\":0.5}", 7, RemoteConfigEntity.Status.ACTIVE)));

        Map<String, Object> out = service.resolve("game_demo", "prod");

        assertEquals("game_demo", out.get("gameId"));
        assertEquals("prod", out.get("environment"));
        // 覆盖后 0.5；INACTIVE 剔除；布尔还原
        Map<?, ?> configs = (Map<?, ?>) out.get("configs");
        assertEquals(Map.of("rate", 0.5), configs.get("shop.discount"));
        assertEquals(Boolean.TRUE, configs.get("feature.new_ui"));
        assertNull(configs.get("maintenance"));
        // version = 2 + 1 + 7
        assertEquals(10L, out.get("version"));
    }

    @Test
    @DisplayName("解析：无环境参数取全环境配置")
    void resolveWithoutEnvironment() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(repo.findEffective("game_demo", null)).thenReturn(List.of(
            config("", "level.cap", "120", 5, RemoteConfigEntity.Status.ACTIVE)));

        Map<String, Object> out = service.resolve("game_demo", null);
        Map<?, ?> configs = (Map<?, ?>) out.get("configs");
        assertEquals(120L, configs.get("level.cap"));  // 整数还原为 long
        assertEquals(5L, out.get("version"));
    }

    @Test
    @DisplayName("列表：按环境过滤")
    void listFiltersByEnvironment() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(repo.findByGameId("game_demo")).thenReturn(List.of(
            config("", "a", "1", 1, RemoteConfigEntity.Status.ACTIVE),
            config("prod", "b", "2", 1, RemoteConfigEntity.Status.ACTIVE)));

        assertEquals(2, service.list("game_demo", null).size());
        assertEquals(1, service.list("game_demo", "prod").size());
        assertEquals("b", service.list("game_demo", "prod").get(0).configKey);
    }

    @Test
    @DisplayName("删除：软删置 deletedAt")
    void deleteSoftDeletes() {
        RemoteConfigEntity existing = config("", "k", "1", 1, RemoteConfigEntity.Status.ACTIVE);
        when(repo.findById("rc_1")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete("rc_1");

        assertNotNull(existing.deletedAt);
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("get 不存在抛异常；update 非法 JSON 拒绝")
    void getAndUpdateValidation() {
        when(repo.findById("rc_nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.get("rc_nope"));

        when(repo.findById("rc_1")).thenReturn(Optional.of(config("", "k", "1", 1, RemoteConfigEntity.Status.ACTIVE)));
        assertThrows(IllegalArgumentException.class,
            () -> service.update("rc_1", "{bad", null, null, "ops1"));
    }

    @Test
    @DisplayName("游戏必须存在")
    void requiresGame() {
        when(gameRepo.findById("game_nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.list("game_nope", null));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("game_nope", null));
    }
}
