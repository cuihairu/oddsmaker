package io.oddsmaker.control.security;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.PermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 安全组件尾部覆盖冲刺第二轮：
 * - AuditLogInterceptor：extractResourceInfo 路径/参数提取与 getClientIp 多级代理头回退
 * - AccessGuard：canAccessGame 未认证/特权直通/委托分支
 * - PermissionAspect：extractParameter 的 @PathVariable 显式命名/隐式参数名/null/未命中分支与环境级权限
 * - jpa 嵌套枚举静态初始化（<clinit>）
 * 与 AccessGuardTest / SecurityComponentsTest / AuditLogInterceptorTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("安全组件尾部覆盖冲刺第二轮")
class SecuritySweep2Test {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private AuditLogInterceptor interceptor;

    @InjectMocks
    private PermissionAspect aspect;

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

    // ===== AuditLogInterceptor：getClientIp / extractResourceInfo =====

    @Auditable(action = AuditLogEntity.AuditAction.UPDATE, resourceType = "game")
    public void auditedHandler() {
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private AuditLogEntity runAudit(MockHttpServletRequest request, int status) throws Exception {
        HandlerMethod handler = new HandlerMethod(this,
            SecuritySweep2Test.class.getMethod("auditedHandler"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogService).log(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("getClientIp：X-Forwarded-For 多代理取首跳并 trim；无 api 路径不提取资源")
    void clientIpFirstHopFromForwardedFor() throws Exception {
        MockHttpServletRequest request = request("/internal/audit");
        request.addHeader("X-Forwarded-For", " 203.0.113.5 , 198.51.100.7 ");
        AuditLogEntity log = runAudit(request, 200);
        assertEquals("203.0.113.5", log.ipAddress);
        assertNull(log.resourceId);
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, log.status);
        assertNotNull(log.requestId);
    }

    @Test
    @DisplayName("getClientIp：unknown 头逐级回退到 X-Real-IP")
    void clientIpFallsBackToRealIp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("auditor", "pw", java.util.List.of()));
        MockHttpServletRequest request = request("/api/games/game-9");
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Real-IP", "198.18.0.9");
        AuditLogEntity log = runAudit(request, 200);
        assertEquals("198.18.0.9", log.ipAddress);
        assertEquals("auditor", log.username);
    }

    @Test
    @DisplayName("getClientIp：空 X-Real-IP 回退到 Proxy-Client-IP")
    void clientIpFallsBackToProxyClientIp() throws Exception {
        MockHttpServletRequest request = request("/api/games/game-9");
        request.addHeader("X-Real-IP", "");
        request.addHeader("Proxy-Client-IP", "10.0.0.8");
        assertEquals("10.0.0.8", runAudit(request, 200).ipAddress);
    }

    @Test
    @DisplayName("getClientIp：unknown Proxy-Client-IP 回退到 WL-Proxy-Client-IP")
    void clientIpFallsBackToWlProxyClientIp() throws Exception {
        MockHttpServletRequest request = request("/api/games/game-9");
        request.addHeader("Proxy-Client-IP", "unknown");
        request.addHeader("WL-Proxy-Client-IP", "10.0.0.7");
        assertEquals("10.0.0.7", runAudit(request, 200).ipAddress);
    }

    @Test
    @DisplayName("getClientIp：全部头缺失时回退 remoteAddr")
    void clientIpFallsBackToRemoteAddr() throws Exception {
        assertEquals("127.0.0.1", runAudit(request("/api/games/game-9"), 200).ipAddress);
    }

    @Test
    @DisplayName("extractResourceInfo：api 路径提取类型与 ID，gameId/environment 参数填充，4xx 记 FAILURE")
    void resourceInfoExtractedFromPathAndParams() throws Exception {
        MockHttpServletRequest request = request("/api/games/game-9/flags");
        request.addParameter("gameId", "g-77");
        request.addParameter("environment", "prod");
        request.addHeader("User-Agent", "JUnit-Agent");
        AuditLogEntity log = runAudit(request, 403);
        assertEquals("games", log.resourceType);
        assertEquals("game-9", log.resourceId);
        assertEquals("g-77", log.gameId);
        assertEquals("prod", log.environment);
        assertEquals("JUnit-Agent", log.userAgent);
        assertEquals(AuditLogEntity.AuditStatus.FAILURE, log.status);
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

    // ===== PermissionAspect：extractParameter / checkPermission 环境级 =====

    @RequirePermission(value = "game:read", gameIdParam = "gameId", environmentParam = "env")
    public String envScoped(@PathVariable("gameId") String gameId, @PathVariable("env") String environment) {
        return "ok-env";
    }

    @RequirePermission(value = "game:read", gameIdParam = "gameId")
    public String implicitName(@PathVariable String gameId) {
        return "ok-implicit";
    }

    @RequirePermission(value = "game:read", gameIdParam = "missing")
    public String noMatch(String other) {
        return "ok-none";
    }

    private ProceedingJoinPoint joinPoint(Method method, Object... args) {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        lenient().when(signature.getMethod()).thenReturn(method);
        lenient().when(jp.getSignature()).thenReturn(signature);
        lenient().when(jp.getArgs()).thenReturn(args);
        return jp;
    }

    @Test
    @DisplayName("切面：@PathVariable 显式命名提取 gameId/env 走环境级权限")
    void aspectEnvironmentScopeViaExplicitPathVariables() throws Throwable {
        login("tester");
        ProceedingJoinPoint jp = joinPoint(
            SecuritySweep2Test.class.getMethod("envScoped", String.class, String.class), "g1", "prod");
        lenient().when(permissionService.hasEnvironmentPermission("tester", "g1", "prod", "game:read"))
            .thenReturn(true);
        lenient().when(jp.proceed()).thenReturn("ok-env");
        assertEquals("ok-env", aspect.checkPermission(jp));
    }

    @Test
    @DisplayName("切面：@PathVariable 空名回退参数名匹配（隐式 gameId）")
    void aspectImplicitParameterNameMatches() throws Throwable {
        login("tester");
        ProceedingJoinPoint jp = joinPoint(
            SecuritySweep2Test.class.getMethod("implicitName", String.class), "g7");
        lenient().when(permissionService.hasGamePermission("tester", "g7", "game:read")).thenReturn(true);
        lenient().when(jp.proceed()).thenReturn("ok-implicit");
        assertEquals("ok-implicit", aspect.checkPermission(jp));
    }

    @Test
    @DisplayName("切面：参数 null 与名称未命中提取为 null，回退全局权限")
    void aspectNullAndMissingParamsFallBackToGlobal() throws Throwable {
        login("tester");
        ProceedingJoinPoint nullArg = joinPoint(
            SecuritySweep2Test.class.getMethod("envScoped", String.class, String.class), null, "prod");
        lenient().when(permissionService.hasPermission("tester", "game:read")).thenReturn(true);
        lenient().when(nullArg.proceed()).thenReturn("ok-null");
        assertEquals("ok-null", aspect.checkPermission(nullArg));

        ProceedingJoinPoint missing = joinPoint(
            SecuritySweep2Test.class.getMethod("noMatch", String.class), "x");
        lenient().when(permissionService.hasPermission("tester", "game:read")).thenReturn(false);
        assertThrows(SecurityException.class, () -> aspect.checkPermission(missing));
    }

    // ===== jpa 嵌套枚举 <clinit> =====

    private static final String[] JPA_ENUMS = {
        "HealthMetricEntity$MetricType",
        "PlayerExportJobEntity$Status",
        "BlockListEntity$BlockType",
        "StandardEventTypeEntity$EventStatus",
        "SSOConfigEntity$SSOProtocol",
        "SSOConfigEntity$SSOStatus",
        "WebhookLogEntity$DeliveryStatus",
        "PerformanceMetricEntity$MetricType",
        "PerformanceMetricEntity$Severity",
        "ReportExecutionEntity$ExecutionStatus",
        "SessionAnalysisEntity$SessionQuality",
        "PlayerPaymentEntity$Status",
        "LevelProgressionEntity$ProgressionStatus",
        "SDKKeyEntity$SDKPlatform",
        "SDKKeyEntity$KeyStatus",
        "SDKKeyEntity$DeliveryMode",
        "PrivacyPolicyEntity$PolicyStatus",
        "PrivacyPolicyEntity$PiiHandling",
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
        "SamplingPolicyEntity$PolicyStatus",
        "SamplingPolicyEntity$SamplingStrategy",
        "RevenueAggregationEntity$RevenueType",
        "RevenueAggregationEntity$AggregationStatus",
        "RedeemCodeBatchEntity$CodeType",
        "RedeemCodeBatchEntity$Status",
        "RateLimitRuleEntity$RuleStatus",
        "ExportJobEntity$ExportStatus",
        "FlinkJobEntity$JobStatus",
        "FlinkJobEntity$JobType",
        "VirtualEconomyEntity$CurrencyType",
        "VirtualEconomyEntity$EconomyStatus",
        "SecurityPolicyEntity$PolicyType",
        "SecurityPolicyEntity$PolicyScope",
        "PiiFieldMappingEntity$PiiCategory",
        "PiiFieldMappingEntity$PiiSensitivity",
        "PiiFieldMappingEntity$PiiHandling",
        "PiiFieldMappingEntity$MappingStatus",
        "RoleEntity$RoleType",
        "AnnouncementEntity$Channel",
        "AnnouncementEntity$Status",
        "ApiKeyEntity$ApiKeyType",
        "ApiKeyEntity$ApiKeyStatus",
        "SecuritySessionEntity$SessionStatus",
        "SecuritySessionEntity$AuthMethod",
        "StorageProfileEntity$IsolationStrategy",
        "FunnelAnalysisEntity$FunnelType",
        "FunnelAnalysisEntity$AnalysisStatus",
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
        "EventSamplingRuleEntity$RuleStatus",
        "RiskCaseEntity$RiskLevel",
        "RiskCaseEntity$ActionType",
        "RiskCaseEntity$ExecutionStatus",
        "DataQualityRuleEntity$RuleType",
        "DataQualityRuleEntity$Severity",
        "DataQualityRuleEntity$RuleStatus",
        "StandardEventEntity$EventImportance",
        "StandardEventEntity$EventStatus",
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
        "UserInvitationEntity$InvitationStatus",
        "SocialAnalyticsEntity$SocialEventType",
        "PipelineEntity$PipelineType",
        "PipelineEntity$PipelineStatus",
        "GameEntity$GameGenre",
        "GameEntity$GamePlatform",
        "GameEntity$GameStatus",
        "RateLimitEntity$WindowType",
        "RateLimitEntity$Algorithm",
        "RateLimitEntity$Scope",
        "RetentionAnalysisEntity$RetentionType",
        "RetentionAnalysisEntity$CohortType",
        "RetentionAnalysisEntity$AnalysisStatus",
        "ModelTrainingEntity$TrainingStatus",
        "EventDefinitionEntity$EventIdentity",
        "EventDefinitionEntity$Importance",
        "EventDefinitionEntity$DefinitionStatus",
        "GameEnvironmentEntity$EnvironmentType",
        "GameEnvironmentEntity$EnvironmentStatus",
        "EventFieldEntity$FieldType",
        "EventFieldEntity$FieldPurpose",
        "EventFieldEntity$FieldStatus",
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
