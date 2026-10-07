package io.oddsmaker.gateway.metrics;

import io.oddsmaker.gateway.metrics.DataQualityCounters.Counter;
import io.oddsmaker.gateway.metrics.DataQualityCounters.WindowSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityReporterTest {

    @Test
    @DisplayName("buildPayload：键集与 control 侧 Snapshot 字段精确一致（camelCase 字面量，不依赖命名策略）")
    void payloadKeysMatchControlSnapshot() {
        DataQualityCounters counters = new DataQualityCounters();
        counters.record("g1", "prod", Counter.RECEIVED);
        counters.record("g1", "prod", Counter.ACCEPTED);
        counters.record("g1", "prod", Counter.DUPLICATES);
        counters.record("g1", "prod", Counter.REJECTED_UNKNOWN_EVENT);
        counters.record("g1", "prod", Counter.LATE);
        WindowSnapshot s = counters.snapshotAndEvict().get(0);

        Map<String, Object> payload = DataQualityReporter.buildPayload(s);

        // 与 InternalDataQualityController.Snapshot 全字段对账：多一键/少一键都会在 control 侧静默丢值
        assertEquals(Set.of(
                "gameId", "environment", "windowStart", "windowSec",
                "received", "accepted", "sampledOut",
                "rejectedSchema", "rejectedUnknownEvent", "rejectedInvalidTimestamp", "rejectedPiiBlocked",
                "rejectedPayloadTooLarge", "rejectedTrustEscalation", "rejectedBlocked",
                "rejectedScopeMismatch", "rejectedKafkaError",
                "duplicatesGateway", "duplicatesEnrich", "late", "dlqOther"),
            payload.keySet());
        assertEquals("g1", payload.get("gameId"));
        assertEquals("prod", payload.get("environment"));
        assertEquals(300, payload.get("windowSec"));
        assertEquals(1L, payload.get("received"));
        assertEquals(1L, payload.get("accepted"));
        assertEquals(1L, payload.get("duplicatesGateway"));
        assertEquals(1L, payload.get("rejectedUnknownEvent"));
        assertEquals(1L, payload.get("late"));
        assertEquals(0L, payload.get("dlqOther"));
        // UTC 窗口起点 ISO 串（control 侧 LocalDateTime.parse 直接受）
        assertTrue(((String) payload.get("windowStart")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?"));
    }
}
