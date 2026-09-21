package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·组2杂项：IntegrationService buildHeaders 与 IdentityConsumer 的未走分支侧。
 * （BASIC_AUTH 缺凭证不伪造 Authorization 头；link usageCount null 兜底；环境解析缺参侧）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("组2分支收口：Integration 头构建与身份链接兜底")
class Group2FinalBranchTest {

    // ===== IntegrationService.buildHeaders =====

    @Mock private IntegrationRepo integrationRepo;
    @Mock private IntegrationLogRepo integrationLogRepo;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private IntegrationService integrationService;

    private IntegrationEntity basicAuth() {
        IntegrationEntity e = new IntegrationEntity();
        e.id = "i_basic";
        e.gameId = "g1";
        e.name = "basic-hook";
        e.integrationType = IntegrationEntity.IntegrationType.WEBHOOK;
        e.authType = IntegrationEntity.AuthType.BASIC_AUTH;
        e.endpointUrl = "http://unit.test/hook";
        return e;
    }

    @Test
    @DisplayName("BASIC_AUTH：username/password 任一缺失不伪造 Authorization 头（短路两侧）")
    void basicAuthMissingCredentialsSkipsHeader() {
        IntegrationEntity noUser = basicAuth();
        noUser.username = null;      // username == null 短路侧
        noUser.password = "pw";
        HttpHeaders h1 = ReflectionTestUtils.invokeMethod(integrationService, "buildHeaders", noUser);
        assertNull(h1.getFirst("Authorization"));

        IntegrationEntity noPass = basicAuth();
        noPass.username = "alice";
        noPass.password = null;     // password == null 侧
        HttpHeaders h2 = ReflectionTestUtils.invokeMethod(integrationService, "buildHeaders", noPass);
        assertNull(h2.getFirst("Authorization"));

        // 双全才设置（对照侧）
        IntegrationEntity full = basicAuth();
        full.username = "alice";
        full.password = "pw";
        HttpHeaders h3 = ReflectionTestUtils.invokeMethod(integrationService, "buildHeaders", full);
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("alice:pw".getBytes()),
            h3.getFirst("Authorization"));
    }

    // ===== IdentityConsumer =====

    @Mock private IdentityRepo identityRepo;
    @Mock private IdentityLinkRepo identityLinkRepo;
    @Mock private GameEnvironmentRepo gameEnvironmentRepo;

    @InjectMocks
    private IdentityConsumer consumer;

    @BeforeEach
    void wireObjectMapper() {
        ReflectionTestUtils.setField(consumer, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("upsertLink：已有 link 的 usageCount 为 null 时兜底 0+1（null 侧）")
    void existingLinkNullUsageCountBootstrapsToOne() {
        IdentityLinkEntity stale = new IdentityLinkEntity();
        stale.id = "ilk_stale";
        stale.identityId = "idt_1";
        stale.linkedIdentityType = "user_id";
        stale.linkedId = "u1";
        stale.usageCount = null;   // 历史脏数据：直设 null 触发兜底（初始化器 0L 的反面）
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId("idt_1", "user_id", "u1"))
            .thenReturn(Optional.of(stale));

        ReflectionTestUtils.invokeMethod(consumer, "upsertLink",
            "idt_1", "user_id", "u1", java.time.LocalDateTime.now());

        assertEquals(1L, stale.usageCount);   // (null?0)+1 = 1
        verify(identityLinkRepo).save(stale);
    }

    @Test
    @DisplayName("resolveEnvironmentId：gameId null 与 envName null/blank 三侧直接返回 null")
    void resolveEnvironmentIdMissingParamSides() {
        assertNull(ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", null, "prod"));
        assertNull(ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", "g1", null));
        assertNull(ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", "g1", "  "));
        // 缺参侧不查库
        verify(gameEnvironmentRepo, org.mockito.Mockito.never())
            .findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("resolveEnvironmentId：查无环境名缓存 null 后仍返回 null（Optional.ofNullable 缓存侧）")
    void resolveEnvironmentIdUnknownNameCachedNull() {
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "ghost"))
            .thenReturn(List.of());

        assertNull(ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", "g1", "ghost"));
        // 命中 null 缓存：第二次不再打 DB
        assertNull(ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", "g1", "ghost"));
        verify(gameEnvironmentRepo, times(1)).findByGameIdAndNameAndDeletedAtIsNull("g1", "ghost");
    }

    @Test
    @DisplayName("resolveEnvironmentId：repo 抛异常吞掉返回 null（catch 侧）")
    void resolveEnvironmentIdRepoFailureTolerated() {
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString()))
            .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() ->
            ReflectionTestUtils.invokeMethod(consumer, "resolveEnvironmentId", "g1", "prod"));
    }
}
