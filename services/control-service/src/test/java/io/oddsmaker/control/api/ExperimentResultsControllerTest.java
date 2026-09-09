package io.oddsmaker.control.api;

import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotEntity;
import io.oddsmaker.control.experiment.ExperimentMetricSnapshotRepo;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.experiment.ExperimentStatsService;
import io.oddsmaker.control.service.ExperimentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 实验结果 API 测试：SRM（样本比例偏差）输出与指标检验结构。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实验结果 API 测试")
class ExperimentResultsControllerTest {

    @Mock
    private ExperimentRepo experimentRepo;

    @Mock
    private ExperimentMetricSnapshotRepo snapshotRepo;

    @Spy
    private ExperimentStatsService statsService = new ExperimentStatsService();

    @Mock
    private ExperimentService experimentService;

    @InjectMocks
    private ExperimentResultsController controller;

    private static ExperimentMetricSnapshotEntity snapshot(String variant, long count, long successes) {
        ExperimentMetricSnapshotEntity s = new ExperimentMetricSnapshotEntity();
        s.metricName = "purchase";
        s.variant = variant;
        s.windowStart = 0L;
        s.count = count;
        s.sum = successes;
        s.sumSquares = successes;
        s.successes = successes;
        return s;
    }

    @Test
    @DisplayName("results：输出总体与分指标 SRM，失衡样本被检出")
    void resultsIncludeSrmDetection() {
        ExperimentEntity experiment = new ExperimentEntity();
        experiment.id = "exp_1";
        experiment.status = "RUNNING";
        experiment.configJson = "{\"control_variant\":\"control\",\"variants\":"
            + "[{\"name\":\"control\",\"weight\":1},{\"name\":\"treatment\",\"weight\":1}]}";
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experiment));
        // 60/40 失衡
        when(snapshotRepo.findByExperimentIdOrderByWindowStartAsc("exp_1")).thenReturn(List.of(
            snapshot("control", 30_000L, 1_500L),
            snapshot("treatment", 20_000L, 1_400L)));

        Map<String, Object> resp = controller.results("exp_1").getBody();

        assertEquals("exp_1", resp.get("experimentId"));
        assertEquals("control", resp.get("control"));

        ExperimentStatsService.SrmResult srm = (ExperimentStatsService.SrmResult) resp.get("srm");
        assertEquals(2000.0, srm.chiSquare, 1e-3);
        assertTrue(srm.detected);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) resp.get("metrics");
        assertEquals(1, metrics.size());
        assertNotNull(metrics.get(0).get("comparisons"));
        assertTrue(((ExperimentStatsService.SrmResult) metrics.get(0).get("srm")).detected);
    }

    @Test
    @DisplayName("results：均衡样本 SRM 不检出")
    void resultsSrmHealthy() {
        ExperimentEntity experiment = new ExperimentEntity();
        experiment.id = "exp_2";
        experiment.status = "RUNNING";
        experiment.configJson = "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"name\":\"b\",\"weight\":1}]}";
        when(experimentRepo.findById("exp_2")).thenReturn(Optional.of(experiment));
        when(snapshotRepo.findByExperimentIdOrderByWindowStartAsc("exp_2")).thenReturn(List.of(
            snapshot("a", 50_100L, 100L),
            snapshot("b", 49_900L, 120L)));

        Map<String, Object> resp = controller.results("exp_2").getBody();

        ExperimentStatsService.SrmResult srm = (ExperimentStatsService.SrmResult) resp.get("srm");
        assertFalse(srm.detected);
        // control 缺省取第一个变体
        assertEquals("a", resp.get("control"));
    }

    @Test
    @DisplayName("实验不存在抛 IllegalArgumentException")
    void rejectsUnknownExperiment() {
        when(experimentRepo.findById("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.results("nope"));
    }
}
