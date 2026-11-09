package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：PipelineService 执行与调度分支，IntegrationService 校验成功与重试清理分支。与 PipelineServiceDeepTest / IntegrationServiceDeepTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：管道与集成")
class FinalSweep6PipelineIntegrationTest {

    @Mock
    private AuditLogService auditLog;

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    private PipelineEntity activePipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g";
        pipeline.pipelineName = "pipe";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        return pipeline;
    }

    @Test
    @DisplayName("executePipeline：成功路径与规则保存失败→重试分支")
    void pipelineExecuteBranches() {
        PipelineService service = new PipelineService();
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", pipelineJobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", dataQualityRuleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        PipelineJobEntity job = service.executePipeline("p1", "tester");
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, job.jobStatus);

        // 质量规则保存抛异常 → catch → fail + retry
        DataQualityRuleEntity rule = new DataQualityRuleEntity();
        rule.ruleName = "r";
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(rule));
        when(dataQualityRuleRepo.save(any(DataQualityRuleEntity.class)))
            .thenThrow(new RuntimeException("rule save failed"));

        PipelineJobEntity failedJob = service.executePipeline("p1", "tester");
        assertEquals(PipelineJobEntity.JobStatus.RETRYING, failedJob.jobStatus);
        assertEquals(1, pipeline.failureCount);
    }

    @Test
    @DisplayName("executeScheduledPipelines：成功/内层失败/外层异常三分支")
    void pipelineScheduledBranches() {
        PipelineService service = new PipelineService();
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", pipelineJobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", dataQualityRuleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "scheduleEnabled", true);  // 调度开关默认关闭

        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class))).thenReturn(List.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        service.executeScheduledPipelines();
        verify(pipelineJobRepo, times(2)).save(any(PipelineJobEntity.class));

        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenThrow(new RuntimeException("save boom"));
        service.executeScheduledPipelines();  // 内层 catch

        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("query boom"));
        service.executeScheduledPipelines();  // 外层 catch
    }

    @Test
    @DisplayName("verifyIntegration：健康检查成功激活分支")
    void integrationVerifySuccessBranch() {
        IntegrationService service = new IntegrationService();
        ReflectionTestUtils.setField(service, "integrationRepo", integrationRepo);
        ReflectionTestUtils.setField(service, "integrationLogRepo", integrationLogRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g";
        integration.integrationType = IntegrationEntity.IntegrationType.WEBHOOK;
        // 探测 stub 的 anyString() 不匹配 null——实体必须带 endpointUrl
        integration.endpointUrl = "https://hooks.example.com/x";
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真化后 executeHealthCheck 落库探测日志——不 stub 则 save 返回 null → isSuccess NPE → 误判 FAILED
        when(integrationLogRepo.save(any(IntegrationLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // 真探测：stub 探测模板成功（不依赖真网络）
        RestTemplate probe = mock(RestTemplate.class);
        when(probe.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
        IntegrationService spied = spy(service);
        doReturn(probe).when(spied).restTemplateFor(any(IntegrationEntity.class));

        IntegrationEntity result = spied.verifyIntegration("i1");

        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, result.integrationStatus);
    }

    @Test
    @DisplayName("retryFailedIntegrations/cleanupExpiredLogs：各三分支")
    void integrationRetryAndCleanupBranches() {
        IntegrationService service = new IntegrationService();
        ReflectionTestUtils.setField(service, "integrationRepo", integrationRepo);
        ReflectionTestUtils.setField(service, "integrationLogRepo", integrationLogRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        IntegrationEntity retryable = new IntegrationEntity();
        retryable.id = "i9";
        when(integrationRepo.findRetryable()).thenReturn(List.of(retryable));
        when(integrationRepo.findById("i9")).thenReturn(Optional.empty());
        service.retryFailedIntegrations();  // IAE → 内层 catch

        when(integrationRepo.findRetryable()).thenThrow(new RuntimeException("boom"));
        service.retryFailedIntegrations();  // 外层 catch

        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(5);
        service.cleanupExpiredLogs();
        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(0);
        service.cleanupExpiredLogs();
        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("boom"));
        service.cleanupExpiredLogs();
        verify(integrationLogRepo, times(3)).deleteExpired(any(LocalDateTime.class));
    }

}
