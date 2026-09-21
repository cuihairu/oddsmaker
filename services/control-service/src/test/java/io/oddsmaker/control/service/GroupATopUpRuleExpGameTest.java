package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·片A补充：RiskRuleService / ExperimentService / GameService 的未走分支侧。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("片A分支补充：风控规则/实验/游戏")
class GroupATopUpRuleExpGameTest {

    // ===== RiskRuleService =====

    @Mock RiskRuleRepo ruleRepo;
    @Mock GameRepo gameRepo;
    @Mock AuditLogService auditLog;

    private RiskRuleService ruleService() {
        return new RiskRuleService(ruleRepo, gameRepo, auditLog);
    }

    @Test
    @DisplayName("list：全部过滤参数为空白时各条件走 false 侧（不进谓词）")
    @SuppressWarnings("unchecked")
    void riskRuleListBlankFilters() {
        RiskRuleService service = ruleService();
        when(ruleRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.list("g1", "  ", "  ", "  ", "  ", 0, 10);

        ArgumentCaptor<Specification<RiskRuleEntity>> captor =
            ArgumentCaptor.forClass((Class) Specification.class);
        verify(ruleRepo).findAll(captor.capture(), any(Pageable.class));
        // 真实执行 spec：deep stubs 的 root/cb 全链路可走
        Root<RiskRuleEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        Predicate result = captor.getValue().toPredicate(root, mock(jakarta.persistence.criteria.CriteriaQuery.class), cb);
        assertNotNull(result);   // 仅剩 deletedAt isNull 一个谓词，cb.and 仍返回合并结果
    }

    @Test
    @DisplayName("create：name 为 null 与空白均拒绝")
    void riskRuleCreateMissingName() {
        RiskRuleService service = ruleService();
        GameEntity game = new GameEntity();
        game.id = "g1";
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game));

        RiskRuleEntity noName = new RiskRuleEntity();
        noName.gameId = "g1";
        assertThrows(IllegalArgumentException.class, () -> service.create(noName, "ops"));

