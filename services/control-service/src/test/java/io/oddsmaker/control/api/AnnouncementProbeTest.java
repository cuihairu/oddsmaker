package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AnnouncementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·组3：AnnouncementController 的 get/schedule/delete 三方法 404 判断
 * （39/81/102 行 = null 侧 / 跨游戏不匹配侧 / 匹配侧 三态穷举）。
 * 此前 OpsControllersTest.announcementMissingSides 的「跨游戏」调用因 stub 不跨测试方法，
 * 实际全部落在 null 侧——不匹配侧（gameId.equals 为 false）从未真正走过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("公告 404 三态穷举（含跨游戏不匹配侧）")
class AnnouncementProbeTest {

    @Mock AccessGuard accessGuard;
    @Mock AnnouncementService announcementService;
    @InjectMocks AnnouncementController controller;

    private AnnouncementEntity own() {
        AnnouncementEntity e = new AnnouncementEntity();
        e.id = "a1";
        e.gameId = "g";
        return e;
    }

    @Test
    @DisplayName("get/schedule/delete：存在且本游戏 200；不存在 404；存在但跨游戏 404")
    void allThreeFortyFourSides() {
        when(announcementService.get("a1")).thenReturn(own());
        when(announcementService.get("nope")).thenReturn(null);
        lenient().when(announcementService.update(anyString(), any(), anyString())).thenReturn(own());
        lenient().when(announcementService.publish(anyString(), anyString())).thenReturn(own());
        lenient().when(announcementService.schedule(anyString(), any(), anyString())).thenReturn(own());
        lenient().when(announcementService.delete(anyString(), anyString())).thenReturn(true);

        // get（39 行）
        assertEquals(200, controller.get("g", "a1").getStatusCode().value());
        assertEquals(404, controller.get("g", "nope").getStatusCode().value());
        assertEquals(404, controller.get("other-game", "a1").getStatusCode().value());
        // schedule（81 行）
        AnnouncementController.ScheduleReq req = new AnnouncementController.ScheduleReq();
        assertEquals(200, controller.schedule("g", "a1", req).getStatusCode().value());
        assertEquals(404, controller.schedule("g", "nope", req).getStatusCode().value());
        assertEquals(404, controller.schedule("other-game", "a1", req).getStatusCode().value());
        // delete（102 行）
        assertEquals(200, controller.delete("g", "a1").getStatusCode().value());
        assertEquals(404, controller.delete("g", "nope").getStatusCode().value());
        assertEquals(404, controller.delete("other-game", "a1").getStatusCode().value());
    }
}
