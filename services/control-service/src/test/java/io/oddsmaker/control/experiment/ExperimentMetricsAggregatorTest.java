package io.oddsmaker.control.experiment;

import io.oddsmaker.control.service.ClickHouseClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 实验指标聚合器测试：exposure/归因指标回填、幂等覆盖、降级与入参校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实验指标聚合器测试")
class ExperimentMetricsAggregatorTest {

    @Mock
    private ExperimentRepo experimentRepo;

    @Mock
    private ExperimentMetricSnapshotRepo snapshotRepo;

    @Mock
    private ClickHouseClient clickHouse;

    @InjectMocks
    private ExperimentMetricsAggregator aggregator;

    private static Map<String, Object> row(Object... kv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("aggregate：exposure 与归因指标回填快照（字段正确）")
    void aggregateWritesSnapshots() {
        when(clickHouse.query(contains("experiment_exposure"), any(), eq("exp_1")))
            .thenReturn(List.of(row("variant", "control", "users", 40L), row("variant", "treatment", "users", 60L)));
        when(clickHouse.query(contains("mapContains"), any()))
            .thenReturn(List.of(row("variant", "treatment", "cnt", 3L, "rev", 9.99, "rev_squares", 99.8001)));
        when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(Optional.empty());

        int written = aggregator.aggregate("exp_1");

        assertEquals(4, written);  // exposure 2 变体 + 归因行产生 events_count/revenue 2 个指标
        ArgumentCaptor<ExperimentMetricSnapshotEntity> captor = ArgumentCaptor.forClass(ExperimentMetricSnapshotEntity.class);
        verify(snapshotRepo, times(4)).save(captor.capture());

        List<ExperimentMetricSnapshotEntity> saved = captor.getAllValues();
        ExperimentMetricSnapshotEntity exposureControl = saved.stream()
            .filter(s -> "exposure_users".equals(s.metricName) && "control".equals(s.variant)).findFirst().orElseThrow();
        assertEquals(40L, exposureControl.count);
        assertNotNull(exposureControl.id);
        assertTrue(exposureControl.windowStart > 0);

        ExperimentMetricSnapshotEntity revenue = saved.stream()
            .filter(s -> "revenue".equals(s.metricName)).findFirst().orElseThrow();
        assertEquals("treatment", revenue.variant);
        assertEquals(3L, revenue.count);
        assertEquals(9.99, revenue.sum, 1e-9);
        assertEquals(99.8001, revenue.sumSquares, 1e-9);

        ExperimentMetricSnapshotEntity events = saved.stream()
            .filter(s -> "events_count".equals(s.metricName)).findFirst().orElseThrow();
        assertEquals(3L, events.count);
    }

    @Test
    @DisplayName("aggregate：同窗口已有快照时覆盖更新而非新建")
    void aggregateUpsertsExistingSnapshot() {
        when(clickHouse.query(contains("experiment_exposure"), any(), eq("exp_1")))
            .thenReturn(List.of(row("variant", "control", "users", 7L)));
        when(clickHouse.query(contains("mapContains"), any())).thenReturn(List.of());
        ExperimentMetricSnapshotEntity existing = new ExperimentMetricSnapshotEntity();
        existing.id = "ems_exists";
        existing.experimentId = "exp_1";
        existing.metricName = "exposure_users";
        existing.variant = "control";
        existing.windowStart = 123L;
        when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(eq("exp_1"), eq("exposure_users"), eq("control"), anyLong()))
            .thenReturn(Optional.of(existing));

        aggregator.aggregate("exp_1");

        ArgumentCaptor<ExperimentMetricSnapshotEntity> captor = ArgumentCaptor.forClass(ExperimentMetricSnapshotEntity.class);
        verify(snapshotRepo).save(captor.capture());
        assertEquals("ems_exists", captor.getValue().id);
        assertEquals(7L, captor.getValue().count);
    }

    @Test
    @DisplayName("aggregateRunning：ClickHouse 不可用时跳过")
    void aggregateRunningSkipsWhenClickHouseUnavailable() {
        when(clickHouse.isAvailable()).thenReturn(false);

        aggregator.aggregateRunning();

        verify(experimentRepo, never()).findByStatus(anyString());
        verify(clickHouse, never()).query(anyString());
    }

    @Test
    @DisplayName("aggregateRunning：单实验失败不中断其余实验")
    void aggregateRunningToleratesFailure() {
        when(clickHouse.isAvailable()).thenReturn(true);
        ExperimentEntity bad = new ExperimentEntity();
        bad.id = "exp_bad";
        ExperimentEntity good = new ExperimentEntity();
        good.id = "exp_good";
        when(experimentRepo.findByStatus("running")).thenReturn(List.of(bad, good));
        when(clickHouse.query(contains("experiment_exposure"), any(), eq("exp_bad")))
            .thenThrow(new RuntimeException("ch down"));
        when(clickHouse.query(contains("experiment_exposure"), any(), eq("exp_good")))
            .thenReturn(List.of(row("variant", "control", "users", 1L)));
        when(clickHouse.query(contains("mapContains"), any())).thenReturn(List.of());
        when(snapshotRepo.findByExperimentIdAndMetricNameAndVariantAndWindowStart(anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(Optional.empty());

        aggregator.aggregateRunning();

        verify(snapshotRepo, times(1)).save(any(ExperimentMetricSnapshotEntity.class));
    }

    @Test
    @DisplayName("aggregate：非法实验 id 被拒绝")
    void aggregateRejectsUnsafeId() {
        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate("exp'; DROP"));
        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(null));
    }

    @Test
    @DisplayName("aggregate：空 variant 行不写快照")
    void aggregateSkipsBlankVariant() {
        when(clickHouse.query(contains("experiment_exposure"), any(), eq("exp_1")))
            .thenReturn(List.of(row("variant", "", "users", 5L)));
        when(clickHouse.query(contains("mapContains"), any())).thenReturn(List.of());

        int written = aggregator.aggregate("exp_1");

        assertEquals(0, written);
        verify(snapshotRepo, never()).save(any());
    }
}
