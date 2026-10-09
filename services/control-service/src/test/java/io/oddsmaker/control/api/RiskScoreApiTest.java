package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 主体累计风险分 API：game:read 鉴权 + 委托 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主体累计风险分 API 测试")
class RiskScoreApiTest {

    private static final String GAME = "game_demo";

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RiskScoreService riskScoreService;

    private RiskScoreController controller;

    @BeforeEach
    void setUp() {
        controller = new RiskScoreController(riskScoreService, accessGuard);
    }

    @Test
    @SuppressWarnings("unchecked")
    void latestGuardsGameReadAndDelegates() {
        when(riskScoreService.latest(GAME, "player_id", "p_100"))
                .thenReturn(Map.of("found", true, "score", 85));

        Map<String, Object> res = controller.latest(GAME, "player_id", "p_100");

        verify(accessGuard).requireGamePermission(GAME, "game:read");
        assertEquals(Boolean.TRUE, res.get("found"));
        assertEquals(85, res.get("score"));
        assertTrue(res.containsKey("score"));
    }
}
