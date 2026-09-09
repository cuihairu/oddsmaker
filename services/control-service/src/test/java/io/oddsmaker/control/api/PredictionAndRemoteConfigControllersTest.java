package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PredictionMetricsService;
import io.oddsmaker.control.service.RemoteConfigService;
import io.oddsmaker.control.jpa.RemoteConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预测与 Remote Config Controller 测试：权限分层（game:update / risk_rule:*）与委托。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预测与 Remote Config Controller 测试")
class PredictionAndRemoteConfigControllersTest {

    @Mock
    private AccessGuard accessGuard;

    // ===== 预测 =====

    @Mock
    private PredictionMetricsService predictionService;

    @InjectMocks
    private PredictionMetricsController predictionController;

    @Test
    @DisplayName("流失刷新：game:update 鉴权；榜单：game:read 鉴权")
    void churnEndpoints() {
        Map<String, Object> refresh = Map.of("scored", 10);
        Map<String, Object> top = Map.of("users", List.of());
        when(predictionService.refreshChurn("g", null)).thenReturn(refresh);
        when(predictionService.topChurn("g", null, 50)).thenReturn(top);

        assertEquals(refresh, predictionController.refreshChurn("g", null).getBody());
        verify(accessGuard).requireGamePermission("g", "game:update");
        assertEquals(top, predictionController.topChurn("g", null, 50).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("风险模型：刷新 risk_rule:update、榜单 risk_rule:read")
    void riskScoreEndpoints() {
        when(predictionService.refreshRiskScore("g", null)).thenReturn(Map.of("scored", 0));
        when(predictionService.topRiskScore("g", null, 100)).thenReturn(Map.of("users", List.of()));

        predictionController.refreshRiskScore("g", null);
        verify(accessGuard).requireGamePermission("g", "risk_rule:update");
        predictionController.topRiskScore("g", null, 100);
        verify(accessGuard).requireGamePermission("g", "risk_rule:read");
    }

    // ===== Remote Config =====

    @Mock
    private RemoteConfigService remoteConfigService;

    @InjectMocks
    private RemoteConfigController remoteConfigController;

    @Test
    @DisplayName("配置 CRUD：读 game:read、写 game:update；版本一致返回 304")
    void remoteConfigCrudAndResolve() {
        when(remoteConfigService.list("g", null)).thenReturn(List.of());

        RemoteConfigEntity entity = new RemoteConfigEntity();
        entity.id = "rc_1";
        entity.gameId = "g";
        entity.configKey = "k";
        entity.configValue = "1";
        when(remoteConfigService.get("rc_1")).thenReturn(entity);
        when(remoteConfigService.update(any(), any(), any(), any(), any())).thenReturn(entity);

        assertEquals(200, remoteConfigController.list("g", null).getStatusCode().value());
        verify(accessGuard).requireGamePermission("g", "game:read");

        RemoteConfigController.UpdateRequest req = new RemoteConfigController.UpdateRequest();
        req.configValue = "2";
        assertEquals(entity, remoteConfigController.update("g", "rc_1", req).getBody());
        verify(accessGuard).requireGamePermission("g", "game:update");

        when(remoteConfigService.resolve("g", "prod")).thenReturn(Map.of("version", 7L, "configs", Map.of()));
        assertEquals(200, remoteConfigController.resolve("g", "prod", null).getStatusCode().value());
        assertEquals(304, remoteConfigController.resolve("g", "prod", 7L).getStatusCode().value());
    }

    @Test
    @DisplayName("配置创建：成功透传；非法与冲突返回 400")
    void createValidates() {
        RemoteConfigEntity ok = new RemoteConfigEntity();
        ok.configKey = "k";
        ok.configValue = "1";
        when(remoteConfigService.create(any(), any())).thenReturn(ok);
        assertEquals(200, remoteConfigController.create("g", ok).getStatusCode().value());
        verify(accessGuard).requireGamePermission("g", "game:update");

        RemoteConfigEntity bad = new RemoteConfigEntity();
        bad.configKey = "9bad!";
        bad.configValue = "1";
        when(remoteConfigService.create(any(), any()))
            .thenThrow(new IllegalStateException("duplicate"));
        assertEquals(400, remoteConfigController.create("g", bad).getStatusCode().value());
    }

    @Test
    @DisplayName("配置更新：状态冲突返回 400")
    void updateConflictRejected() {
        RemoteConfigEntity own = new RemoteConfigEntity();
        own.id = "rc_4";
        own.gameId = "g";
        when(remoteConfigService.get("rc_4")).thenReturn(own);
        when(remoteConfigService.update(any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("bad json"));
        RemoteConfigController.UpdateRequest req = new RemoteConfigController.UpdateRequest();
        assertEquals(400, remoteConfigController.update("g", "rc_4", req).getStatusCode().value());
    }

    @Test
    @DisplayName("配置删除：本游戏 id 执行软删")
    void deletesOwnGameConfig() {
        RemoteConfigEntity own = new RemoteConfigEntity();
        own.id = "rc_3";
        own.gameId = "g";
        when(remoteConfigService.get("rc_3")).thenReturn(own);

        assertEquals(200, remoteConfigController.delete("g", "rc_3").getStatusCode().value());
        verify(remoteConfigService).delete("rc_3");
    }

    @Test
    @DisplayName("配置：跨游戏 id 更新/删除返回 404")
    void rejectsCrossGameId() {
        RemoteConfigEntity other = new RemoteConfigEntity();
        other.id = "rc_2";
        other.gameId = "other";
        when(remoteConfigService.get("rc_2")).thenReturn(other);

        RemoteConfigController.UpdateRequest req = new RemoteConfigController.UpdateRequest();
        assertEquals(404, remoteConfigController.update("g", "rc_2", req).getStatusCode().value());
        assertEquals(404, remoteConfigController.delete("g", "rc_2").getStatusCode().value());
    }
}
