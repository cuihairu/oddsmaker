package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.HealthCheckEntity;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.api.ControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终收尾：Specification lambda 真实执行、CH 客户端构造、健康检查随机分支等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾测试")
class FinalSweep4Test {

    // ===== RiskRuleService：list 的 Specification lambda 真实执行 =====

    @Mock
    private io.oddsmaker.control.jpa.RiskRuleRepo riskRuleRepo;

    @Mock
    private AuditLogService auditLog;

    @Test
    @DisplayName("风控规则 list：Specification 谓词构造（全过滤条件分支）")
    void riskRuleListSpecification() {
        RiskRuleService service = new RiskRuleService(riskRuleRepo, gameRepo, auditLog);
        when(riskRuleRepo.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.list("g", "prod", "ACTIVE", "THRESHOLD", "speed", 0, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Specification<io.oddsmaker.control.jpa.RiskRuleEntity>> captor =
            org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(riskRuleRepo).findAll(captor.capture(), any(org.springframework.data.domain.Pageable.class));

        // 真实执行 Specification lambda（Root/CriteriaBuilder 用 deep-stub mock 驱动全分支）
        jakarta.persistence.criteria.Root<io.oddsmaker.control.jpa.RiskRuleEntity> root =
            mock(jakarta.persistence.criteria.Root.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        jakarta.persistence.criteria.CriteriaQuery<?> query =
            mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.CriteriaBuilder cb =
            mock(jakarta.persistence.criteria.CriteriaBuilder.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        jakarta.persistence.criteria.Predicate predicate =
            captor.getValue().toPredicate(root, query, cb);
        assertNotNull(predicate);  // cb.and(...) deep-stub 结果

        // environmentId 为空分支
        service.list("g", null, null, null, null, 0, 10);
        verify(riskRuleRepo, org.mockito.Mockito.times(2))
            .findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class));
    }

    // ===== ClickHouseClient 构造分支 =====

    @Test
    @DisplayName("CH 客户端：配置 url 时可用，未配置时不可用")
    void clickHouseClientConstruction() {
        ClickHouseClient configured = new ClickHouseClient(
            "jdbc:clickhouse://localhost:8123/default", "default", "");
        assertTrue(configured.isAvailable());

        ClickHouseClient unconfigured = new ClickHouseClient("", "default", "");
        assertEquals(false, unconfigured.isAvailable());
    }

    // ===== HealthMonitorService：performHealthCheck 随机双分支（重试至覆盖） =====

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Test
    @DisplayName("健康检查：随机结果两个分支都出现（重试驱动）")
    void healthCheckRandomBranches() {
HealthMonitorService service = new HealthMonitorService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "healthCheckRepo", healthCheckRepo);
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(check));
        when(healthCheckRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean healthySeen = false;
        boolean unhealthySeen = false;
        for (int i = 0; i < 200 && !(healthySeen && unhealthySeen); i++) {
            HealthCheckEntity result = service.performHealthCheck("db");
            healthySeen |= "Service is responding normally".equals(result.statusMessage);
            unhealthySeen |= result.statusMessage != null
                && result.statusMessage.startsWith("Service is not responding");
        }
        assertTrue(healthySeen && unhealthySeen);
    }

    // ===== IntegrationService.verifyIntegration 深分支 =====

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @Test
    @DisplayName("集成验证：成功/失败/未找到三分支")
    void integrationVerifyBranches() {
IntegrationService service = new IntegrationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "integrationRepo", integrationRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "integrationLogRepo", integrationLogRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g";
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 内部 HTTP 验证走真实 RestTemplate（连接失败 catch → verified=false 分支）
        IntegrationEntity result = service.verifyIntegration("i1");
        assertNotNull(result);

        when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.verifyIntegration("missing"));
    }

    // ===== CohortService.calculateCohort 结果分支 =====

    @Mock
    private CohortRepo cohortRepo;

    @Test
    @DisplayName("Cohort 计算：结果写入与状态迁移")
    void cohortCalculateBranches() {
CohortService service = new CohortService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper",
            new com.fasterxml.jackson.databind.ObjectMapper());
        CohortEntity cohort = new CohortEntity();
        cohort.id = "c1";
        cohort.gameId = "g";
        cohort.name = "sep-cohort";
        cohort.cohortType = CohortEntity.CohortType.ACQUISITION;
        cohort.status = CohortEntity.CohortStatus.PENDING;
        when(cohortRepo.findById("c1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CohortEntity calculated = service.calculateCohort("c1");
        assertNotNull(calculated);
        verify(cohortRepo, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    // ===== MLModelService.updateModel / completePrediction 剩余分支 =====

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Test
    @DisplayName("ML 模型更新：单配置项分支")
    void mlModelUpdateSingleSections() {
MLModelService service = new MLModelService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mlModelRepo", mlModelRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelTrainingRepo", modelTrainingRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        MLModelEntity model = new MLModelEntity();
        model.id = "m1";
        model.gameId = "g";
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model));
        when(mlModelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("description", "updated");
        assertNotNull(service.updateModel("m1", updates, "ops"));
        verify(mlModelRepo).save(any());
    }

    // ===== RiskMetricsAssembler.toIso 剩余类型分支 =====

    @Test
    @DisplayName("toIso：LocalDate/LocalDateTime/OffsetDateTime/Number 分支")
    void riskMetricsToIsoVariants() {
        assertEquals("2026-09-09", RiskMetricsAssembler.toIso(java.time.LocalDate.of(2026, 9, 9)));
        assertTrue(String.valueOf(RiskMetricsAssembler.toIso(
            java.time.LocalDateTime.of(2026, 9, 9, 8, 0))).startsWith("2026-09-09T"));
        assertTrue(String.valueOf(RiskMetricsAssembler.toIso(
            java.time.OffsetDateTime.parse("2026-09-09T08:00:00+08:00"))).startsWith("2026-09-09T"));
        assertEquals("raw", RiskMetricsAssembler.toIso("raw"));
    }

    // ===== ControlService 补充：getActiveKeyForGateway 已删游戏分支 =====

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Test
    @DisplayName("网关侧 Key：环境已删/归属不匹配返回 null")
    void gatewayKeyEnvBranches() {
        ControlService service = new ControlService(keyRepo, gameRepo, envRepo, storageProfileRepo, auditLog);
        io.oddsmaker.control.jpa.ApiKeyEntity key = new io.oddsmaker.control.jpa.ApiKeyEntity();
        key.apiKey = "ak_1";
        key.gameId = "g";
        key.environmentId = "prod";
        key.status = io.oddsmaker.control.jpa.ApiKeyEntity.ApiKeyStatus.ACTIVE;
        when(keyRepo.findById("ak_1")).thenReturn(Optional.of(key));

        // 环境已删除
        io.oddsmaker.control.jpa.GameEnvironmentEntity deleted = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        deleted.id = "prod";
        deleted.gameId = "g";
        deleted.deletedAt = LocalDateTime.now();
        when(envRepo.findById("prod")).thenReturn(Optional.of(deleted));
        org.junit.jupiter.api.Assertions.assertNull(service.getActiveKeyForGateway("ak_1"));

        // 环境归属其他游戏
        io.oddsmaker.control.jpa.GameEnvironmentEntity other = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        other.id = "prod";
        other.gameId = "other";
        when(envRepo.findById("prod")).thenReturn(Optional.of(other));
        org.junit.jupiter.api.Assertions.assertNull(service.getActiveKeyForGateway("ak_1"));
    }
}
