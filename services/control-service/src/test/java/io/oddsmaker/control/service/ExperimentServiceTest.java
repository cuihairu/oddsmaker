package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.jpa.SegmentRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExperimentService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentService 单元测试")
class ExperimentServiceTest {

    @Mock
    private ExperimentRepo experimentRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Mock
    private SegmentRepo segmentRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ExperimentService experimentService;

    /** 旧式状态字面量 → 枚举（B8 枚举化后实体字段为 ExperimentStatus） */
    private static ExperimentEntity.ExperimentStatus status(String legacy) {
        return switch (legacy) {
            case "running", "live" -> ExperimentEntity.ExperimentStatus.LIVE;
            case "paused" -> ExperimentEntity.ExperimentStatus.PAUSED;
            case "ended" -> ExperimentEntity.ExperimentStatus.ENDED;
            default -> ExperimentEntity.ExperimentStatus.DRAFT;
        };
    }

    private static ExperimentEntity experiment(String status, String configJson) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_demo";
        e.status = status(status);
        e.salt = "exp_salt";
        e.configJson = configJson;
        return e;
    }

    private static final String CONFIG = "{\"variants\":[" +
        "{\"name\":\"control\",\"weight\":5000}," +
        "{\"name\":\"treatment\",\"weight\":5000}]}";

    @Test
    @DisplayName("服务加载测试")
    void serviceLoads() {
    }

    @Test
    @DisplayName("分流：running 实验按确定性哈希分配变体")
    void assignRunningExperiment() {
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("running", CONFIG)));

        String first = experimentService.assign("exp_1", "user_42");
        assertNotNull(first);
        assertTrue(first.equals("control") || first.equals("treatment"));
        // 确定性：重复分流结果一致
        assertEquals(first, experimentService.assign("exp_1", "user_42"));
    }

    @Test
    @DisplayName("分流：draft/paused 实验返回 null（不分流）")
    void assignNonRunningReturnsNull() {
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment("paused", CONFIG)));
        assertNull(experimentService.assign("exp_1", "user_42"));
    }

    @Test
    @DisplayName("分流：control_variant 兜底在无有效变体时生效")
    void assignFallsBackToControlVariant() {
        when(experimentRepo.findById("exp_1"))
            .thenReturn(Optional.of(experiment("running", "{\"control_variant\":\"legacy_control\"}")));
        assertEquals("legacy_control", experimentService.assign("exp_1", "user_42"));
    }

    @Test
    @DisplayName("分流：缺 subjectId 抛参数异常")
    void assignRequiresSubject() {
        assertThrows(IllegalArgumentException.class, () -> experimentService.assign("exp_1", " "));
    }

    @Test
    @DisplayName("分流：实验不存在抛参数异常")
    void assignUnknownExperimentThrows() {
        when(experimentRepo.findById(anyString())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> experimentService.assign("exp_x", "u1"));
    }

    @Test
    @DisplayName("校验：createExperiment 的 gameId 空白/游戏不存在拒绝；status 标准化下传")
    void validationBranches() {
        // requireGame：gameId 空白 / 游戏不存在（createExperiment 入口）
        ExperimentDTO blank = new ExperimentDTO();
        blank.gameId = " ";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(blank));
        when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        ExperimentDTO missing = new ExperimentDTO();
        missing.gameId = "game_x";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(missing));

        // listExperiments：status 非空白走标准化后下传 repo（旧值 running 归一 LIVE）
        when(experimentRepo.search(any(), any(), any(), any()))
            .thenReturn(Page.empty());
        experimentService.listExperiments("game_demo", null, null, " RUNNING ", 0, 10);
        verify(experimentRepo)
            .search(eq("game_demo"), any(), eq(ExperimentEntity.ExperimentStatus.LIVE), any());
    }

    // ===== B8 形式化字段 =====

    @Test
    @DisplayName("创建：audience 引用本游戏 segment 落列；他游戏 segment 拒绝")
    void createAudienceSegment() {
        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_prod";
        env.name = "prod";
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_demo", "prod"))
            .thenReturn(List.of(env));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SegmentEntity own = new SegmentEntity();
        own.id = "seg_1";
        own.gameId = "game_demo";
        when(segmentRepo.findById("seg_1")).thenReturn(Optional.of(own));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_demo";
        dto.environment = "prod";
        dto.name = "exp-audience";
        dto.audienceSegmentId = "seg_1";
        dto.config = objectMapper.createObjectNode();

        ExperimentDTO out = experimentService.createExperiment(dto);
        assertEquals("seg_1", out.audienceSegmentId);
        verify(segmentRepo).findById("seg_1");

        // 他游戏 segment 拒绝
        SegmentEntity other = new SegmentEntity();
        other.id = "seg_2";
        other.gameId = "game_other";
        when(segmentRepo.findById("seg_2")).thenReturn(Optional.of(other));
        ExperimentDTO bad = new ExperimentDTO();
        bad.gameId = "game_demo";
        bad.environment = "prod";
        bad.name = "exp-bad";
        bad.audienceSegmentId = "seg_2";
        bad.config = objectMapper.createObjectNode();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> experimentService.createExperiment(bad));
        assertTrue(ex.getMessage().contains("does not belong to game"));
        // 场景一（合法创建）已 save 一次；他游戏 segment 拒绝路径不得追加 save
        verify(experimentRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("创建：guardrails/decision/variants/allocation 落列并往返")
    void createFormalizedFieldsRoundTrip() throws Exception {
        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_prod";
        env.name = "prod";
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_demo", "prod"))
            .thenReturn(List.of(env));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_demo";
        dto.environment = "prod";
        dto.name = "exp-formal";
        dto.guardrails = objectMapper.readTree("{\"minSampleSize\":1000}");
        dto.decision = objectMapper.readTree("{\"status\":\"PENDING\"}");
        dto.allocationInfo = objectMapper.readTree("{\"strategy\":\"weighted\"}");
        dto.variants = objectMapper.readTree("[{\"name\":\"a\",\"weight\":1},{\"name\":\"b\",\"weight\":1}]");
        dto.config = objectMapper.createObjectNode();

        ExperimentDTO out = experimentService.createExperiment(dto);
        assertEquals(1000, out.guardrails.path("minSampleSize").asInt());
        assertEquals("PENDING", out.decision.path("status").asText());
        assertEquals("weighted", out.allocationInfo.path("strategy").asText());
        assertEquals(2, out.variants.size());
    }

    @Test
    @DisplayName("创建：旧 configJson 内 variants 提列落 variants_json（向前兼容）")
    void createSyncsVariantsFromLegacyConfig() throws Exception {
        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_prod";
        env.name = "prod";
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_demo", "prod"))
            .thenReturn(List.of(env));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_demo";
        dto.environment = "prod";
        dto.name = "exp-legacy";
        dto.config = objectMapper.readTree(CONFIG);

        ExperimentDTO out = experimentService.createExperiment(dto);
        assertEquals(2, out.variants.size());
        assertEquals("control", out.variants.get(0).path("name").asText());
    }

    @Test
    @DisplayName("读取：variants_json 缺失时回退旧 configJson 的 variants")
    void readFallsBackToLegacyConfigVariants() {
        ExperimentEntity e = experiment("draft", CONFIG);
        e.variantsJson = null;
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(e));
        when(environmentRepo.findById(any())).thenReturn(Optional.empty());

        ExperimentDTO out = experimentService.getExperiment("exp_1").orElseThrow();
        assertEquals(2, out.variants.size());
        assertEquals("treatment", out.variants.get(1).path("name").asText());
        assertNull(out.guardrails);
        assertNull(out.decision);
    }

    @Test
    @DisplayName("更新：config 写入同步 variants 列；status 旧值 running 归一 LIVE")
    void updateSyncsVariantsAndNormalizesStatus() throws Exception {
        ExperimentEntity e = experiment("draft", CONFIG);
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(e));
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO dto = new ExperimentDTO();
        dto.config = objectMapper.readTree("{\"variants\":[{\"name\":\"x\",\"weight\":1},{\"name\":\"y\",\"weight\":2}]}");
        dto.status = "running";

        ExperimentDTO out = experimentService.updateExperiment("exp_1", dto);
        assertEquals("LIVE", out.status);
        assertEquals(2, out.variants.size());
        assertEquals("y", out.variants.get(1).path("name").asText());
    }

    @Test
    @DisplayName("发布动作：DRAFT → LIVE 且校验 variants 齐备")
    void publishTransitionsDraftToLive() {
        ExperimentEntity e = experiment("draft", CONFIG);
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(e));
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO out = experimentService.publishExperiment("exp_1");
        assertEquals("LIVE", out.status);

        // 缺 variants 的 DRAFT 发布被拒
        ExperimentEntity noVariants = experiment("draft", "{}");
        when(experimentRepo.findById("exp_2")).thenReturn(Optional.of(noVariants));
        assertThrows(IllegalArgumentException.class, () -> experimentService.publishExperiment("exp_2"));
    }

    @Test
    @DisplayName("状态别名：旧 API 值 running 存储为枚举名 LIVE")
    void statusAliasRunningMapsToLive() throws Exception {
        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_prod";
        env.name = "prod";
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_demo", "prod"))
            .thenReturn(List.of(env));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        when(experimentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_demo";
        dto.environment = "prod";
        dto.name = "exp-alias";
        dto.status = "running";
        dto.config = objectMapper.readTree(CONFIG);

        ArgumentCaptor<ExperimentEntity> captor = ArgumentCaptor.forClass(ExperimentEntity.class);
        experimentService.createExperiment(dto);
        verify(experimentRepo).save(captor.capture());
        assertEquals(ExperimentEntity.ExperimentStatus.LIVE, captor.getValue().status);
    }
}
