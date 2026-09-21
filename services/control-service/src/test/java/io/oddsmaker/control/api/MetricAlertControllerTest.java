package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.MetricAlertRuleEntity;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.MetricAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务指标告警 Controller 测试：行内鉴权 scope、gameId 归属校验与委托。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("业务指标告警 Controller 测试")
class MetricAlertControllerTest {

    @Mock
    private MetricAlertService service;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private MetricAlertController controller;

    private MetricAlertRuleEntity rule(String id) {
        MetricAlertRuleEntity rule = new MetricAlertRuleEntity();
        rule.id = id;
        rule.gameId = "g";
        rule.name = "收入下限";
        return rule;
    }

    @Test
    @DisplayName("规则列表与详情：alert:read 鉴权 + 不存在返回 404")
    void listAndGet() {
        when(service.list("g")).thenReturn(List.of(rule("alr_1")));
        assertEquals(1, controller.list("g").getBody().size());
        verify(accessGuard).requireGamePermission("g", "alert:read");

        when(service.get("g", "alr_1")).thenReturn(rule("alr_1"));
        assertEquals("alr_1", controller.get("g", "alr_1").getBody().id);
        assertEquals(404, controller.get("g", "alr_missing").getStatusCode().value());
    }

    @Test
    @DisplayName("创建/更新：alert:manage 鉴权 + 委托（非法规则 Service 抛 400）")
    void createAndUpdate() {
        MetricAlertRuleEntity req = rule(null);
        when(service.create("g", req, "api")).thenReturn(rule("alr_new"));
        assertEquals("alr_new", controller.create("g", req).getBody().id);
        verify(accessGuard).requireGamePermission("g", "alert:manage");

        when(service.update("g", "alr_1", req, "api")).thenReturn(rule("alr_1"));
        assertEquals("alr_1", controller.update("g", "alr_1", req).getBody().id);
    }

    @Test
    @DisplayName("非法规则：Service 校验异常直接向上传播（GlobalExceptionHandler 转 400）")
    void createPropagatesValidationError() {
        when(service.create(eq("g"), any(), any())).thenThrow(new IllegalArgumentException("bad rule"));
        assertThrows(IllegalArgumentException.class, () -> controller.create("g", rule(null)));
    }

    @Test
    @DisplayName("删除：存在返回 deleted=true，不存在 404")
    void delete() {
        when(service.delete("g", "alr_1", "api")).thenReturn(true);
        Map<String, Object> body = controller.delete("g", "alr_1").getBody();
        assertEquals(true, body.get("deleted"));
        assertEquals("alr_1", body.get("id"));

        when(service.delete("g", "alr_1", "api")).thenReturn(false);
        assertEquals(404, controller.delete("g", "alr_1").getStatusCode().value());
    }

    @Test
    @DisplayName("试算与告警历史：鉴权 + 委托")
    void evaluateAndHistory() {
        Map<String, Object> result = Map.of("available", true, "fired", true);
        when(service.evaluateNow("g", "alr_1")).thenReturn(result);
        assertEquals(result, controller.evaluate("g", "alr_1").getBody());
        verify(accessGuard).requireGamePermission("g", "alert:manage");

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        when(service.alertHistory("g", 50)).thenReturn(List.of(alert));
        assertEquals(1, controller.alerts("g", 50).getBody().size());
        verify(accessGuard).requireGamePermission("g", "alert:read");
    }

    @Test
    @DisplayName("确认与解决：默认操作人取当前认证，body 可覆盖")
    void acknowledgeAndResolve() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        when(service.acknowledge("g", "alert_x", "api", "收到")).thenReturn(alert);
        MetricAlertController.AlertActionReq req = new MetricAlertController.AlertActionReq();
        req.comment = "收到";
        assertEquals("alert_x", controller.acknowledge("g", "alert_x", req).getBody().id);
        verify(service).acknowledge("g", "alert_x", "api", "收到");
        verify(accessGuard).requireGamePermission("g", "alert:manage");

        when(service.resolve("g", "alert_x", "alice", "已修复")).thenReturn(alert);
        MetricAlertController.AlertActionReq req2 = new MetricAlertController.AlertActionReq();
        req2.by = "alice";
        req2.comment = "已修复";
        assertEquals("alert_x", controller.resolve("g", "alert_x", req2).getBody().id);
        verify(service).resolve("g", "alert_x", "alice", "已修复");

        // body 缺省也可（required=false）
        when(service.resolve("g", "alert_x", "api", null)).thenReturn(alert);
        assertTrue(controller.resolve("g", "alert_x", null).getBody() != null);
    }

    @Test
    @DisplayName("确认/解决：req 非 null 但 by 字段为 null（c2 短路侧）回落认证主体")
    void acknowledgeAndResolveNullByField() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        when(service.acknowledge(any(), any(), any(), any())).thenReturn(alert);
        when(service.resolve(any(), any(), any(), any())).thenReturn(alert);

        // req 非 null、req.by == null → 条件链在第二条件短路，by 回落 currentOperator()
        MetricAlertController.AlertActionReq nullBy = new MetricAlertController.AlertActionReq();
        nullBy.comment = "备注";
        controller.acknowledge("g", "alert_x", nullBy);
        verify(service).acknowledge("g", "alert_x", "api", "备注");

        MetricAlertController.AlertActionReq nullBy2 = new MetricAlertController.AlertActionReq();
        controller.resolve("g", "alert_x", nullBy2);
        verify(service).resolve("g", "alert_x", "api", null);

        // acknowledge 的三真路径（by 直用侧）：已有用例的 "api" 是 currentOperator 缺省值，
        // 显式 by 才走三元取 req.by 分支
        MetricAlertController.AlertActionReq explicitBy = new MetricAlertController.AlertActionReq();
        explicitBy.by = "alice";
        controller.acknowledge("g", "alert_x", explicitBy);
        verify(service).acknowledge("g", "alert_x", "alice", null);
    }

    @Test
    @DisplayName("确认/解决：req null 与 by 空白串回落认证主体；认证主体存在取 getName")
    void acknowledgeAndResolveSides() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        when(service.acknowledge(any(), any(), any(), any())).thenReturn(alert);
        when(service.resolve(any(), any(), any(), any())).thenReturn(alert);

        // acknowledge req=null：by 与 comment 均取缺省（87/88 行 null 侧）
        controller.acknowledge("g", "alert_x", null);
        // by 空白串：视为缺省（87 行 isBlank 侧）
        MetricAlertController.AlertActionReq blankBy = new MetricAlertController.AlertActionReq();
        blankBy.by = "   ";
        controller.acknowledge("g", "alert_x", blankBy);
        verify(service, org.mockito.Mockito.times(2)).acknowledge("g", "alert_x", "api", null);

        // resolve 的同款对侧（97 行 isBlank 侧）
        MetricAlertController.AlertActionReq blankBy2 = new MetricAlertController.AlertActionReq();
        blankBy2.by = "   ";
        controller.resolve("g", "alert_x", blankBy2);
        verify(service).resolve("g", "alert_x", "api", null);

        // 认证主体存在（110 行 auth != null 侧）：by 缺省取 getName()
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(
            "alice", "n", "ROLE_ADMIN");
        try {
            org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(auth);
            controller.acknowledge("g", "alert_x", new MetricAlertController.AlertActionReq());
            verify(service).acknowledge("g", "alert_x", "alice", null);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

}
