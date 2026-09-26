package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.MlArtifactEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.MlArtifactRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ml 产物注册 API 测试：鉴权 + 委托 + 404。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ml 产物注册 API 测试")
class MlArtifactControllerTest {

    @Mock
    private MlArtifactRegistry registry;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private MlArtifactController controller;

    @Test
    @DisplayName("注册：ml:manage 鉴权 + 委托")
    void registerDelegates() {
        Map<String, Object> artifact = Map.of("model_type", "churn", "schema_version", 1);
        MlArtifactController.RegisterRequest request = new MlArtifactController.RegisterRequest();
        request.gameId = "g";
        request.createdBy = "op";
        request.artifact = new HashMap<>(artifact);

        MlArtifactEntity entity = new MlArtifactEntity();
        entity.id = "mla_x";
        when(registry.register("g", request.artifact, "op")).thenReturn(entity);

        assertEquals(entity, controller.register(request).getBody());
        verify(accessGuard).requireGamePermission("g", "ml:manage");
    }

    @Test
    @DisplayName("版本列表：ml:read 鉴权 + 委托（modelType 透传）")
    void listDelegates() {
        MlArtifactEntity entity = new MlArtifactEntity();
        when(registry.listVersions("g", "churn")).thenReturn(List.of(entity));
        when(registry.listVersions("g", null)).thenReturn(List.of(entity, entity));

        assertEquals(1, controller.listVersions("g", "churn").getBody().size());
        assertEquals(2, controller.listVersions("g", null).getBody().size());
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission("g", "ml:read");
    }

    @Test
    @DisplayName("active：命中返回实体，未注册 404")
    void activeFoundAndNotFound() {
        MlArtifactEntity entity = new MlArtifactEntity();
        when(registry.resolveActive("g", "churn")).thenReturn(Optional.of(entity));
        when(registry.resolveActive("g", "pltv")).thenReturn(Optional.empty());

        assertEquals(200, controller.resolveActive("g", "churn").getStatusCode().value());
        assertTrue(controller.resolveActive("g", "pltv").getStatusCode().is4xxClientError());
    }
}
