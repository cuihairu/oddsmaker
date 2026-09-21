package io.oddsmaker.control.api;

import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.TrackingPlanDTO;
import io.oddsmaker.control.service.TrackingPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * INSTR 收口：getTrackingPlan/getEventDefinition 的 .map 映射 lambda（found 侧）——
 * 此前测试只走了 Optional.empty 的 404 路径，200 路径的映射体从未执行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrackingPlan Controller found 侧补充")
class TrackingPlanFoundSidesTest {

    @Mock
    private TrackingPlanService trackingPlanService;

    @InjectMocks
    private TrackingPlanController controller;

    @Test
    @DisplayName("getTrackingPlan：存在时映射 200（map lambda found 侧）")
    void getTrackingPlanFound() {
        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.id = "tp_1";
        when(trackingPlanService.getTrackingPlan("tp_1")).thenReturn(Optional.of(dto));

        var resp = controller.getTrackingPlan("g1", "tp_1");
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("tp_1", resp.getBody().data.id);
    }

    @Test
    @DisplayName("getEventDefinition：存在时映射 200（map lambda found 侧）")
    void getEventDefinitionFound() {
        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.id = "ev_1";
        when(trackingPlanService.getEventDefinition("ev_1")).thenReturn(Optional.of(dto));

        var resp = controller.getEventDefinition("g1", "tp_1", "ev_1");
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals("ev_1", resp.getBody().data.id);
    }
}
