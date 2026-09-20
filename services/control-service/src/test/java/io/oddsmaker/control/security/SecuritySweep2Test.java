package io.oddsmaker.control.security;

import io.oddsmaker.control.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 安全组件尾部覆盖冲刺第二轮：
 * - AccessGuard：canAccessGame 未认证/特权直通/委托分支
 * - jpa 嵌套枚举静态初始化（<clinit>）
 * 与 AccessGuardTest / SecurityComponentsTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("安全组件尾部覆盖冲刺第二轮")
class SecuritySweep2Test {

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private AccessGuard accessGuard;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            user, "n/a",
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    // ===== AccessGuard：canAccessGame =====

    @Test
    @DisplayName("canAccessGame：未认证（无认证/未认证令牌）返回 false 且不触达权限服务")
    void canAccessGameUnauthenticatedFalse() {
        assertFalse(accessGuard.canAccessGame("g1", "game:read"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("anon", "pw"));
        assertFalse(accessGuard.canAccessGame("g1", "game:read"));
        verifyNoInteractions(permissionService);
    }

    @Test
    @DisplayName("canAccessGame：ROLE_ADMIN / ROLE_INTERNAL 直通")
    void canAccessGamePrivilegedBypass() {
        login("root", "ROLE_ADMIN");
        assertTrue(accessGuard.canAccessGame("g1", "game:read"));
        login("gateway", "ROLE_INTERNAL");
        assertTrue(accessGuard.canAccessGame("g2", "game:read"));
        verifyNoInteractions(permissionService);
    }

    @Test
    @DisplayName("canAccessGame：普通用户委托 permissionService，结果透传")
    void canAccessGameDelegatesToPermissionService() {
        login("alice");
        lenient().when(permissionService.hasGamePermission("alice", "g1", "game:read")).thenReturn(true);
        lenient().when(permissionService.hasGamePermission("alice", "g2", "game:read")).thenReturn(false);
        assertTrue(accessGuard.canAccessGame("g1", "game:read"));
        assertFalse(accessGuard.canAccessGame("g2", "game:read"));
    }

    // ===== jpa 嵌套枚举 <clinit> =====

    private static final String[] JPA_ENUMS = {
        "HealthMetricEntity$MetricType",
        "PlayerExportJobEntity$Status",
        "BlockListEntity$BlockType",
        "SSOConfigEntity$SSOProtocol",
        "SSOConfigEntity$SSOStatus",
        "WebhookLogEntity$DeliveryStatus",
        "PerformanceMetricEntity$MetricType",
        "PerformanceMetricEntity$Severity",
        "ReportExecutionEntity$ExecutionStatus",
        "SessionAnalysisEntity$SessionQuality",
        "PlayerPaymentEntity$Status",
        "SDKKeyEntity$SDKPlatform",
        "SDKKeyEntity$KeyStatus",
        "SDKKeyEntity$DeliveryMode",
        "AuditLogEntity$AuditAction",
        "AuditLogEntity$AuditResult",
        "AuditLogEntity$AuditStatus",
        "IdentityEntity$IdentityType",
        "IdentityEntity$IdentityStatus",
        "CohortEntity$CohortType",
        "CohortEntity$CohortStatus",
        "SystemAlertEntity$Severity",
        "SystemAlertEntity$AlertType",
        "SystemAlertEntity$AlertStatus",
        "RiskRuleEntity$RuleCategory",
        "RiskRuleEntity$RuleType",
        "RiskRuleEntity$RiskLevel",
        "RiskRuleEntity$ActionType",
        "RiskRuleEntity$RuleStatus",
        "UserRoleEntity$RoleType",
        "UserRoleEntity$PermissionScope",
        "MLModelEntity$ModelType",
        "MLModelEntity$ModelStatus",
        "RevenueAnalysisEntity$RevenueType",
        "RedeemCodeBatchEntity$CodeType",
        "RedeemCodeBatchEntity$Status",
        "ExportJobEntity$ExportStatus",
        "FlinkJobEntity$JobStatus",
        "FlinkJobEntity$JobType",
        "SecurityPolicyEntity$PolicyType",
        "SecurityPolicyEntity$PolicyScope",
        "RoleEntity$RoleType",
        "AnnouncementEntity$Channel",
        "AnnouncementEntity$Status",
        "ApiKeyEntity$ApiKeyType",
        "ApiKeyEntity$ApiKeyStatus",
        "SecuritySessionEntity$SessionStatus",
        "SecuritySessionEntity$AuthMethod",
        "StorageProfileEntity$IsolationStrategy",
        "MaintenanceWindowEntity$MaintenanceType",
        "MaintenanceWindowEntity$MaintenanceStatus",
        "MaintenanceWindowEntity$ImpactScope",
        "SDKVersionEntity$SDKPlatform",
        "SDKVersionEntity$VersionStatus",
        "SDKVersionEntity$ChangeType",
        "TelemetryConfigEntity$ConfigType",
        "TelemetryConfigEntity$ConfigStatus",
        "EventPropertyDefinitionEntity$PropertyType",
        "EventPropertyDefinitionEntity$PropertyStatus",
        "MLModelPredictionEntity$PredictionStatus",
        "MLModelPredictionEntity$FeedbackType",
        "UserEntity$GlobalRole",
        "UserEntity$UserStatus",
        "UserEntity$UserRole",
        "FunnelConfigEntity$FunnelType",
        "ReviewQueueEntity$ReviewStatus",
        "ReportEntity$ReportType",
        "ReportEntity$ReportStatus",
        "SymbolMappingEntity$MappingStatus",
        "SymbolMappingEntity$FileType",
        "AdAnalysisEntity$AdNetwork",
        "AdAnalysisEntity$AdFormat",
        "TrackingPlanEntity$PlanStatus",
        "TrackingPlanEntity$ValidationStrictness",
        "HealthCheckEntity$CheckType",
        "HealthCheckEntity$HealthStatus",
        "MFAConfigEntity$MFAMethod",
        "MFAConfigEntity$MFAStatus",
        "IntegrationEntity$IntegrationType",
        "IntegrationEntity$AuthType",
        "IntegrationEntity$IntegrationStatus",
        "MailEntity$Scope",
        "MailEntity$Status",
        "PermissionEntity$PermissionType",
        "PermissionEntity$PermissionAction",
        "PermissionEntity$PermissionScope",
        "RiskCaseEntity$RiskLevel",
        "RiskCaseEntity$ActionType",
        "RiskCaseEntity$ExecutionStatus",
        "DataQualityRuleEntity$RuleType",
        "DataQualityRuleEntity$Severity",
        "DataQualityRuleEntity$RuleStatus",
        "IntegrationLogEntity$LogLevel",
        "IntegrationLogEntity$CallStatus",
        "RemoteConfigEntity$Status",
        "QuotaEntity$ResourceType",
        "SystemConfigEntity$ConfigType",
        "WebhookConfigEntity$WebhookStatus",
        "WebhookConfigEntity$AuthType",
        "PipelineJobEntity$JobStatus",
        "IdentityLinkEntity$LinkType",
        "IdentityLinkEntity$VerificationStatus",
        "IdentityLinkEntity$LinkStatus",
        "SocialAnalyticsEntity$SocialEventType",
        "PipelineEntity$PipelineType",
        "PipelineEntity$PipelineStatus",
        "GameEntity$GameGenre",
        "GameEntity$GamePlatform",
        "GameEntity$GameStatus",
        "RateLimitEntity$WindowType",
        "RateLimitEntity$Algorithm",
        "RateLimitEntity$Scope",
        "ModelTrainingEntity$TrainingStatus",
        "EventDefinitionEntity$EventIdentity",
        "EventDefinitionEntity$Importance",
        "EventDefinitionEntity$DefinitionStatus",
        "GameEnvironmentEntity$EnvironmentType",
        "GameEnvironmentEntity$EnvironmentStatus",
        "FeatureFlagEntity$FlagStatus",
        "FeatureFlagEntity$FlagType",
        "RedeemCodeEntity$Status",
    };

    @Test
    @DisplayName("jpa 嵌套枚举：Class.forName 触发 <clinit> 并校验 values()/valueOf()")
    void jpaEnumStaticInitializersTriggered() throws Exception {
        for (String name : JPA_ENUMS) {
            Class<?> clazz = assertDoesNotThrow(() -> Class.forName("io.oddsmaker.control.jpa." + name),
                "无法加载 " + name);
            assertTrue(clazz.isEnum(), name + " 应为枚举");
            Object[] values = (Object[]) clazz.getMethod("values").invoke(null);
            assertTrue(values.length > 0, name + " 应含至少一个常量");
            assertEquals(values[0], clazz.getMethod("valueOf", String.class)
                .invoke(null, values[0].toString()), name + " valueOf 应往返一致");
        }
    }
}
