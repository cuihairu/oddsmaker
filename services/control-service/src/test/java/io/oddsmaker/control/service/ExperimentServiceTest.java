package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ExperimentService experimentService;

    private static ExperimentEntity experiment(String status, String configJson) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_demo";
        e.status = status;
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
        io.oddsmaker.control.dto.ExperimentDTO blank = new io.oddsmaker.control.dto.ExperimentDTO();
        blank.gameId = " ";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(blank));
        when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        io.oddsmaker.control.dto.ExperimentDTO missing = new io.oddsmaker.control.dto.ExperimentDTO();
        missing.gameId = "game_x";
        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(missing));

        // listExperiments：status 非空白走标准化后下传 repo
        when(experimentRepo.search(any(), any(), any(), any()))
            .thenReturn(org.springframework.data.domain.Page.empty());
        experimentService.listExperiments("game_demo", null, null, " RUNNING ", 0, 10);
        org.mockito.Mockito.verify(experimentRepo)
            .search(eq("game_demo"), any(), eq("running"), any());
    }
}