        RiskRuleEntity blankName = new RiskRuleEntity();
        blankName.gameId = "g1";
        blankName.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> service.create(blankName, "ops"));
    }

    @Test
    @DisplayName("update：全字段 null patch 不改任何字段；name 空白不改名")
    void riskRuleUpdateNullPatchAndBlankName() {
        RiskRuleService service = ruleService();
        RiskRuleEntity existing = new RiskRuleEntity();
        existing.id = "rr1";
        existing.gameId = "g1";
        existing.name = "orig";
        existing.riskScore = 50;
        existing.triggerThreshold = 5;
        when(ruleRepo.findById("rr1")).thenReturn(Optional.of(existing));
        when(ruleRepo.save(any(RiskRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // riskScore/triggerThreshold 有初始化器（50/1），全 null patch 须显式置 null
        RiskRuleEntity nullPatch = new RiskRuleEntity();
        nullPatch.riskScore = null;
        nullPatch.triggerThreshold = null;
        RiskRuleEntity saved = service.update("rr1", nullPatch, "ops");
        assertEquals("orig", saved.name);      // req.name == null → 跳过全部 if（false 侧齐走）
        assertEquals(Integer.valueOf(50), saved.riskScore);
        assertEquals(Integer.valueOf(5), saved.triggerThreshold);

        RiskRuleEntity blankName = new RiskRuleEntity();
        blankName.name = "   ";
        assertEquals("orig", service.update("rr1", blankName, "ops").name);   // isBlank → 不改名
    }

    // ===== ExperimentService =====

    @Mock ExperimentRepo experimentRepo;
    @Mock GameEnvironmentRepo environmentRepo;
    private final ObjectMapper experimentMapper = new ObjectMapper();
    @Mock AuditLogService expAuditLog;
    private ExperimentService experimentService;   // setUp 构造器注入（objectMapper 用真实实例）

    // ===== GameService =====
    // GameService 字段注入按字段名匹配：gameRepo 用上文 RiskRule 段声明的同一 @Mock

    @Mock GameEnvironmentRepo gameEnvironmentRepo;
    @Mock ApiKeyRepo apiKeyRepo;
    @Mock StorageProfileRepo storageProfileRepo;
    @Mock AuditLogService gameAuditLog;
    @InjectMocks GameService gameService;

    private static GameEntity game(GameEntity.GameStatus status) {
        GameEntity entity = new GameEntity();
        entity.id = "g1";
        entity.name = "ok";
        entity.status = status;
        entity.platforms = Set.of(GameEntity.GamePlatform.WEB);
        entity.currentVersion = "1.0.0";
        return entity;
    }

    @BeforeEach
    void setUp() {
        // ExperimentService 依赖（与 GameService 分开的构造器）
        experimentService = new ExperimentService(experimentRepo, gameRepo, environmentRepo, experimentMapper, expAuditLog);
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);
        lenient().when(experimentRepo.search(any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        lenient().when(environmentRepo.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString()))
            .thenReturn(List.of());

        // GameService 依赖
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull(anyString())).thenReturn(0L);
        lenient().when(apiKeyRepo.countByGameIdAndStatus(anyString(), any())).thenReturn(0L);
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(true);
    }

    // ===== ExperimentService 用例 =====

    private ExperimentDTO dto(String name) {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environmentId = "env1";
        dto.name = name;
        return dto;
    }

    private GameEnvironmentEntity env(String id, boolean deleted) {
        GameEnvironmentEntity entity = new GameEnvironmentEntity();
        entity.id = id;
        entity.gameId = "g1";
        entity.name = "prod";
        entity.deletedAt = deleted ? LocalDateTime.now() : null;
        return entity;
    }

    @Test
    @DisplayName("list：environmentName null/空白跳过名称解析；status 空白不过滤")
    void experimentListEnvNameSides() {
        // environmentName == null（L68 第二条件 false）
        experimentService.listExperiments("g1", null, null, "  ", 0, 10);
        // environmentName 空白（!isBlank false）
        experimentService.listExperiments("g1", null, "   ", null, 0, 10);
        // environmentId 已解析（L68 第一条件 false）
        experimentService.listExperiments("g1", "env1", null, null, 0, 10);
        verify(experimentRepo, org.mockito.Mockito.times(3)).search(any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("create：id 空白生成；salt 空白回退 id；name null/空白拒绝")
    void experimentCreateIdSaltNameSides() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game(GameEntity.GameStatus.DEVELOPMENT)));
        when(environmentRepo.findById("env1")).thenReturn(Optional.of(env("env1", false)));

        ExperimentDTO blankId = dto("n");
        blankId.id = "   ";
        ExperimentDTO created = experimentService.createExperiment(blankId);
        assertTrue(created.id.startsWith("exp_"));       // isBlank → 生成

        ExperimentDTO blankSalt = dto("n");
        blankSalt.salt = "   ";
        ExperimentDTO saltFallback = experimentService.createExperiment(blankSalt);
        assertEquals(saltFallback.id, saltFallback.salt);   // salt 空白 → 回退 id

        ExperimentDTO blankName = dto("   ");
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(blankName));
    }

    @Test
    @DisplayName("create：gameId null 拒绝；已删游戏拒绝；环境缺失/已删拒绝")
    void experimentCreateGameEnvSides() {
        // 独立变量名：req 重赋值后进 assertThrows lambda 会破坏 effectively final
        ExperimentDTO req1 = dto("n");
        req1.gameId = null;
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(req1));

        GameEntity deleted = game(GameEntity.GameStatus.DEVELOPMENT);
        deleted.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("g_del")).thenReturn(Optional.of(deleted));
        ExperimentDTO req2 = dto("n");
        req2.gameId = "g_del";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(req2));

        when(gameRepo.findById("g1")).thenReturn(Optional.of(game(GameEntity.GameStatus.DEVELOPMENT)));
        ExperimentDTO req3 = dto("n");
        req3.environmentId = null;   // environment 名称也缺 → requireEnvironment 抛
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(req3));

        when(environmentRepo.findById("env_del")).thenReturn(Optional.of(env("env_del", true)));
        ExperimentDTO req4 = dto("n");
        req4.environmentId = "env_del";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(req4));
    }

    @Test
    @DisplayName("create：config 非对象拒绝；weight 缺失/非整数/非正数拒绝")
    void experimentCreateConfigSides() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game(GameEntity.GameStatus.DEVELOPMENT)));
        when(environmentRepo.findById("env1")).thenReturn(Optional.of(env("env1", false)));

        ExperimentDTO arrayCfg = dto("n");
        arrayCfg.config = experimentMapper.createArrayNode();
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(arrayCfg));

        for (int variant = 0; variant < 3; variant++) {
            ArrayNode variants = experimentMapper.createArrayNode();
            ObjectNode a = variants.addObject();
            a.put("name", "a");
            a.put("weight", 1);
            ObjectNode b = variants.addObject();
            b.put("name", "b");
            if (variant == 1) {
                b.put("weight", "2");   // 字符串 → 非整数
            } else if (variant == 2) {
                b.put("weight", 0);     // 非正数
            }                            // variant 0：weight 缺失
            ObjectNode config = experimentMapper.createObjectNode();
            config.set("variants", variants);
            ExperimentDTO bad = dto("n" + variant);
            bad.config = config;
            assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(bad));
        }
    }

    @Test
    @DisplayName("update：salt 空白回退 id；status=running 校验可运行配置；status 空白保持原值")
    void experimentUpdateSides() {
        ExperimentEntity entity = new ExperimentEntity();
        entity.id = "exp1";
        entity.gameId = "g1";
        entity.environmentId = "env1";
        entity.name = "n";
        entity.status = "draft";
        entity.salt = "s";
        entity.configJson = "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"name\":\"b\",\"weight\":1}]}";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(entity));

        ExperimentDTO blankSalt = dto("n");
        blankSalt.salt = "   ";
        assertEquals("exp1", experimentService.updateExperiment("exp1", blankSalt).salt);

        ExperimentDTO running = dto("n");
        running.status = "running";
        assertEquals("running", experimentService.updateExperiment("exp1", running).status);

        ExperimentDTO blankStatus = dto("n");
        blankStatus.status = "   ";
        // 上一段已把同一 entity 的 status 改为 running，空白 status 不覆盖 → 保持 running
        assertEquals("running", experimentService.updateExperiment("exp1", blankStatus).status);
    }

    @Test
    @DisplayName("assign：subjectId null/空白拒绝；空变体回落 controlVariant 或 null")
    void experimentAssignSides() {
        assertThrows(IllegalArgumentException.class, () -> experimentService.assign("exp1", null));
        assertThrows(IllegalArgumentException.class, () -> experimentService.assign("exp1", "   "));

        ExperimentEntity withControl = new ExperimentEntity();
        withControl.id = "exp1";
        withControl.salt = "s";
        withControl.status = "running";
        withControl.configJson = "{\"control_variant\":\"cv\"}";   // 无 variants → assign 返回 null → 回落
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(withControl));
        assertEquals("cv", experimentService.assign("exp1", "subj"));

        ExperimentEntity noControl = new ExperimentEntity();
        noControl.id = "exp2";
        noControl.salt = "s";
        noControl.status = "running";
        noControl.configJson = "{}";
        when(experimentRepo.findById("exp2")).thenReturn(Optional.of(noControl));
        assertNull(experimentService.assign("exp2", "subj"));
    }

    @Test
    @DisplayName("publish：configJson null/空白均视为空对象 → running 校验拒绝")
    void experimentPublishBlankConfig() {
        for (String configJson : new String[] {null, "   "}) {
            ExperimentEntity entity = new ExperimentEntity();
            entity.id = "exp1";
            entity.salt = "s";
            entity.status = "draft";
            entity.configJson = configJson;
            when(experimentRepo.findById("exp1")).thenReturn(Optional.of(entity));
            assertThrows(IllegalArgumentException.class, () -> experimentService.publishExperiment("exp1"));
        }
    }

    @Test
    @DisplayName("getExperiment：环境已删时 environment 名称回落 null")
    void experimentToDtoDeletedEnvironment() {
        ExperimentEntity entity = new ExperimentEntity();
        entity.id = "exp1";
        entity.gameId = "g1";
        entity.environmentId = "env_del";
        entity.name = "n";
        entity.status = "draft";
        entity.salt = "s";
        entity.configJson = "{}";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(entity));
        when(environmentRepo.findById("env_del")).thenReturn(Optional.of(env("env_del", true)));

        ExperimentDTO result = experimentService.getExperiment("exp1").orElseThrow();
        assertNull(result.environment);
    }

    // ===== GameService 用例 =====

    @Test
    @DisplayName("createGame：id 空白自动生成；非法时区拒绝；blank 时区放行")
    void gameCreateIdAndTimezoneSides() {
        GameDTO blankId = new GameDTO();
        blankId.name = "n";
        blankId.id = "   ";
        blankId.defaultTimezone = "   ";   // blank → validateTimezone 直接返回
        when(gameRepo.existsById(anyString())).thenReturn(false);
        GameDTO created = gameService.createGame(blankId);
        assertTrue(created.id.startsWith("game_"));

        GameDTO badTz = new GameDTO();
        badTz.name = "n";
        badTz.defaultTimezone = "Mars/Olympus";
        assertThrows(IllegalArgumentException.class, () -> gameService.createGame(badTz));
    }

    @Test
    @DisplayName("已删游戏：update/delete/publish/unpublish/listEnvironments 全部拒绝")
    void deletedGameBranches() {
        GameEntity deleted = game(GameEntity.GameStatus.DEVELOPMENT);
        deleted.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("g_del")).thenReturn(Optional.of(deleted));

        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g_del", new GameDTO()));
        assertThrows(IllegalArgumentException.class, () -> gameService.deleteGame("g_del"));
        assertThrows(IllegalArgumentException.class, () -> gameService.publishGame("g_del"));
        assertThrows(IllegalArgumentException.class, () -> gameService.unpublishGame("g_del"));
        assertThrows(IllegalArgumentException.class, () -> gameService.listEnvironments("g_del"));
    }

    @Test
    @DisplayName("updateGame：status 与现值相同跳过状态机校验；合法转换全矩阵通过")
    void gameStatusTransitions() {
        // 逐个起始状态重设 stub
        for (GameEntity.GameStatus from : List.of(
                GameEntity.GameStatus.TESTING, GameEntity.GameStatus.LIVE, GameEntity.GameStatus.PUBLISHED,
                GameEntity.GameStatus.MAINTENANCE, GameEntity.GameStatus.DISCONTINUED)) {
            GameEntity entity = game(from);
            when(gameRepo.findById("g1")).thenReturn(Optional.of(entity));

            if (from == GameEntity.GameStatus.TESTING) {
                // 相同 status → 跳过 validateStatusChange（TESTING→TESTING 不在允许表，不抛即证明跳过）
                GameDTO same = new GameDTO();
                same.status = GameEntity.GameStatus.TESTING;
                assertEquals(GameEntity.GameStatus.TESTING, gameService.updateGame("g1", same).status);
                // TESTING → DEVELOPMENT 合法
                GameDTO toDev = new GameDTO();
                toDev.status = GameEntity.GameStatus.DEVELOPMENT;
                assertEquals(GameEntity.GameStatus.DEVELOPMENT, gameService.updateGame("g1", toDev).status);
            }
            if (from == GameEntity.GameStatus.LIVE) {
                GameDTO toMaint = new GameDTO();
                toMaint.status = GameEntity.GameStatus.MAINTENANCE;
                assertEquals(GameEntity.GameStatus.MAINTENANCE, gameService.updateGame("g1", toMaint).status);
                GameDTO toDev = new GameDTO();
                toDev.status = GameEntity.GameStatus.DEVELOPMENT;   // LIVE → DEVELOPMENT 非法
                assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g1", toDev));
            }
            if (from == GameEntity.GameStatus.PUBLISHED) {
                for (GameEntity.GameStatus to : List.of(
                        GameEntity.GameStatus.MAINTENANCE, GameEntity.GameStatus.DISCONTINUED)) {
                    // updateGame 就地改 entity.status，每个转换用独立 entity 保证起点不漂移
                    when(gameRepo.findById("g1")).thenReturn(Optional.of(game(from)));
                    GameDTO req = new GameDTO();
                    req.status = to;
                    assertEquals(to, gameService.updateGame("g1", req).status);
                }
                when(gameRepo.findById("g1")).thenReturn(Optional.of(game(from)));
                GameDTO toLive = new GameDTO();
                toLive.status = GameEntity.GameStatus.LIVE;         // PUBLISHED → LIVE 非法
                assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g1", toLive));
            }
            if (from == GameEntity.GameStatus.MAINTENANCE) {
                for (GameEntity.GameStatus to : List.of(
                        GameEntity.GameStatus.LIVE, GameEntity.GameStatus.DISCONTINUED)) {
                    when(gameRepo.findById("g1")).thenReturn(Optional.of(game(from)));
                    GameDTO req = new GameDTO();
                    req.status = to;
                    assertEquals(to, gameService.updateGame("g1", req).status);
                }
            }
            if (from == GameEntity.GameStatus.DISCONTINUED) {
                GameDTO toLive = new GameDTO();
                toLive.status = GameEntity.GameStatus.LIVE;         // 停服后不可变更
                assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g1", toLive));
            }
        }
    }


    @Test
    @DisplayName("publishGame：releaseDate 已有则不覆盖；name/platforms/version 缺失拒绝")
    void gamePublishBranches() {
        LocalDateTime fixed = LocalDateTime.now().minusDays(3);
        GameEntity ready = game(GameEntity.GameStatus.DEVELOPMENT);
        ready.releaseDate = fixed;
        when(gameRepo.findById("g1")).thenReturn(Optional.of(ready));
        assertEquals(fixed, gameService.publishGame("g1").releaseDate);   // 非 null → 不覆盖

        GameEntity noName = game(GameEntity.GameStatus.DEVELOPMENT);
        noName.name = null;
        when(gameRepo.findById("g2")).thenReturn(Optional.of(noName));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("g2"));

        GameEntity noPlatforms = game(GameEntity.GameStatus.DEVELOPMENT);
        noPlatforms.platforms = Set.of();
        when(gameRepo.findById("g3")).thenReturn(Optional.of(noPlatforms));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("g3"));

        GameEntity noVersion = game(GameEntity.GameStatus.DEVELOPMENT);
        noVersion.currentVersion = "   ";
        when(gameRepo.findById("g4")).thenReturn(Optional.of(noVersion));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("g4"));
    }
}
