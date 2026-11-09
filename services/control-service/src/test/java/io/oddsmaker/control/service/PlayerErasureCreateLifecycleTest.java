package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试（LAY-014 拆分）：创建校验与固化 JSON、取消矩阵、get/list 校验、user_id 类型展开、截断审计标记、已删游戏拒绝、序列化失败 IAE。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureCreateLifecycleTest extends PlayerErasureServiceTestBase {

    @Test
    @DisplayName("创建校验：游戏不存在 / 空 value / 非法类型均抛 IAE")
    void createValidation() {
        assertThatThrownBy(() -> service.create("gX", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", "PLAYER_ID", " ", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", "EMAIL", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("创建成功：固化 resolvedIdentities JSON + 审计 + PENDING")
    void createSuccess() throws Exception {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());

        PlayerErasureRequestEntity req = service.create("g1", "player_id", "p1", null, "op");

        assertThat(req.id).startsWith("per_").hasSize(28);
        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(req.requestType).isEqualTo(PlayerErasureRequestEntity.RequestType.PLAYER_ID);
        JsonNode resolved = objectMapper.readTree(req.resolvedIdentities);
        assertThat(resolved.get("playerIds").get(0).asText()).isEqualTo("p1");
        assertThat(resolved.get("truncated").asBoolean()).isFalse();
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.CREATE),
            eq("player_erasure_request"), eq(req.id), eq("p1"), anyString(),
            eq(AuditLogEntity.AuditResult.SUCCESS), eq("op"),
            isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("取消：PENDING 可取消；PROCESSING 抛 ISE")
    void cancelMatrix() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));

        PlayerErasureRequestEntity cancelled = service.cancel(req.id, "op2");
        assertThat(cancelled.status).isEqualTo(PlayerErasureRequestEntity.Status.CANCELLED);
        assertThat(cancelled.cancelledBy).isEqualTo("op2");
        assertThat(cancelled.cancelledAt).isNotNull();

        req.status = PlayerErasureRequestEntity.Status.PROCESSING;
        assertThatThrownBy(() -> service.cancel(req.id, "op2"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("get：不存在抛 IAE（Controller 映射 404）")
    void getNotFound() {
        when(requestRepo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("list：校验游戏存在后按创建倒序返回")
    void listRequiresGame() {
        when(requestRepo.findByGameIdOrderByCreatedAtDesc("g1"))
            .thenReturn(List.of(savedRequest()));
        assertThat(service.list("g1")).hasSize(1);
        assertThatThrownBy(() -> service.list("gX"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("创建：requestType 缺省抛 IAE；user_id 类型展开落 JSON")
    void createUserIdTypeAndNullType() throws Exception {
        assertThatThrownBy(() -> service.create("g1", null, "x", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);

        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus("g1", "u1"))
            .thenReturn(List.of(identity("idu1", "u1", null, null, null)));
        when(identityRepo.findById("idu1")).thenReturn(Optional.empty());
        when(identityLinkRepo.findByIdentityIdAnyStatus("idu1")).thenReturn(List.of());

        PlayerErasureRequestEntity req = service.create("g1", "user_id", "u1", null, "op");
        assertThat(req.requestType).isEqualTo(PlayerErasureRequestEntity.RequestType.USER_ID);
        JsonNode resolved = objectMapper.readTree(req.resolvedIdentities);
        assertThat(resolved.get("userIds").get(0).asText()).isEqualTo("u1");
    }

    @Test
    @DisplayName("创建：展开截断时审计详情带 [truncated] 标记")
    void createTruncatedDetail() {
        ReflectionTestUtils.setField(service, "identityExpansionLimit", 2);
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        IdentityEntity seed = identity("idt0", "u0", "p0", "d0", "c0");
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p0")).thenReturn(List.of(seed));
        when(identityRepo.findById("idt0")).thenReturn(Optional.of(seed));

        PlayerErasureRequestEntity req = service.create("g1", "player_id", "p0", null, "op");

        assertThat(req.resolvedIdentities).contains("\"truncated\":true");
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.CREATE),
            eq("player_erasure_request"), eq(req.id), eq("p0"), contains("[truncated]"),
            eq(AuditLogEntity.AuditResult.SUCCESS), eq("op"),
            isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("已删游戏上创建请求被拒绝（deletedAt 非空）")
    void createOnDeletedGameRejected() {
        GameEntity deleted = new GameEntity();
        deleted.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("gD")).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.create("gD", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("序列化失败：create 抛出带原因的 IAE")
    void createToJsonFailure() throws Exception {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any()))
            .thenThrow(new RuntimeException("no jackson"));
        assertThatThrownBy(() -> service.create("g1", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serialize");
    }

}
