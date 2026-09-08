package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.RiskEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Timestamp;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控处置归档测试：risk_actions 写入参数、risk_scores 联动更新、
 * ClickHouse 不可用/写失败时的降级（不影响处置主链路）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风控处置归档（risk_actions/risk_scores）")
class RiskActionRecorderTest {

    @Mock private ClickHouseClient client;

    private RiskActionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new RiskActionRecorder(client);
    }

    private RiskEventDto event() {
        RiskEventDto e = new RiskEventDto();
        e.gameId = "game_demo";
        e.environment = "prod";
        e.ts = 1730000000000L;
        e.riskEventId = "re_001";
        e.ruleId = "rr_threshold";
        e.severity = "HIGH";
        e.subjectType = "DEVICE";
        e.subjectId = "dev_abc";
        e.score = 0.95f;
        e.action = "BLOCK";
        e.reason = "amount exceeds threshold";
        e.evidence = Map.of("amount", "999");
        return e;
    }

    @Test
    void record_writesActionRowWithAllColumns() {
        when(client.isAvailable()).thenReturn(true);

        recorder.record(event(), "block", "blocked", null);

        verify(client).update(anyString(),
                eq("game_demo"), eq("prod"), eq(new Timestamp(1730000000000L)),
                eq("re_001"), eq(""), eq("rr_threshold"),
                eq("DEVICE"), eq("dev_abc"), eq("HIGH"),
                eq("block"), eq("blocked"), eq("system"),
                eq("amount exceeds threshold"), eq(Map.of("amount", "999")));
        verify(client).execute(any(ConnectionCallback.class));
    }

    @Test
    void record_skipsWhenClickHouseUnavailable() {
        when(client.isAvailable()).thenReturn(false);

        recorder.record(event(), "block", "blocked", null);

        verify(client, never()).update(anyString(), any(Object[].class));
        verify(client, never()).execute(any(ConnectionCallback.class));
    }

    @Test
    void record_toleratesWriteFailure() {
        when(client.isAvailable()).thenReturn(true);
        when(client.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("ch down"));

        assertDoesNotThrow(() -> recorder.record(event(), "block", "blocked", null));

        verify(client, never()).execute(any(ConnectionCallback.class));
    }

    @Test
    void record_withoutScore_skipsSubjectScoreUpdate() {
        when(client.isAvailable()).thenReturn(true);
        RiskEventDto e = event();
        e.score = null;

        recorder.record(e, "alert", "logged", null);

        verify(client).update(anyString(), any(Object[].class));
        verify(client, never()).execute(any(ConnectionCallback.class));
    }

    @Test
    void record_nullTsFallsBackToNow() {
        when(client.isAvailable()).thenReturn(true);
        RiskEventDto e = event();
        e.ts = null;

        recorder.record(e, "mark", "marked", "rc_123");

        verify(client).update(anyString(),
                anyString(), anyString(), any(Timestamp.class),
                eq("re_001"), eq("rc_123"), anyString(),
                anyString(), anyString(), anyString(),
                eq("mark"), eq("marked"), anyString(),
                anyString(), any(Object.class));
    }

    @Test
    void record_nullFieldsNormalizedToEmptyStrings() {
        when(client.isAvailable()).thenReturn(true);
        RiskEventDto e = new RiskEventDto();
        e.gameId = "game_demo";
        e.environment = "dev";
        e.riskEventId = "re_002";
        e.subjectId = "dev_x";
        e.evidence = null;

        recorder.record(e, "throttle", "throttled", null);

        verify(client).update(anyString(),
                eq("game_demo"), eq("dev"), any(Timestamp.class),
                eq("re_002"), eq(""), eq(""),
                eq(""), eq("dev_x"), eq(""),
                eq("throttle"), eq("throttled"), eq("system"),
                eq(""), eq(Map.of()));
        // 无 subjectType/score → 不更新 risk_scores
        verify(client, never()).execute(any(ConnectionCallback.class));
    }

    @Test
    void record_nullRiskEventId_doesNotThrow() {
        when(client.isAvailable()).thenReturn(true);
        RiskEventDto e = new RiskEventDto();

        assertDoesNotThrow(() -> recorder.record(e, "alert", "logged", null));
    }
}
