package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.DataQualityMetricsEntity;
import io.oddsmaker.control.jpa.FeatureStoreEntity;
import io.oddsmaker.control.jpa.FeatureStoreRepo;
import io.oddsmaker.control.service.DataQualityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQualityControllersTest {

    @Mock
    DataQualityService dataQualityService;

    @Mock
    FeatureStoreRepo featureStoreRepo;

    @InjectMocks
    InternalDataQualityController internalController;

    @InjectMocks
    DataQualityController readController;

    private InternalDataQualityController.Snapshot validSnapshot() {
        InternalDataQualityController.Snapshot s = new InternalDataQualityController.Snapshot();
        s.gameId = "game_1";
        s.environment = "prod";
        s.windowStart = "2026-10-07T12:00:00";
        s.received = 100;
        s.accepted = 90;
        return s;
    }

    @Test
    @DisplayName("ingest：合法快照 200 并回窗口起点；缺键 400；非法时间格式 400")
    void ingestPaths() {
        DataQualityMetricsEntity saved = new DataQualityMetricsEntity();
        saved.gameId = "game_1";
        saved.windowStart = LocalDateTime.of(2026, 10, 7, 12, 0);
        when(dataQualityService.ingest(any())).thenReturn(saved);
        ResponseEntity<Map<String, Object>> ok = internalController.ingest(validSnapshot());
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("2026-10-07T12:00", ok.getBody().get("windowStart"));

        InternalDataQualityController.Snapshot missing = validSnapshot();
        missing.gameId = null;
        when(dataQualityService.ingest(any()))
            .thenThrow(new IllegalArgumentException("gameId, environment and windowStart are required"));
        assertEquals(400, internalController.ingest(missing).getStatusCode().value());

        InternalDataQualityController.Snapshot badTime = validSnapshot();
        badTime.windowStart = "not-a-time";
        assertEquals(400, internalController.ingest(badTime).getStatusCode().value());
    }

    @Test
    @DisplayName("读面：series/summary 委托服务；featureStore 空 scopeKey 400")
    void readPaths() {
        when(dataQualityService.series(any(), any(), anyInt())).thenReturn(List.of(Map.of("received", 1)));
        when(dataQualityService.summary(any(), any(), anyInt())).thenReturn(Map.of("received", 1));
        assertEquals(200, readController.series("g", "prod", 24).getStatusCode().value());
        assertEquals(200, readController.summary("g", "prod", 24).getStatusCode().value());
        assertEquals(400, readController.featureStore("g", "prod", " ", 24).getStatusCode().value());
        assertTrue(readController.featureStore("g", "prod", null, 24).getStatusCode().is4xxClientError());

        FeatureStoreEntity row = new FeatureStoreEntity();
        row.scopeKey = "PLAYER:u1";
        row.windowStart = LocalDateTime.of(2026, 10, 7, 11, 55);
        row.windowEnd = LocalDateTime.of(2026, 10, 7, 12, 0);
        row.asOf = LocalDateTime.of(2026, 10, 7, 12, 0);
        row.features = "{\"avg_bet@300\":1.5}";
        when(featureStoreRepo
            .findByGameIdAndEnvironmentAndScopeKeyAndWindowEndGreaterThanEqualOrderByWindowEndDesc(
                any(), any(), any(), any())).thenReturn(List.of(row));
        List<Map<String, Object>> rows = readController.featureStore("g", "prod", "PLAYER:u1", 24).getBody();
        assertEquals(1, rows.size());
        assertEquals("PLAYER:u1", rows.get(0).get("scopeKey"));
        assertEquals("{\"avg_bet@300\":1.5}", rows.get(0).get("features"));
    }
}
