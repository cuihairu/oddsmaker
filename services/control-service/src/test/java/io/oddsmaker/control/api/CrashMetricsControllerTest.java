package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.CrashMetricsService;
import io.oddsmaker.control.service.SymbolicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Crash 监控 Controller 测试：三组指标委托 + 符号化端点校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Crash 监控 Controller 测试")
class CrashMetricsControllerTest {

    @Mock
    private CrashMetricsService service;

    @Mock
    private SymbolicationService symbolicationService;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private CrashMetricsController controller;

    @Test
    @DisplayName("三组指标：game:read 鉴权 + 委托")
    void metricsDelegate() {
        Map<String, Object> groups = Map.of("groups", java.util.List.of());
        Map<String, Object> trend = Map.of("points", java.util.List.of());
        Map<String, Object> rates = Map.of("versions", java.util.List.of());
        when(service.topGroups("g", null, 14)).thenReturn(groups);
        when(service.trend("g", "prod", 7)).thenReturn(trend);
        when(service.rateByVersion("g", null, 30)).thenReturn(rates);

        assertEquals(groups, controller.topGroups("g", null, 14).getBody());
        assertEquals(trend, controller.trend("g", "prod", 7).getBody());
        assertEquals(rates, controller.rateByVersion("g", null, 30).getBody());
        verify(accessGuard, org.mockito.Mockito.times(3)).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("符号化：缺平台/版本返回 400，合法请求委托并透传结果")
    void symbolicateValidatesAndDelegates() {
        CrashMetricsController.SymbolicateRequest bad = new CrashMetricsController.SymbolicateRequest();
        bad.appVersion = "1.0";
        assertEquals(400, controller.symbolicate("g", bad).getStatusCode().value());

        CrashMetricsController.SymbolicateRequest ok = new CrashMetricsController.SymbolicateRequest();
        ok.platform = "android";
        ok.appVersion = "1.2.0";
        ok.stackTrace = "at a.b.C(x:1)";
        SymbolicationService.SymbolicationResult result =
            new SymbolicationService.SymbolicationResult("sym_1", "android", "1.2.0", "ok", 1);
        when(symbolicationService.symbolicate("g", "android", "1.2.0", "at a.b.C(x:1)")).thenReturn(result);

        assertEquals(result, controller.symbolicate("g", ok).getBody());
        // bad 与 ok 两次请求都先过权限门卫
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("符号化：空堆栈 400")
    void symbolicateBlankStackRejected() {
        CrashMetricsController.SymbolicateRequest req = new CrashMetricsController.SymbolicateRequest();
        req.platform = "android";
        req.appVersion = "1.2.0";
        org.mockito.Mockito.when(symbolicationService.symbolicate(eq("g"), eq("android"), eq("1.2.0"), isNull()))
            .thenThrow(new IllegalArgumentException("stackTrace is required"));
        assertEquals(400, controller.symbolicate("g", req).getStatusCode().value());
    }
}
