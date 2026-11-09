package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.InternalApiKeyController;
import io.oddsmaker.control.api.MailController;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.api.RedeemCodeController;
import io.oddsmaker.control.api.RiskRuleController;
import io.oddsmaker.control.api.RoleAssignmentController;
import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.dto.UserDTO;
import io.oddsmaker.control.exception.RateLimitExceededException;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.MailClaimEntity;
import io.oddsmaker.control.jpa.PermissionEntity;
import io.oddsmaker.control.jpa.PermissionRepo;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.RoleEntity;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.jpa.UserRoleRepo;
import io.oddsmaker.control.security.AccessGuard;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮·LAY-003 拆分）：PermissionService、RiskRuleService、DTO updateEntity、内部 API Key/Mail/Redeem/角色分配/风控规则控制器、RateLimitExceededException。与 FinalSweep2Test / ServicesFinalSweepTest / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾（第四轮）·权限/DTO/控制器/异常域")
class FinalSweep3DtoApiTest {

// ==================== 共享 Mock（原类逐字搬运） ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserRepo userRepo;

    @Mock
    private RoleRepo roleRepo;

    @Mock
    private PermissionRepo permissionRepo;

    @Mock
    private UserRoleRepo userRoleRepo;

    @InjectMocks
    private PermissionService permissionService;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private GameRepo gameRepo;

    @InjectMocks
    private RiskRuleService riskRuleService;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private RoleEntity role(String id, String... permissionIds) {
        RoleEntity r = new RoleEntity();
        r.id = id;
        r.enabled = true;
        Set<PermissionEntity> perms = new java.util.HashSet<>();
        for (String pid : permissionIds) {
            PermissionEntity p = new PermissionEntity();
            p.id = pid;
            perms.add(p);
        }
        r.permissions = perms;
        return r;
    }

