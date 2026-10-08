package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AlertEmailConfigEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AlertEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警邮件通道 Controller 测试：鉴权 scope、保存透传、未配置时测试发送拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警邮件通道 Controller 测试")
class AlertEmailConfigControllerTest {

    private static final String GAME = "game_demo";

    @Mock
    private AlertEmailService alertEmailService;

    @Mock
    private AccessGuard accessGuard;

    private AlertEmailConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new AlertEmailConfigController(alertEmailService, accessGuard);
    }

    @Test
    @DisplayName("查询：alert:read 鉴权；未配置返回 200 null 体")
    void getWithReadScope() {
        when(alertEmailService.config(GAME)).thenReturn(null);
        assertEquals(200, controller.get(GAME).getStatusCode().value());
        verify(accessGuard).requireGamePermission(GAME, "alert:read");
    }

    @Test
    @DisplayName("保存：alert:manage 鉴权并透传字段（enabled 缺省视为 false）")
    void saveTransfersFields() {
        when(alertEmailService.save(eq(GAME), eq("prod"), eq("a@x.com"), eq(true), eq("api")))
            .thenReturn(new AlertEmailConfigEntity());

        AlertEmailConfigController.SaveReq req = new AlertEmailConfigController.SaveReq();
        req.recipients = "a@x.com";
        req.environment = "prod";
        req.enabled = true;
        assertEquals(200, controller.save(GAME, req).getStatusCode().value());

        AlertEmailConfigController.SaveReq noEnabled = new AlertEmailConfigController.SaveReq();
        noEnabled.recipients = "a@x.com";
        controller.save(GAME, noEnabled);
        verify(alertEmailService).save(eq(GAME), eq(null), eq("a@x.com"), eq(false), eq("api"));
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission(GAME, "alert:manage");
    }

    @Test
    @DisplayName("测试发送：未配置抛 IAE；已配置返回 sent 结果")
    void testRequiresConfig() {
        when(alertEmailService.config(GAME)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> controller.test(GAME));

        AlertEmailConfigEntity cfg = new AlertEmailConfigEntity();
        when(alertEmailService.config(GAME)).thenReturn(cfg);
        when(alertEmailService.sendTestEmail(eq(GAME), eq(cfg), eq("api")))
            .thenReturn(Map.of("sent", true));
        assertEquals(Boolean.TRUE, controller.test(GAME).getBody().get("sent"));
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission(GAME, "alert:manage");
    }
}
