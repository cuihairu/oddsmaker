package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DataQualityMetricsEntity;
import io.oddsmaker.control.jpa.DataQualityMetricsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQualityServiceTest {

    @Mock
    DataQualityMetricsRepo repo;

    @InjectMocks
    DataQualityService service;

    private DataQualityMetricsEntity full(long received, long accepted, long sampled, long unknown,
                                          long dupGateway, long dupEnrich, long late) {
        DataQualityMetricsEntity e = new DataQualityMetricsEntity();
        e.gameId = "game_1";
        e.environment = "prod";
        e.windowStart = LocalDateTime.of(2026, 10, 7, 12, 0);
        e.windowSec = 300;
        e.received = received;
        e.accepted = accepted;
        e.sampledOut = sampled;
        e.rejectedUnknownEvent = unknown;
        e.rejectedSchema = 5;
        e.duplicatesGateway = dupGateway;
        e.duplicatesEnrich = dupEnrich;
        e.late = late;
        return e;
    }

    @Test
    @DisplayName("ingest：新键落插入，已存在键整行覆盖（乱序快照无害）")
    void ingestOverwritesExistingKey() {
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        service.ingest(full(100, 90, 0, 5, 0, 0, 0));
        ArgumentCaptor<DataQualityMetricsEntity> first = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo).save(first.capture());
        assertEquals(100, first.getValue().received);

        DataQualityMetricsEntity existing = full(100, 90, 0, 5, 0, 0, 0);
        // enrich/DLQ 两列归 DLQ consumer 所有：快照覆盖必须保留，否则 60s 覆盖会抹掉 enrich 增量
        existing.duplicatesEnrich = 7;
        existing.dlqOther = 3;
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.of(existing));
        service.ingest(full(200, 180, 0, 10, 0, 0, 0));
        ArgumentCaptor<DataQualityMetricsEntity> second = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).save(second.capture());
        assertEquals(200, second.getValue().received);
        assertEquals(7, second.getValue().duplicatesEnrich);
        assertEquals(3, second.getValue().dlqOther);
        assertTrue(second.getValue().updatedAt != null);
    }

    @Test
    @DisplayName("ingest：缺键字段拒绝；windowSec 非正归一 300")
    void ingestValidation() {
        DataQualityMetricsEntity bad = full(1, 1, 0, 0, 0, 0, 0);
        bad.gameId = " ";
        assertThrows(IllegalArgumentException.class, () -> service.ingest(bad));

        DataQualityMetricsEntity zeroSec = full(1, 1, 0, 0, 0, 0, 0);
        zeroSec.windowSec = 0;
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        service.ingest(zeroSec);
        ArgumentCaptor<DataQualityMetricsEntity> captor = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertEquals(300, captor.getValue().windowSec);
    }

    @Test
    @DisplayName("恒等式破式只告警不拒收（观测面不丢单）")
    void ingestIdentityBreachStillSaved() {
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        DataQualityMetricsEntity breach = full(100, 80, 0, 5, 0, 0, 0);
        service.ingest(breach);
        ArgumentCaptor<DataQualityMetricsEntity> captor = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertEquals(100, captor.getValue().received);
    }

    @Test
    @DisplayName("mergeEnrichCounts：新窗建行增量；既有行累加；网关列不动；缺键跳过")
    void mergeEnrichCountsIncrements() {
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        LocalDateTime win = LocalDateTime.of(2026, 10, 7, 12, 0);
        service.mergeEnrichCounts("g1", "prod", win, 2, 1);
        ArgumentCaptor<DataQualityMetricsEntity> created = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo).save(created.capture());
        assertEquals(2, created.getValue().duplicatesEnrich);
        assertEquals(1, created.getValue().dlqOther);
        assertEquals(0, created.getValue().received);
        assertEquals(300, created.getValue().windowSec);

        DataQualityMetricsEntity existing = full(100, 90, 0, 5, 0, 0, 0);
        existing.duplicatesEnrich = 2;
        existing.dlqOther = 1;
        when(repo.findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
            any(), any(), any(), anyInt())).thenReturn(Optional.of(existing));
        service.mergeEnrichCounts("g1", "prod", win, 3, 0);
        ArgumentCaptor<DataQualityMetricsEntity> merged = ArgumentCaptor.forClass(DataQualityMetricsEntity.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).save(merged.capture());
        assertEquals(5, merged.getValue().duplicatesEnrich);
        assertEquals(1, merged.getValue().dlqOther);
        assertEquals(100, merged.getValue().received);
        assertEquals(90, merged.getValue().accepted);

        service.mergeEnrichCounts(" ", "prod", win, 1, 1);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("series/summary：五率读时算，0 分母得 0.0，rejectTop 非零降序")
    void ratesAndRejectTop() {        // received=100, accepted=90（含 gateway dup 2）, rejected 5+5=10, sampled 0 → 恒等成立
        DataQualityMetricsEntity row = full(100, 90, 0, 5, 2, 1, 4);
        when(repo.findByGameIdAndEnvironmentAndWindowStartGreaterThanEqualOrderByWindowStartDesc(
            any(), any(), any())).thenReturn(List.of(row));

        List<Map<String, Object>> series = service.series("game_1", "prod", 24);
        assertEquals(1, series.size());
        assertEquals(0.90, (double) series.get(0).get("eventValidRate"), 1e-9);
        assertEquals(0.10, (double) series.get(0).get("dropRate"), 1e-9);
        assertEquals(0.05, (double) series.get(0).get("unknownEventRate"), 1e-9);
        assertEquals(0.03, (double) series.get(0).get("duplicateRate"), 1e-9);
        assertEquals(0.04, (double) series.get(0).get("lateRate"), 1e-9);
        assertEquals(0L, series.get(0).get("identityDelta"));

        Map<String, Object> summary = service.summary("game_1", "prod", 24);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) summary.get("rejectTop");
        assertEquals(2, top.size());
        // 非零值并列时按 count 降序；此处 schema=5 与 unknown=5 并列，顺序稳定即可
        assertEquals("rejectedSchema", top.get(0).get("reason"));

        // 0 分母：无流量窗口不产生 NaN
        DataQualityMetricsEntity empty = new DataQualityMetricsEntity();
        empty.gameId = "g";
        empty.environment = "prod";
        when(repo.findByGameIdAndEnvironmentAndWindowStartGreaterThanEqualOrderByWindowStartDesc(
            any(), any(), any())).thenReturn(List.of(empty));
        List<Map<String, Object>> emptySeries = service.series("g", "prod", 1);
        assertEquals(0.0, (double) emptySeries.get(0).get("eventValidRate"), 1e-9);
        assertEquals(0.0, (double) emptySeries.get(0).get("lateRate"), 1e-9);
    }
}
