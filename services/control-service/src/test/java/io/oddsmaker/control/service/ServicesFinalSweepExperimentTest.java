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
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：实验删除返回布尔、获取映射环境名、running 配置分支、按环境名列出与非法状态。与 ExperimentServiceTest / ExperimentServiceDeepTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实验 Service 深度测试（删除/环境名/配置分支）")
class ServicesFinalSweepExperimentTest {

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ExperimentRepo experimentRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private ExperimentService experimentService;

    private static GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private static GameEnvironmentEntity environment(String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env_" + gameId + "_" + name;
        e.gameId = gameId;
        e.name = name;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        return e;
    }

    private static ExperimentEntity experimentEntity(String status) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_1";
        e.environmentId = "env_game_1_prod";
        e.name = "Sweep Exp";
        e.status = "running".equals(status) ? ExperimentEntity.ExperimentStatus.LIVE : ExperimentEntity.ExperimentStatus.valueOf(status.toUpperCase());
        e.salt = "salt";
        e.configJson = "{\"variants\":[{\"name\":\"control\",\"weight\":1},"
            + "{\"name\":\"treatment\",\"weight\":1}]}";
        e.createdAt = LocalDateTime.now();
        e.updatedAt = LocalDateTime.now();
        return e;
    }

    @Test
    @DisplayName("实验：deleteExperiment 存在返回 true 并删除，不存在返回 false")
    void experimentDeleteReturnsTrueFalse() {
        lenient().when(experimentRepo.existsById("exp_1")).thenReturn(true);
        assertTrue(experimentService.deleteExperiment("exp_1"));
        verify(experimentRepo).deleteById("exp_1");

        lenient().when(experimentRepo.existsById("exp_missing")).thenReturn(false);
        assertFalse(experimentService.deleteExperiment("exp_missing"));
        verify(experimentRepo, never()).deleteById("exp_missing");
    }

    @Test
    @DisplayName("实验：getExperiment 回填环境名")
    void experimentGetMapsEnvironmentName() {
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experimentEntity("running")));
        lenient().when(gameEnvironmentRepo.findById("env_game_1_prod")).thenReturn(Optional.of(prod));

        Optional<ExperimentDTO> found = experimentService.getExperiment("exp_1");

        assertTrue(found.isPresent());
        assertEquals("prod", found.get().environment);
        assertEquals("Sweep Exp", found.get().name);
        assertTrue(found.get().config.has("variants"));
    }

    @Test
    @DisplayName("实验：getRunningConfig 三分支（成功/环境不活跃/游戏不存在）")
    void experimentRunningConfigBranches() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.findRunningConfigs("game_1", "env_game_1_prod"))
            .thenReturn(List.of(experimentEntity("running")));

        assertEquals(1, experimentService.getRunningConfig("game_1", "prod").size());

        GameEnvironmentEntity inactive = environment("game_1", "prod");
        inactive.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "staging"))
            .thenReturn(List.of(inactive));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> experimentService.getRunningConfig("game_1", "staging"));
        assertTrue(ex.getMessage().contains("not active"));

        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.getRunningConfig("game_x", "prod"));
    }

    @Test
    @DisplayName("实验：listExperiments 按环境名解析 environmentId，非法 status 过滤被拒绝")
    void experimentListByEnvironmentNameAndInvalidStatus() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.search(any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        ControlService.Paged<ExperimentDTO> result =
            experimentService.listExperiments("game_1", null, "PROD", null, 0, 10);
        assertEquals(0L, result.total);
        verify(experimentRepo).search(eq("game_1"), eq("env_game_1_prod"), isNull(), any(Pageable.class));

    }

}
