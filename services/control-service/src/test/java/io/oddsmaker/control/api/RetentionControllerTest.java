package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RetentionEnforcementEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RetentionEnforcementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据保留 Controller 测试：全局权限（retention:read/manage）鉴权与输出结构。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据保留 Controller 测试")
class RetentionControllerTest {

    @Mock
    private RetentionEnforcementService service;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private RetentionController controller;

    private static RetentionEnforcementEntity state(String table, RetentionEnforcementEntity.Status status) {
        RetentionEnforcementEntity e = new RetentionEnforcementEntity();
        e.chTable = table;
        e.ttlColumn = "event_date";
        e.status = status;
        e.desiredDays = 90;
        return e;
    }

    @Test
    @DisplayName("状态：retention:read 鉴权 + configured/enabled/expectedDays/items 结构")
    void status() {
        when(service.isConfigured()).thenReturn(true);
        when(service.isEnabled()).thenReturn(false);
        when(service.resolveExpectedDays()).thenReturn(90);
        when(service.listStates())
                .thenReturn(List.of(state("events", RetentionEnforcementEntity.Status.IN_SYNC)));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.status().getBody();

        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("configured"));
        assertEquals(Boolean.FALSE, body.get("enabled"));
        assertEquals(90, body.get("expectedDays"));
        assertEquals(1, ((List<?>) body.get("items")).size());
        verify(accessGuard).requirePermission("retention:read");
    }

    @Test
    @DisplayName("状态：期望值为 null（无有效配置）原样透出")
    void statusNullExpected() {
        when(service.isConfigured()).thenReturn(true);
        when(service.isEnabled()).thenReturn(true);
        when(service.resolveExpectedDays()).thenReturn(null);
        when(service.listStates()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.status().getBody();

        assertNotNull(body);
        assertFalse(body.containsKey("expectedDays") && body.get("expectedDays") != null);
        assertTrue(((List<?>) body.get("items")).isEmpty());
    }

    @Test
    @DisplayName("手动对账：retention:manage 鉴权 + ranAt 与 items 结构")
    void run() {
        when(service.isConfigured()).thenReturn(true);
        when(service.resolveExpectedDays()).thenReturn(90);
        when(service.enforceNow())
                .thenReturn(List.of(state("events", RetentionEnforcementEntity.Status.UPDATING)));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.run().getBody();

        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("configured"));
        assertNotNull(body.get("ranAt"));
        assertEquals(1, ((List<?>) body.get("items")).size());
        verify(accessGuard).requirePermission("retention:manage");
    }

    @Test
    @DisplayName("手动对账：CH 未配置时 service 空转，configured=false 透出")
    void runUnconfigured() {
        when(service.isConfigured()).thenReturn(false);
        when(service.resolveExpectedDays()).thenReturn(90);
        when(service.enforceNow()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.run().getBody();

        assertNotNull(body);
        assertEquals(Boolean.FALSE, body.get("configured"));
        assertTrue(((List<?>) body.get("items")).isEmpty());
    }

    @Test
    @DisplayName("输出为 LinkedHashMap 有序结构（前端渲染依赖键序）")
    void orderedOutput() {
        when(service.isConfigured()).thenReturn(true);
        when(service.isEnabled()).thenReturn(true);
        when(service.resolveExpectedDays()).thenReturn(90);
        when(service.listStates()).thenReturn(List.of());

        Map<String, Object> body = (Map<String, Object>) controller.status().getBody();

        assertEquals(new LinkedHashMap<>().getClass(), body.getClass());
    }
}
