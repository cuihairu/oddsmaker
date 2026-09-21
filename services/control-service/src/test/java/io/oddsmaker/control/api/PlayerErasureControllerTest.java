package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PlayerErasureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求 Controller 测试：privacy:read/manage 鉴权、404/409/400 映射、
 * scheduledFor ISO 解析与操作人兜底。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("玩家数据删除请求 Controller 测试")
class PlayerErasureControllerTest {

    @Mock
    private PlayerErasureService service;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private PlayerErasureController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static PlayerErasureRequestEntity req(String id, String gameId) {
        PlayerErasureRequestEntity r = new PlayerErasureRequestEntity();
        r.id = id;
        r.gameId = gameId;
        r.status = PlayerErasureRequestEntity.Status.PENDING;
        r.requestValue = "p1";
        return r;
    }

    @Test
    @DisplayName("创建：privacy:manage 鉴权 + 无认证时操作人兜底 api + scheduledFor 缺省为 null")
    void create() {
        when(service.create(eq("g"), eq("PLAYER_ID"), eq("p1"), isNull(), eq("api")))
            .thenReturn(req("per_1", "g"));
        PlayerErasureController.CreateRequest body = new PlayerErasureController.CreateRequest();
        body.gameId = "g";
        body.requestType = "PLAYER_ID";
        body.requestValue = "p1";

        PlayerErasureRequestEntity created = (PlayerErasureRequestEntity) controller.create(body).getBody();
        assertEquals("per_1", created.id);
        verify(accessGuard).requireGamePermission("g", "privacy:manage");
    }

    @Test
    @DisplayName("创建：scheduledFor 合法 ISO 透传，非法 ISO 返回 400")
    void createScheduledFor() {
        when(service.create(eq("g"), eq("PLAYER_ID"), eq("p1"),
                eq(LocalDateTime.of(2026, 9, 19, 8, 0)), any())).thenReturn(req("per_1", "g"));
        PlayerErasureController.CreateRequest ok = new PlayerErasureController.CreateRequest();
        ok.gameId = "g";
        ok.requestType = "PLAYER_ID";
        ok.requestValue = "p1";
        ok.scheduledFor = "2026-09-19T08:00:00";
        PlayerErasureRequestEntity scheduled = (PlayerErasureRequestEntity) controller.create(ok).getBody();
        assertEquals("per_1", scheduled.id);

        PlayerErasureController.CreateRequest bad = new PlayerErasureController.CreateRequest();
        bad.gameId = "g";
        bad.requestType = "PLAYER_ID";
        bad.requestValue = "p1";
        bad.scheduledFor = "not-a-date";
        assertEquals(400, controller.create(bad).getStatusCode().value());
    }

    @Test
    @DisplayName("创建：Service 校验异常（游戏不存在/非法类型）映射 400")
    void createValidationError() {
        PlayerErasureController.CreateRequest body = new PlayerErasureController.CreateRequest();
        body.gameId = "g";
        body.requestType = "EMAIL";
        body.requestValue = "p1";
        when(service.create(eq("g"), eq("EMAIL"), eq("p1"), isNull(), any()))
            .thenThrow(new IllegalArgumentException("requestType must be one of"));
        assertEquals(400, controller.create(body).getStatusCode().value());
    }

    @Test
    @DisplayName("列表：gameId 必填（缺省 400）+ privacy:read 鉴权")
    void list() {
        assertEquals(400, controller.list(null).getStatusCode().value());
        assertEquals(400, controller.list(" ").getStatusCode().value());

        when(service.list("g")).thenReturn(List.of(req("per_1", "g")));
        @SuppressWarnings("unchecked")
        List<PlayerErasureRequestEntity> all = (List<PlayerErasureRequestEntity>) controller.list("g").getBody();
        assertEquals(1, all.size());
        verify(accessGuard).requireGamePermission("g", "privacy:read");
    }

    @Test
    @DisplayName("详情：存在走 privacy:read，不存在 404")
    void get() {
        when(service.get("per_1")).thenReturn(req("per_1", "g"));
        PlayerErasureRequestEntity got = (PlayerErasureRequestEntity) controller.get("per_1").getBody();
        assertEquals("per_1", got.id);
        verify(accessGuard).requireGamePermission("g", "privacy:read");

        when(service.get("per_missing"))
            .thenThrow(new IllegalArgumentException("Erasure request not found: per_missing"));
        assertEquals(404, controller.get("per_missing").getStatusCode().value());
    }

    @Test
    @DisplayName("取消：privacy:manage 鉴权 + 非 PENDING 409 + 不存在 404")
    void cancel() {
        when(service.get("per_1")).thenReturn(req("per_1", "g"));
        when(service.cancel("per_1", "api")).thenReturn(req("per_1", "g"));
        PlayerErasureRequestEntity cancelled = (PlayerErasureRequestEntity) controller.cancel("per_1").getBody();
        assertEquals("per_1", cancelled.id);
        verify(accessGuard).requireGamePermission("g", "privacy:manage");

        when(service.cancel("per_1", "api"))
            .thenThrow(new IllegalStateException("Erasure request is not PENDING"));
        assertEquals(409, controller.cancel("per_1").getStatusCode().value());

        when(service.get("per_missing"))
            .thenThrow(new IllegalArgumentException("Erasure request not found"));
        assertEquals(404, controller.cancel("per_missing").getStatusCode().value());
    }

    @Test
    @DisplayName("操作人：有认证时取 principal 名称")
    void currentOperatorFromAuthentication() {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken("alice", "n/a"));
        when(service.create(eq("g"), eq("PLAYER_ID"), eq("p1"), isNull(), eq("alice")))
            .thenReturn(req("per_1", "g"));
        PlayerErasureController.CreateRequest body = new PlayerErasureController.CreateRequest();
        body.gameId = "g";
        body.requestType = "PLAYER_ID";
        body.requestValue = "p1";
        PlayerErasureRequestEntity created = (PlayerErasureRequestEntity) controller.create(body).getBody();
        assertEquals("per_1", created.id);
    }

    @Test
    @DisplayName("创建：scheduledFor 空白串视为缺省 null（isBlank false 侧）")
    void createBlankScheduledFor() {
        when(service.create(eq("g"), eq("PLAYER_ID"), eq("p1"), isNull(), eq("api")))
            .thenReturn(req("per_1", "g"));
        PlayerErasureController.CreateRequest body = new PlayerErasureController.CreateRequest();
        body.gameId = "g";
        body.requestType = "PLAYER_ID";
        body.requestValue = "p1";
        body.scheduledFor = "   ";
        assertEquals(200, controller.create(body).getStatusCode().value());
    }

}