    private UserRoleEntity ur(String roleId, String gameId) {
        UserRoleEntity ur = new UserRoleEntity();
        ur.userId = "u1";
        ur.roleId = roleId;
        ur.gameId = gameId;
        return ur;
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 游戏级角色授权")
    void testHasGamePermissionViaGameRole() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of(ur("r1", "g1")));
        when(roleRepo.findById("r1")).thenReturn(Optional.of(role("r1", "game:read")));
        assertTrue(permissionService.hasGamePermission("u1", "g1", "game:read"));
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 全局角色兜底授权")
    void testHasGamePermissionViaGlobalRole() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of());
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(ur("r2", null)));
        when(roleRepo.findById("r2")).thenReturn(Optional.of(role("r2", "game:read")));
        assertTrue(permissionService.hasGamePermission("u1", "g1", "game:read"));
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 失效分配被跳过")
    void testHasGamePermissionSkipsInvalidAssignments() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        UserRoleEntity expired = ur("r1", "g1");
        expired.expiresAt = LocalDateTime.now().minusDays(1);
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of(expired));
        UserRoleEntity expiredGlobal = ur("r2", null);
        expiredGlobal.enabled = false;
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(expiredGlobal));
        assertFalse(permissionService.hasGamePermission("u1", "g1", "game:read"));

        when(userRepo.findById("u0")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> permissionService.hasGamePermission("u0", "g1", "game:read"));
    }

    @Test
    @DisplayName("RiskRuleService.list Specification 分页查询")
    void testRiskRuleList() {
        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "rr1";
        rule.gameId = "g1";
        when(riskRuleRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(rule)));

        Page<RiskRuleEntity> page = riskRuleService.list("g1", "env1", "ACTIVE", "THRESHOLD", "q", 0, 10);
        assertEquals(1, page.getTotalElements());
        assertEquals("rr1", page.getContent().get(0).id);
    }

    @Test
    @DisplayName("EventPropertyDefinitionDTO.updateEntity 全字段映射")
    void testEventPropertyDefinitionUpdateEntity() {
        EventPropertyDefinitionEntity e = new EventPropertyDefinitionEntity();
        EventPropertyDefinitionDTO d = new EventPropertyDefinitionDTO();
        d.displayName = "DN";
        d.description = "D";
        d.type = EventPropertyDefinitionEntity.PropertyType.FLOAT;
        d.arrayElementType = "string";
        d.required = true;
        d.defaultValue = "1.0";
        d.allowedValues = "[1,2]";
        d.cardinalityLimit = 50;
        d.minValue = 0.1;
        d.maxValue = 9.9;
        d.minLength = 1;
        d.maxLength = 10;
        d.regexPattern = "^a";
        d.isPii = true;
        d.piiType = "email";
        d.isIndexed = false;
        d.propertyGroup = "core";
        d.displayOrder = 3;
        d.status = EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED;
        d.updateEntity(e);
        assertEquals("DN", e.displayName);
        assertEquals("D", e.description);
        assertEquals(EventPropertyDefinitionEntity.PropertyType.FLOAT, e.type);
        assertEquals("string", e.arrayElementType);
        assertEquals(Boolean.TRUE, e.required);
        assertEquals("1.0", e.defaultValue);
        assertEquals("[1,2]", e.allowedValues);
        assertEquals(50, e.cardinalityLimit);
        assertEquals(0.1, e.minValue);
        assertEquals(9.9, e.maxValue);
        assertEquals(1, e.minLength);
        assertEquals(10, e.maxLength);
        assertEquals("^a", e.regexPattern);
        assertEquals(Boolean.TRUE, e.isPii);
        assertEquals("email", e.piiType);
        assertEquals(Boolean.FALSE, e.isIndexed);
        assertEquals("core", e.propertyGroup);
        assertEquals(3, e.displayOrder);
        assertEquals(EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED, e.status);
    }

    @Test
    @DisplayName("UserDTO.updateEntity 全字段映射")
    void testUserUpdateEntity() {
        UserEntity e = new UserEntity();
        UserDTO d = new UserDTO();
        d.email = "u@x.io";
        d.name = "N";
        d.displayName = "DN";
        d.avatarUrl = "http://a";
        d.globalRole = UserEntity.GlobalRole.OPERATOR;
        d.status = UserEntity.UserStatus.INACTIVE;
        d.company = "C";
        d.title = "T";
        d.phone = "+1";
        d.timeZone = "UTC";
        d.locale = "zh-CN";
        d.notificationEmail = false;
        d.notificationSms = true;
        d.dashboardTheme = "dark";
        d.updateEntity(e);
        assertEquals("u@x.io", e.email);
        assertEquals("N", e.name);
        assertEquals("DN", e.displayName);
        assertEquals("http://a", e.avatarUrl);
        assertEquals(UserEntity.GlobalRole.OPERATOR, e.globalRole);
        assertEquals(UserEntity.UserStatus.INACTIVE, e.status);
        assertEquals("C", e.company);
        assertEquals("T", e.title);
        assertEquals("+1", e.phone);
        assertEquals("UTC", e.timeZone);
        assertEquals("zh-CN", e.locale);
        assertEquals(Boolean.FALSE, e.notificationEmail);
        assertEquals(Boolean.TRUE, e.notificationSms);
        assertEquals("dark", e.dashboardTheme);
    }

    @Test
    @DisplayName("StorageProfileDTO.updateEntity 全字段映射")
    void testStorageProfileUpdateEntity() {
        StorageProfileEntity e = new StorageProfileEntity();
        StorageProfileDTO d = new StorageProfileDTO();
        d.name = "eu";
        d.displayName = "EU";
        d.description = "D";
        d.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        d.kafkaCluster = "k";
        d.clickhouseCluster = "ch";
        d.redisCluster = "r";
        d.archiveBucket = "b";
        d.active = false;
        d.updateEntity(e);
        assertEquals("eu", e.name);
        assertEquals("EU", e.displayName);
        assertEquals("D", e.description);
        assertEquals(StorageProfileEntity.IsolationStrategy.DEDICATED, e.isolationStrategy);
        assertEquals("k", e.kafkaCluster);
        assertEquals("ch", e.clickhouseCluster);
        assertEquals("r", e.redisCluster);
        assertEquals("b", e.archiveBucket);
        assertEquals(Boolean.FALSE, e.active);
    }

    @Test
    @DisplayName("EventDefinitionDTO.updateEntity 全字段映射")
    void testEventDefinitionUpdateEntity() {
        EventDefinitionEntity e = new EventDefinitionEntity();
        EventDefinitionDTO d = new EventDefinitionDTO();
        d.displayName = "DN";
        d.description = "D";
        d.category = "eco";
        d.subcategory = "pay";
        d.identity = EventDefinitionEntity.EventIdentity.USER;
        d.requireUserId = true;
        d.requireSessionId = true;
        d.requirePlayerId = true;
        d.validationRules = "{}";
        d.examplePayload = "{}";
        d.docUrl = "http://doc";
        d.importance = EventDefinitionEntity.Importance.CRITICAL;
        d.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
        d.updateEntity(e);
        assertEquals("DN", e.displayName);
        assertEquals("D", e.description);
        assertEquals("eco", e.category);
        assertEquals("pay", e.subcategory);
        assertEquals(EventDefinitionEntity.EventIdentity.USER, e.identity);
        assertEquals(Boolean.TRUE, e.requireUserId);
        assertEquals(Boolean.TRUE, e.requireSessionId);
        assertEquals(Boolean.TRUE, e.requirePlayerId);
        assertEquals("{}", e.validationRules);
        assertEquals("{}", e.examplePayload);
        assertEquals("http://doc", e.docUrl);
        assertEquals(EventDefinitionEntity.Importance.CRITICAL, e.importance);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DEPRECATED, e.status);
    }

    @Test
    @DisplayName("InternalApiKeyController.getActiveKey 命中/未命中")
    void testInternalApiKeyController() {
        ControlService cs = mock(ControlService.class);
        InternalApiKeyController controller = new InternalApiKeyController(cs);
        Models.InternalApiKeyResp resp = new Models.InternalApiKeyResp();
        resp.apiKey = "pk1";
        when(cs.getActiveKeyForGateway("pk1")).thenReturn(resp);
        ResponseEntity<Models.InternalApiKeyResp> ok = controller.getActiveKey("pk1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("pk1", ok.getBody().apiKey);

        when(cs.getActiveKeyForGateway("pk0")).thenReturn(null);
        assertEquals(404, controller.getActiveKey("pk0").getStatusCode().value());
    }

    @Test
    @DisplayName("MailController.claim 成功与 409")
    void testMailControllerClaim() {
        MailService mailService = mock(MailService.class);
        AccessGuard guard = mock(AccessGuard.class);
        MailController controller = new MailController(mailService, guard);

        MailClaimEntity claim = new MailClaimEntity();
        claim.id = "c1";
        claim.mailId = "m1";
        claim.playerKey = "k";
        claim.claimedAttachments = "sword";
        claim.claimedAt = LocalDateTime.now();
        when(mailService.claim("m1", "k")).thenReturn(claim);
        ResponseEntity<Map<String, Object>> ok = controller.claim("m1", "g1", "k");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("c1", ok.getBody().get("claimId"));
        assertEquals("sword", ok.getBody().get("attachments"));

        when(mailService.claim("m2", "k")).thenReturn(null);
        ResponseEntity<Map<String, Object>> conflict = controller.claim("m2", "g1", "k");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("mail_not_claimable", conflict.getBody().get("error"));
    }

    @Test
    @DisplayName("RedeemCodeController.redeem 成功与 409")
    void testRedeemCodeControllerRedeem() {
        RedeemCodeService redeemCodeService = mock(RedeemCodeService.class);
        AccessGuard guard = mock(AccessGuard.class);
        RedeemCodeController controller = new RedeemCodeController(redeemCodeService, guard);

        RedeemRecordEntity record = new RedeemRecordEntity();
        record.id = "rr1";
        record.batchId = "b1";
        record.gameId = "g1";
        record.playerKey = "k";
        record.code = "CODE";
        record.reward = "gold";
        record.redeemedAt = LocalDateTime.now();
        when(redeemCodeService.redeem("g1", "CODE", "k")).thenReturn(record);
        ResponseEntity<Map<String, Object>> ok = controller.redeem("g1", "k", "CODE");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("rr1", ok.getBody().get("recordId"));
        assertEquals("gold", ok.getBody().get("reward"));

        when(redeemCodeService.redeem("g1", "DUP", "k"))
            .thenThrow(new IllegalStateException("already redeemed"));
        ResponseEntity<Map<String, Object>> conflict = controller.redeem("g1", "k", "DUP");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("already redeemed", conflict.getBody().get("error"));
    }

    @Test
    @DisplayName("RoleAssignmentController.validateScope 环境维度缺 gameId 抛异常 / 未知角色拒绝 / 合法分配")
    void testRoleAssignmentValidateScope() {
        PermissionService ps = mock(PermissionService.class);
        AccessGuard guard = mock(AccessGuard.class);
        AuditLogService audit = mock(AuditLogService.class);
        RoleRepo roles = mock(RoleRepo.class);
        RoleEntity enabledRole = new RoleEntity();
        enabledRole.id = "role_viewer";
        when(roles.findByEnabledTrue()).thenReturn(List.of(enabledRole));
        RoleAssignmentController controller = new RoleAssignmentController(ps, guard, audit, roles);

        RoleAssignmentController.AssignReq bad = new RoleAssignmentController.AssignReq();
        bad.roleId = "role_viewer";
        bad.environment = "prod";
        bad.gameId = null;
        assertThrows(IllegalArgumentException.class, () -> controller.assign("u1", bad));

        // 白名单动态取启用角色：roles 表不存在的 id 拒绝
        RoleAssignmentController.AssignReq badRole = new RoleAssignmentController.AssignReq();
        badRole.roleId = "super_admin";
        assertThrows(IllegalArgumentException.class, () -> controller.assign("u1", badRole));

        RoleAssignmentController.AssignReq good = new RoleAssignmentController.AssignReq();
        good.roleId = "role_viewer";
        good.gameId = "g1";
        UserRoleEntity assignment = new UserRoleEntity();
        assignment.userId = "u1";
        assignment.roleId = "role_viewer";
        assignment.gameId = "g1";
        when(ps.assignRole(eq("u1"), eq("role_viewer"), eq("g1"), isNull(), anyString()))
            .thenReturn(assignment);
        ResponseEntity<Map<String, Object>> ok = controller.assign("u1", good);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("g1", ok.getBody().get("gameId"));
        assertEquals("", ok.getBody().get("environment"));
        assertEquals(Boolean.TRUE, ok.getBody().get("enabled"));
    }

    @Test
    @DisplayName("RiskRuleController.toResp 经 get 映射 / 未找到 404")
    void testRiskRuleControllerToResp() {
        RiskRuleService rrs = mock(RiskRuleService.class);
        AccessGuard guard = mock(AccessGuard.class);
        RiskRuleController controller = new RiskRuleController(rrs, guard);

        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "rr1";
        rule.gameId = "g1";
        rule.name = "rule";
        rule.status = RiskRuleEntity.RuleStatus.ACTIVE;
        rule.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        when(rrs.get("rr1")).thenReturn(rule);
        ResponseEntity<RiskRuleController.RiskRuleResp> ok = controller.get("rr1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("rr1", ok.getBody().id);
        assertEquals("THRESHOLD", ok.getBody().type);
        assertEquals(Boolean.TRUE, ok.getBody().enabled);

        when(rrs.get("missing")).thenReturn(null);
        assertEquals(404, controller.get("missing").getStatusCode().value());
    }

    @Test
    @DisplayName("RateLimitExceededException 两个构造器与访问器")
    void testRateLimitExceededException() {
        RateLimitExceededException simple = new RateLimitExceededException("too fast");
        assertEquals("too fast", simple.getMessage());
        assertNull(simple.getUserId());
        assertNull(simple.getEndpoint());
        assertEquals(0, simple.getRetryAfter());

        RateLimitExceededException full = new RateLimitExceededException("slow down", "u1", "/api/x", 30L);
        assertEquals("slow down", full.getMessage());
        assertEquals("u1", full.getUserId());
        assertEquals("/api/x", full.getEndpoint());
        assertEquals(30L, full.getRetryAfter());
    }
}
