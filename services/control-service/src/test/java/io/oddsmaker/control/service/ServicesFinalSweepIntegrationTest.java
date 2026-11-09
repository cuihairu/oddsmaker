package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：集成统计聚合、调用统计成功率、重试触发验证。与 IntegrationServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("集成 Service 深度测试（统计/成功率/重试）")
class ServicesFinalSweepIntegrationTest {

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @Mock
    private AuditLogService auditLog;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private IntegrationService integrationService;

    private static IntegrationEntity integration(String id, IntegrationEntity.IntegrationStatus status,
                                                 boolean enabled) {
        IntegrationEntity e = new IntegrationEntity();
        e.id = id;
        e.gameId = "game_1";
        e.name = "hook-" + id;
        e.integrationType = IntegrationEntity.IntegrationType.SLACK;
        e.integrationStatus = status;
        e.enabled = enabled;
        return e;
    }

    @Test
    @DisplayName("集成：getIntegrationStats 按状态与类型聚合")
    void integrationStatsAggregation() {
        lenient().when(integrationRepo.findByGameId("game_1")).thenReturn(List.of(
            integration("i_active", IntegrationEntity.IntegrationStatus.ACTIVE, true),
            integration("i_failed", IntegrationEntity.IntegrationStatus.FAILED, true),
            integration("i_inactive", IntegrationEntity.IntegrationStatus.INACTIVE, false)));

        Map<String, Object> stats = integrationService.getIntegrationStats("game_1");

        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("active"));
        assertEquals(1L, stats.get("failed"));
        assertEquals(1L, stats.get("inactive"));
        assertEquals(Map.of("SLACK", 3L), stats.get("byType"));
    }

    @Test
    @DisplayName("集成：getCallStats 计算成功率与平均耗时")
    void integrationCallStatsSuccessRate() {
        lenient().when(integrationLogRepo.countCallsSince(eq("i_1"), any())).thenReturn(10L);
        lenient().when(integrationLogRepo.countSuccessCallsSince(eq("i_1"), any())).thenReturn(8L);
        lenient().when(integrationLogRepo.countFailedCallsSince(eq("i_1"), any())).thenReturn(2L);
        lenient().when(integrationLogRepo.averageDurationSince(eq("i_1"), any())).thenReturn(120L);

        Map<String, Object> stats = integrationService.getCallStats("i_1", LocalDateTime.now().minusHours(1));

        assertEquals(10L, stats.get("totalCalls"));
        assertEquals(8L, stats.get("successCalls"));
        assertEquals(2L, stats.get("failedCalls"));
        assertEquals(0.8, (double) stats.get("successRate"), 0.0001);
        assertEquals(120L, stats.get("averageDurationMs"));
    }

    @Test
    @DisplayName("集成：retryFailedIntegrations 对可重试集成触发验证")
    void integrationRetryTriggersVerify() {
        IntegrationEntity retryable = integration("i_retry", IntegrationEntity.IntegrationStatus.FAILED, true);
        // 探测 stub 的 anyString() 不匹配 null——实体必须带 endpointUrl
        retryable.endpointUrl = "https://hooks.example.com/x";
        lenient().when(integrationRepo.findRetryable()).thenReturn(List.of(retryable));
        lenient().when(integrationRepo.findById("i_retry")).thenReturn(Optional.of(retryable));
        lenient().when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后 executeHealthCheck 落库探测日志——不 stub 则 save 返回 null → isSuccess NPE → 误判 FAILED
        lenient().when(integrationLogRepo.save(any(IntegrationLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        // 真探测：stub 探测模板成功（真化后重试成功 = URL 可达，不再无条件洗白）
        RestTemplate probe = mock(RestTemplate.class);
        lenient().when(probe.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
        IntegrationService spied = spy(integrationService);
        doReturn(probe).when(spied).restTemplateFor(any(IntegrationEntity.class));

        assertDoesNotThrow(() -> spied.retryFailedIntegrations());

        // verifyIntegration 内部两次 save（置 VERIFYING + 置 ACTIVE）
        verify(integrationRepo, times(2)).save(retryable);
        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, retryable.integrationStatus);
    }

}
