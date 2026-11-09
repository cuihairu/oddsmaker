package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * ML 模型与实验平台 Service 深度单元测试（LAY-005 拆分）：实验创建默认值与全量校验分支、更新约束、发布配置校验、暂停、列表分页与 subjectId 分流。与 MLModelServiceTest / ExperimentServiceTest / TrackingPlanServiceTest / PermissionServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实验 Service 深度测试（创建/校验/发布/分流）")
class ExperimentServiceDeepTest {

    // ==================== ExperimentService 依赖 ====================

    @Mock
    private ExperimentRepo experimentRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ExperimentService experimentService;

    private static final String EXP_CONFIG =
        "{\"variants\":[{\"name\":\"control\",\"weight\":5000},{\"name\":\"treatment\",\"weight\":5000}]}";

    private static GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "game_1";
        g.name = "Demo Game";
        return g;
    }

    private static GameEnvironmentEntity env(String id) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = "game_1";
        e.name = "prod";
        return e;
    }

    private static ExperimentEntity experiment(String status, String configJson) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_1";
        e.environmentId = "env_prod";
        e.name = "Old Name";
        e.status = "running".equals(status) ? ExperimentEntity.ExperimentStatus.LIVE : ExperimentEntity.ExperimentStatus.valueOf(status.toUpperCase());
        e.salt = "salt_1";
        e.configJson = configJson;
        e.createdAt = LocalDateTime.now();
        e.updatedAt = LocalDateTime.now();
        return e;
    }

    private ExperimentDTO baseExperimentDto() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Validation";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);
        return dto;
    }

    @Test
    @DisplayName("createExperiment - 默认生成 id/salt，状态为 draft")
    void createExperiment_success_defaults() throws Exception {
        GameEnvironmentEntity prod = env("env_prod");
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Checkout Test";
        dto.config = objectMapper.readTree(EXP_CONFIG);
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(prod));

        ExperimentDTO created = experimentService.createExperiment(dto);

        assertThat(created.id).startsWith("exp_");
        assertThat(created.status).isEqualTo("DRAFT");
        assertThat(created.environment).isEqualTo("prod");
        assertThat(created.environmentId).isEqualTo("env_prod");
        assertThat(created.salt).isEqualTo(created.id);
        assertThat(created.config.has("variants")).isTrue();
    }

    @Test
    @DisplayName("createExperiment - 显式 id 重复抛参数异常")
    void createExperiment_duplicateIdThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = "exp_dup";
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Dup";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById("exp_dup")).thenReturn(true);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createExperiment - 游戏不存在抛参数异常")
    void createExperiment_gameNotFound() {
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_x";

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("createExperiment - 环境不存在抛参数异常")
    void createExperiment_environmentNotFound() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of());
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Environment not found");
    }

    @Test
    @DisplayName("createExperiment - running 状态缺少 variants 抛参数异常")
    void createExperiment_runningWithoutVariantsThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";
        dto.status = "LIVE";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("createExperiment - 非法状态抛参数异常")
    void createExperiment_unsupportedStatusThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";
        dto.status = "archived";   // B8 起 live 为合法枚举别名，改用真正不支持的状态钉拒绝路径
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported experiment status");
    }

    @Test
    @DisplayName("createExperiment - 空名称抛参数异常")
    void createExperiment_blankNameThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "   ";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("validateConfig - variants 少于两个抛参数异常")
    void createExperiment_variantsNeedTwo() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "LIVE";
        dto.config = objectMapper.readTree("{\"variants\":[{\"name\":\"control\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two variants");
    }

    @Test
    @DisplayName("validateConfig - 变体名重复抛参数异常")
    void createExperiment_duplicateVariantNamesThrows() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "LIVE";
        dto.config = objectMapper.readTree(
            "{\"variants\":[{\"name\":\"control\",\"weight\":1},{\"name\":\"control\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be unique");
    }

    @Test
    @DisplayName("validateConfig - 变体权重非正整数抛参数异常")
    void createExperiment_invalidWeightThrows() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "LIVE";
        dto.config = objectMapper.readTree(
            "{\"variants\":[{\"name\":\"control\",\"weight\":0},{\"name\":\"treatment\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive integer");
    }

    @Test
    @DisplayName("validateConfig - targeting 非对象抛参数异常")
    void createExperiment_targetingMustBeObject() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.config = objectMapper.readTree("{\"targeting\":\"everyone\"}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targeting must be a JSON object");
    }

    @Test
    @DisplayName("updateExperiment - 重命名并发布为 running")
    void updateExperiment_success_renamesAndPublishes() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO dto = new ExperimentDTO();
        dto.name = "New Name";
        dto.status = "LIVE";

        ExperimentDTO updated = experimentService.updateExperiment("exp_1", dto);

        assertThat(updated.status).isEqualTo("LIVE");
        assertThat(updated.name).isEqualTo("New Name");
        assertThat(entity.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateExperiment - 实验不存在抛参数异常")
    void updateExperiment_notFoundThrows() {
        lenient().when(experimentRepo.findById("exp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_x", new ExperimentDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Experiment not found");
    }

    @Test
    @DisplayName("updateExperiment - 更换游戏抛参数异常")
    void updateExperiment_gameChangeThrows() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_2";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("game cannot be changed");
    }

    @Test
    @DisplayName("updateExperiment - 更换环境抛参数异常")
    void updateExperiment_environmentChangeThrows() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(environmentRepo.findById("env_other")).thenReturn(Optional.of(env("env_other")));

        ExperimentDTO dto = new ExperimentDTO();
        dto.environmentId = "env_other";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("environment cannot be changed");
    }

    @Test
    @DisplayName("updateExperiment - 切换 running 但缺 variants 抛参数异常")
    void updateExperiment_runningWithoutVariantsThrows() {
        ExperimentEntity entity = experiment("draft", "{}");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        ExperimentDTO dto = new ExperimentDTO();
        dto.status = "LIVE";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("publishExperiment - 校验通过后置为 running")
    void publishExperiment_success() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO published = experimentService.publishExperiment("exp_1");

        assertThat(published.status).isEqualTo("LIVE");
        assertThat(entity.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("publishExperiment - 缺少 variants 拒绝发布")
    void publishExperiment_invalidConfigThrows() {
        ExperimentEntity entity = experiment("draft", "{\"metrics\":{}}");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> experimentService.publishExperiment("exp_1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("publishExperiment - configJson 非法 JSON 抛状态异常")
    void publishExperiment_invalidJsonThrows() {
        ExperimentEntity entity = experiment("draft", "{{invalid-json");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> experimentService.publishExperiment("exp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("pauseExperiment - 置为 paused")
    void pauseExperiment_success() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO paused = experimentService.pauseExperiment("exp_1");

        assertThat(paused.status).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("pauseExperiment - 实验不存在抛参数异常")
    void pauseExperiment_notFoundThrows() {
        lenient().when(experimentRepo.findById("exp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> experimentService.pauseExperiment("exp_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Experiment not found");
    }

    @Test
    @DisplayName("listExperiments - 仅按环境名过滤时必须提供 gameId")
    void listExperiments_environmentNameRequiresGameId() {
        assertThatThrownBy(() -> experimentService.listExperiments(null, null, "prod", null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gameId is required");
    }

    @Test
    @DisplayName("listExperiments - 返回分页 DTO")
    void listExperiments_success() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        PageImpl<ExperimentEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        lenient().when(experimentRepo.search(any(), any(), any(), any(Pageable.class))).thenReturn(page);
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ControlService.Paged<ExperimentDTO> result =
            experimentService.listExperiments("game_1", null, null, null, 0, 10);

        assertThat(result.items).hasSize(1);
        assertThat(result.items.get(0).id).isEqualTo("exp_1");
        assertThat(result.total).isEqualTo(1L);
    }

    @Test
    @DisplayName("assign - 对 subjectId 做 trim 后分流")
    void assign_trimsSubjectId() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        String assigned = experimentService.assign("exp_1", "  user_42  ");

        assertThat(assigned).isIn("control", "treatment");
        assertThat(experimentService.assign("exp_1", "user_42")).isEqualTo(assigned);
    }

}
