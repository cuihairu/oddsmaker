package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 游戏分析就绪度服务测试（审计 DATA-002）：CH 可达/链路在产两口径分离，
 * 降级零额外查询，analysisLastEventAt 原样透传不发明阈值。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("游戏分析就绪度服务测试")
class GameReadinessServiceTest {

    @Mock
    private ClickHouseClient client;

    @InjectMocks
    private GameReadinessService service;

    @Test
    @DisplayName("CH 未配置：available=false、就绪度 null，零额外查询")
    void degraded() {
        when(client.isAvailable()).thenReturn(false);

        Map<String, Object> resp = service.readiness("g", "prod");

        assertEquals(Boolean.FALSE, resp.get("available"));
        assertNull(resp.get("analysisLastEventAt"));
        verify(client, never()).analysisLastEventAt(any(), any());
    }

    @Test
    @DisplayName("链路在产：analysisLastEventAt 透传 + game/environment 回显")
    void analysisLive() {
        when(client.isAvailable()).thenReturn(true);
        when(client.analysisLastEventAt("g", "prod")).thenReturn("2026-10-18T08:00:00Z");

        Map<String, Object> resp = service.readiness("g", "prod");

        assertEquals(Boolean.TRUE, resp.get("available"));
        assertEquals("2026-10-18T08:00:00Z", resp.get("analysisLastEventAt"));
        assertEquals("g", resp.get("gameId"));
        assertEquals("prod", resp.get("environment"));
    }

    @Test
    @DisplayName("CH 可达但链路从未产出：available=true、就绪度 null（Standard 档无 Flink 的正常态）")
    void analysisNeverProduced() {
        when(client.isAvailable()).thenReturn(true);
        when(client.analysisLastEventAt("g", null)).thenReturn(null);

        Map<String, Object> resp = service.readiness("g", null);

        assertEquals(Boolean.TRUE, resp.get("available"));
        assertNull(resp.get("analysisLastEventAt"));
        assertNull(resp.get("environment"));
    }
}
