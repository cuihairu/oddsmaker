package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.PipelineEntity;
import io.oddsmaker.control.jpa.PipelineJobEntity;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestRepo;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·组1终章：11 个 service 的剩余 branch-miss 对侧补测。
 * 行号锚定（对照 jacoco sourcefile mb>0 清单）：
 * RiskRuleService 39/42/47/50/53（list 过滤三形态）、113-129（update nullPatch 初始化器字段）；
 * GameService 467（环境名 null/非空）、602/603（状态转换各侧）、620（发布 name null/空白）；
 * ExperimentService 119/144（salt trim 侧）、153（draft 下改 config 不按 running 校验）、376（name null）；
 * WebhookService 281（patch.status 双侧）、360（authType==null 短路）；
 * PlayerErasureService 370（user_id/device_id case）、485（remaining==null）、643/659（CH 计数空结果）;
 * FlinkJobService 450（controlToken 非空但空白）；MailService 126（environmentId null/非 null）;
 * RedeemCodeService 99/101（sharedCode 非空但空白、codeLength>0）；CohortService 90/265/283；
 * CrashMetricsService 45（environment null）；PipelineService 176（isRetryable 短路侧）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("组1分支终章：11 个 service 剩余对侧")
class Group1FinalBranchTest {

    // ===== 共享 mock（同类型全类唯一，多 @InjectMocks 安全共享） =====

    @Mock
    private AuditLogService auditLog;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ClickHouseClient clickHouse;

    @Mock
    private RestTemplate restTemplate;

    // ===== RiskRuleService（构造器注入） =====

    @Mock
    private RiskRuleRepo ruleRepo;

    @InjectMocks
    private RiskRuleService riskRuleService;

    // ===== GameService（字段注入） =====

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @InjectMocks
    private GameService gameService;

    // ===== ExperimentService（构造器注入：experimentRepo/gameRepo/environmentRepo/objectMapper/auditLog） =====

    @Mock
    private ExperimentRepo experimentRepo;

    @InjectMocks
    private ExperimentService experimentService;

    // ===== WebhookService（字段注入）；erasure 内部依赖以独立 @Mock 提供 =====

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @InjectMocks
    private WebhookService webhookService;

    @Mock
    private WebhookService erasureWebhookSvc;

    // ===== PlayerErasureService（字段注入全家桶，照 GroupA 布局） =====

    @Mock
    private PlayerErasureRequestRepo requestRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @Mock
    private MailRepo mailRepo;

    @Mock
    private MailClaimRepo mailClaimRepo;

    @Mock
    private PlayerExportJobRepo exportJobRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private ReviewQueueRepo reviewQueueRepo;

    @Mock
    private BlockListRepo blockListRepo;

    @InjectMocks
    private PlayerErasureService erasureService;

    // ===== FlinkJobService（字段注入；flinkJobRepo/riskRuleRepo=ruleRepo/auditLog/objectMapper/flinkRestClient） =====

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private FlinkRestClient flinkRestClient;

    @InjectMocks
    private FlinkJobService flinkJobService;

    // ===== MailService（字段注入：mailRepo/claimRepo/gameRepo/auditLog） =====

    @InjectMocks
    private MailService mailService;

    // ===== RedeemCodeService（字段注入：batchRepo/codeRepo/recordRepo/gameRepo/auditLog） =====

    @Mock
    private RedeemCodeBatchRepo batchRepo;

    @Mock
    private RedeemCodeRepo codeRepo;

    @InjectMocks
    private RedeemCodeService redeemCodeService;

    // ===== CohortService（字段注入：cohortRepo/gameEnvironmentRepo/auditLog/objectMapper/clickHouse） =====

    @Mock
    private CohortRepo cohortRepo;

    @InjectMocks
    private CohortService cohortService;

    // ===== CrashMetricsService（构造器注入：clickHouse） =====

    @InjectMocks
    private CrashMetricsService crashMetricsService;

    // ===== PipelineService（字段注入：pipelineRepo/pipelineJobRepo/dataQualityRuleRepo/auditLog/clickHouse） =====

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @InjectMocks
    private PipelineService pipelineService;

    @BeforeEach
    void erasureAndCommonSetUp() {
        // PlayerErasureService 的 @Value 参数（无 Spring 环境须手动注入）
        ReflectionTestUtils.setField(erasureService, "identityExpansionLimit", 500);
        ReflectionTestUtils.setField(erasureService, "processingTimeoutMinutes", 60);
        ReflectionTestUtils.setField(erasureService, "mutationTimeoutSeconds", 0);
        ReflectionTestUtils.setField(erasureService, "mutationPollIntervalSeconds", 0);
        ReflectionTestUtils.setField(erasureService, "maxChRetries", 8);
        lenient().when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
        lenient().when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findByGameIdAndUserIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findByGameIdAndDeviceIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(clickHouse.isAvailable()).thenReturn(false);
    }

    // ===== RiskRuleService =====

    @Test
    @DisplayName("list：null/空白/全值三形态各走一遍 Specification（39/42/47/50/53 全侧）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void riskRuleListExecutesAllFilterSides() {
        when(ruleRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        // 全 null（短路 F）、全空白（非 null 但 isBlank）、全值（T,T）
        riskRuleService.list(null, null, null, null, null, 0, 10);
        riskRuleService.list("  ", "  ", "  ", "  ", "  ", 0, 10);
        riskRuleService.list("g1", "env1", "active", "threshold", "speed", 0, 10);

        ArgumentCaptor<Specification<RiskRuleEntity>> specCaptor =
            ArgumentCaptor.forClass((Class) Specification.class);
        verify(ruleRepo, times(3)).findAll(specCaptor.capture(), any(Pageable.class));

        // mock repo 不会执行 spec，手动驱动 lambda 使条件表达式真正求值
        Root<RiskRuleEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        var cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        for (Specification<RiskRuleEntity> spec : specCaptor.getAllValues()) {
            assertNotNull(spec.toPredicate(root, query, cb));
        }
    }

    @Test
    @DisplayName("update：patch 带初始化器的 13 个字段显式置 null → 全走「沿用 existing」侧（113-129）")
    void riskRuleUpdateNullPatchKeepsInitializerFields() {
        RiskRuleEntity existing = new RiskRuleEntity();
        existing.id = "rr_1";
        existing.gameId = "g1";
        existing.name = "orig";
        // 特异值证明保留的是 existing 而非实体初始化器默认
        existing.category = RiskRuleEntity.RuleCategory.PAYMENT;
        existing.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        existing.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        existing.actionType = RiskRuleEntity.ActionType.IGNORE;
        existing.riskScore = 77;
        existing.triggerThreshold = 5;
        existing.enableAutoBlock = true;
        existing.enableWebhook = true;
        existing.enableReviewQueue = false;
        existing.timeWindowMinutes = 33;
        existing.cooldownMinutes = 44;
        existing.priority = 7;
        existing.testMode = true;
        when(ruleRepo.findById("rr_1")).thenReturn(Optional.of(existing));
        when(ruleRepo.save(any(RiskRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity patch = new RiskRuleEntity();
        // 初始化器字段必须显式置 null，否则覆盖成默认值而非走 false 侧
        patch.category = null;
        patch.ruleType = null;
        patch.riskLevel = null;
        patch.actionType = null;
        patch.riskScore = null;
        patch.triggerThreshold = null;
        patch.enableAutoBlock = null;
        patch.enableWebhook = null;
        patch.enableReviewQueue = null;
        patch.timeWindowMinutes = null;
        patch.cooldownMinutes = null;
        patch.priority = null;
        patch.testMode = null;

        RiskRuleEntity saved = riskRuleService.update("rr_1", patch, "op_1");

        assertEquals(RiskRuleEntity.RuleCategory.PAYMENT, saved.category);
        assertEquals(RiskRuleEntity.RuleType.THRESHOLD, saved.ruleType);
        assertEquals(RiskRuleEntity.RiskLevel.HIGH, saved.riskLevel);
        assertEquals(RiskRuleEntity.ActionType.IGNORE, saved.actionType);
        assertEquals(77, saved.riskScore);
        assertEquals(5, saved.triggerThreshold);
        assertEquals(Boolean.TRUE, saved.enableAutoBlock);
        assertEquals(Boolean.TRUE, saved.enableWebhook);
        assertEquals(Boolean.FALSE, saved.enableReviewQueue);
        assertEquals(33, saved.timeWindowMinutes);
        assertEquals(44, saved.cooldownMinutes);
        assertEquals(7, saved.priority);
        assertEquals(Boolean.TRUE, saved.testMode);
        assertEquals("orig", saved.name);   // name null 也沿用
    }

    // ===== GameService =====

    private GameEntity game(GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = "g1";
        g.name = "demo";
        g.platforms = java.util.Set.of(GameEntity.GamePlatform.WEB);
        g.currentVersion = "1.0.0";
        g.status = status;
        return g;
    }

    @Test
    @DisplayName("getEnvironment：环境名 null/空白拒绝、非空名走 trim 查询（467 全侧）")
    void gameEnvironmentNameSides() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
        assertThrows(IllegalArgumentException.class, () -> gameService.getEnvironment("g1", null));
        assertThrows(IllegalArgumentException.class, () -> gameService.getEnvironment("g1", "   "));
        // 非空名：通过 467 校验后走 repo 查询，未命中 → 空 Optional 正常返回（证明走到 trim 后的查询侧）
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod")).thenReturn(List.of());
        assertTrue(gameService.getEnvironment("g1", "prod").isEmpty());
    }

    @Test
    @DisplayName("updateGame：TESTING→DEVELOPMENT 合法、TESTING→DISCONTINUED 与 LIVE→DEVELOPMENT 拒绝（602/603 各侧）")
    void gameStatusTransitionSides() {
        when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameEntity testing = game(GameEntity.GameStatus.TESTING);
        when(gameRepo.findById("gt")).thenReturn(Optional.of(testing));
        GameDTO toDev = new GameDTO();
        toDev.status = GameEntity.GameStatus.DEVELOPMENT;
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, gameService.updateGame("gt", toDev).status);  // 602 右侧真

        GameEntity testing2 = game(GameEntity.GameStatus.TESTING);
        when(gameRepo.findById("gt2")).thenReturn(Optional.of(testing2));
        GameDTO toDisc = new GameDTO();
        toDisc.status = GameEntity.GameStatus.DISCONTINUED;
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("gt2", toDisc));  // 602 双假

        GameEntity live = game(GameEntity.GameStatus.LIVE);
        when(gameRepo.findById("gl")).thenReturn(Optional.of(live));
        GameDTO fromLive = new GameDTO();
        fromLive.status = GameEntity.GameStatus.DEVELOPMENT;
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("gl", fromLive));  // 603 假侧
    }

    @Test
    @DisplayName("publishGame：name null 与纯空白均拒绝发布（620 两侧）")
    void publishGameBlankNameRejected() {
        GameEntity nullName = game(GameEntity.GameStatus.TESTING);
        nullName.name = null;
        when(gameRepo.findById("gn")).thenReturn(Optional.of(nullName));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("gn"));

        GameEntity blankName = game(GameEntity.GameStatus.TESTING);
        blankName.name = "   ";
        when(gameRepo.findById("gb")).thenReturn(Optional.of(blankName));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("gb"));
    }

    // ===== ExperimentService =====

    private ExperimentDTO expDto(String name) {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = "exp_new";
        dto.gameId = "g1";
        dto.environmentId = "env1";
        dto.name = name;
        return dto;
    }

    @Test
    @DisplayName("create：name null 拒绝（376 null 侧）；salt 非空白走 trim（119）")
    void expCreateNullNameAndSaltTrim() {
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env1";
        env.gameId = "g1";   // resolveEnvironment 归属校验读 gameId
        when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
        when(gameEnvironmentRepo.findById("env1")).thenReturn(Optional.of(env));
        when(experimentRepo.findById(anyString())).thenReturn(Optional.empty());
        when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> experimentService.createExperiment(expDto(null)));

        ExperimentDTO salted = expDto("n1");
        salted.salt = " salty ";
        experimentService.createExperiment(salted);
        ArgumentCaptor<ExperimentEntity> saved = ArgumentCaptor.forClass(ExperimentEntity.class);
        verify(experimentRepo).save(saved.capture());
        assertEquals("salty", saved.getValue().salt);
    }

    @Test
    @DisplayName("update：salt 非空白走 trim（144）；draft 状态下改 config 不按 running 校验（153 false 侧）")
    void expUpdateSaltTrimAndDraftConfig() throws Exception {
        ExperimentEntity entity = new ExperimentEntity();
        entity.id = "exp_1";
        entity.gameId = "g1";
        entity.environmentId = "env1";
        entity.name = "n";
        entity.status = "draft";
        entity.configJson = "{\"trafficPercent\":100,\"variants\":[]}";
        when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO saltTrim = new ExperimentDTO();
        saltTrim.salt = " s2 ";
        experimentService.updateExperiment("exp_1", saltTrim);
        assertEquals("s2", entity.salt);

        ExperimentDTO withConfig = new ExperimentDTO();
        // 不带 variants 字段：draft 下（requireRunnable=false）放行，153 的 equals(false) 侧
        withConfig.config = objectMapper.readTree("{\"trafficPercent\":50}");
        experimentService.updateExperiment("exp_1", withConfig);
        assertNotNull(entity.configJson);
        assertTrue(entity.configJson.contains("50"));

        // 153 真身是 status 段的 if ("running".equals(status))：
        // dto.status="running" → normalizeStatus 得 running → readConfig(entity) 按 running 校验
        ExperimentEntity toRun = new ExperimentEntity();
        toRun.id = "exp_torun";
        toRun.gameId = "g1";
        toRun.environmentId = "env1";
        toRun.name = "n";
        toRun.status = "draft";
        toRun.configJson = "{\"trafficPercent\":100,\"variants\":"
            + "[{\"name\":\"control\",\"weight\":50},{\"name\":\"treatment\",\"weight\":50}]}";
        when(experimentRepo.findById("exp_torun")).thenReturn(Optional.of(toRun));
        ExperimentDTO start = new ExperimentDTO();
        start.status = "running";
        experimentService.updateExperiment("exp_torun", start);
        assertEquals("running", toRun.status);

        // dto.status 非 null 但 normalize 后非 running → 153 的 equals(false) 侧（不触发 running 校验）
        ExperimentEntity toPause = new ExperimentEntity();
        toPause.id = "exp_pause";
        toPause.gameId = "g1";
        toPause.environmentId = "env1";
        toPause.name = "n";
        toPause.status = "running";
        toPause.configJson = "{\"trafficPercent\":100,\"variants\":"
            + "[{\"name\":\"control\",\"weight\":50},{\"name\":\"treatment\",\"weight\":50}]}";
        when(experimentRepo.findById("exp_pause")).thenReturn(Optional.of(toPause));
        ExperimentDTO pause = new ExperimentDTO();
        pause.status = "paused";
        experimentService.updateExperiment("exp_pause", pause);
        assertEquals("paused", toPause.status);
    }

    // ===== WebhookService =====

    @Test
    @DisplayName("updateWebhookConfig：patch.status 双侧（281）；existing/patch authType 均 null 直接跳过校验（360 null 侧）")
    void webhookUpdateStatusSidesAndNullAuthType() {
        WebhookConfigEntity existing = new WebhookConfigEntity();
        existing.id = "wc_1";
        existing.gameId = "g1";
        existing.name = "n";
        existing.webhookUrl = "https://x.example.com/h";
        existing.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        // authType 不设 → null；patch 也不带 → effective null → validateAuthConfig 第一条件短路
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(existing));
        when(webhookConfigRepo.findByGameIdAndName("g1", "n")).thenReturn(Optional.of(existing));  // 同名是自己
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookConfigEntity paused = new WebhookConfigEntity();
        paused.name = "n";
        paused.webhookUrl = "https://x.example.com/h";
        paused.status = WebhookConfigEntity.WebhookStatus.PAUSED;
        assertEquals(WebhookConfigEntity.WebhookStatus.PAUSED,
            webhookService.updateWebhookConfig("g1", "wc_1", paused, "ops").status);   // 281 true 侧

        WebhookConfigEntity keepStatus = new WebhookConfigEntity();
        keepStatus.name = "n";
        keepStatus.webhookUrl = "https://x.example.com/h";
        keepStatus.status = null;   // 防 status 有初始化器
        assertEquals(WebhookConfigEntity.WebhookStatus.PAUSED,
            webhookService.updateWebhookConfig("g1", "wc_1", keepStatus, "ops").status);   // 281 false 侧：保留
    }

    // ===== PlayerErasureService =====

    @Test
    @DisplayName("resolve：USER_ID 与 DEVICE_ID 输入类型各走对应 case；identity.character_id 展开（370）")
    void erasureResolveUserIdAndDeviceIdTypes() {
        PlayerErasureService.ResolvedIdentities byUser =
            erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.USER_ID, "u1", 500);
        assertTrue(byUser.userIds().contains("u1"));

        PlayerErasureService.ResolvedIdentities byDevice =
            erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.DEVICE_ID, "d1", 500);
        assertTrue(byDevice.deviceIds().contains("d1"));

        // character_id case：link 反查 identityId → identity.characterId 入队 → case "character_id"
        IdentityLinkEntity charLink = new IdentityLinkEntity();
        charLink.identityId = "idt_c";
        charLink.linkedIdentityType = "player_id";
        charLink.linkedId = "p1";
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1")).thenReturn(List.of(charLink));
        IdentityEntity charIdentity = new IdentityEntity();
        charIdentity.characterId = "c1";
        when(identityRepo.findById("idt_c")).thenReturn(Optional.of(charIdentity));
        PlayerErasureService.ResolvedIdentities withChar =
            erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);
        assertTrue(withChar.characterIds().contains("c1"));
    }

    @Test
    @DisplayName("PG 清洗：mail.recipients 为 null → removeRecipient 返 null，不落库（485 null 侧）")
    void erasureMailNullRecipientsSkipped() {
        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        MailEntity nullRecipients = new MailEntity();
        nullRecipients.recipients = null;
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "p1"))
            .thenReturn(List.of(nullRecipients));
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());

        erasureService.purgePostgres("g1", one, "per_null");

        verify(mailRepo, never()).save(any(MailEntity.class));
    }

    @Test
    @DisplayName("CH 清洗：预估/变更状态查询返回空结果集 → 按 0/已完成处理（643/659 isEmpty 侧）")
    void erasureChEmptyCountRows() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of());   // estimateRows 单参 → isEmpty → 0
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of());   // pendingMutations → 空
        when(clickHouse.execute(any())).thenReturn(null);

        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        Map<String, Object> ch = erasureService.purgeClickHouse("g1", one, "per_empty");

        assertEquals("EXECUTED", ch.get("status"));
    }

    @Test
    @DisplayName("CH 清洗：mutation 状态查询非空计数 → baseline/pending 同值即回落确认（659 非空侧）")
    void erasureChPendingMutationNonEmptyCounts() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of());   // estimateRows → 空 → 0
        when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("c", 2L)));   // pendingMutations → 非空 Number 计数
        when(clickHouse.execute(any())).thenReturn(null);

        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        Map<String, Object> ch = erasureService.purgeClickHouse("g1", one, "per_pending");

        assertEquals("EXECUTED", ch.get("status"));
    }

    @Test
    @DisplayName("CH 清洗：mutation 计数非数字（instanceof false）→ 按 0 记基线（659 false 侧）")
    void erasureChPendingMutationNonNumericTolerated() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of());
        when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("c", "not-a-number")));   // 非 Number
        when(clickHouse.execute(any())).thenReturn(null);

        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        Map<String, Object> ch = erasureService.purgeClickHouse("g1", one, "per_nonnum");

        assertEquals("EXECUTED", ch.get("status"));   // 基线 0，轮询同 0 → 回落确认
    }

    // ===== FlinkJobService =====

    @Test
    @DisplayName("deployJob：controlToken 非空但纯空白 → 不透传 --control.token（450 T,F 短路侧）")
    void flinkBlankControlTokenOmittedFromProgramArgs() throws Exception {
        Path jarDir = Files.createTempDirectory("g1_jars");
        Files.writeString(jarDir.resolve("risk-job-0.1.0-all.jar"), "fake");
        setFlinkField("jarDir", jarDir.toString());
        setFlinkField("controlToken", "   ");
        when(flinkRestClient.uploadJar(any(Path.class))).thenReturn("jar_b");
        when(flinkRestClient.launch(anyString(), anyString(), anyInt(), anyString())).thenReturn("job_b");
        FlinkJobEntity draft = new FlinkJobEntity();
        draft.id = "fj_blank";
        draft.gameId = "g1";
        draft.environmentId = "env1";
        draft.name = "risk-job";
        draft.jobType = FlinkJobEntity.JobType.RISK_EVALUATION.name();
        draft.parallelism = 2;
        draft.status = FlinkJobEntity.JobStatus.DRAFT;
        when(flinkJobRepo.findById("fj_blank")).thenReturn(Optional.of(draft));
        when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        flinkJobService.deployJob("fj_blank", "op");

        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(flinkRestClient).launch(anyString(), anyString(), eq(2), args.capture());
        assertFalse(args.getValue().contains("--control.token"));

        // controlToken == null：450 首条件 false 短路（生产 @Value 恒注入非 null，仅直调可达）
        setFlinkField("controlToken", null);
        FlinkJobEntity nullTokenDraft = new FlinkJobEntity();
        nullTokenDraft.id = "fj_null";
        nullTokenDraft.gameId = "g1";
        nullTokenDraft.environmentId = "env1";
        nullTokenDraft.name = "risk-job";
        nullTokenDraft.jobType = FlinkJobEntity.JobType.RISK_EVALUATION.name();
        nullTokenDraft.parallelism = 2;
        nullTokenDraft.status = FlinkJobEntity.JobStatus.DRAFT;
        when(flinkJobRepo.findById("fj_null")).thenReturn(Optional.of(nullTokenDraft));
        flinkJobService.deployJob("fj_null", "op");
        ArgumentCaptor<String> nullArgs = ArgumentCaptor.forClass(String.class);
        verify(flinkRestClient, times(2)).launch(anyString(), anyString(), eq(2), nullArgs.capture());
        assertFalse(nullArgs.getAllValues().get(1).contains("--control.token"));
    }

    private void setFlinkField(String name, Object value) throws Exception {
        Field f = FlinkJobService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(flinkJobService, value);
    }

    // ===== MailService =====

    @Test
    @DisplayName("inbox：environmentId null → 空串、非 null → 原值（126 双侧）")
    void mailInboxEnvironmentNullAndPresent() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
        when(mailRepo.findInbox(eq("g1"), anyString(), anyString(), any(LocalDateTime.class)))
            .thenReturn(List.of());

        mailService.inbox("g1", null, "p1");
        mailService.inbox("g1", "env1", "p1");

        verify(mailRepo).findInbox(eq("g1"), eq(""), eq("p1"), any(LocalDateTime.class));
        verify(mailRepo).findInbox(eq("g1"), eq("env1"), eq("p1"), any(LocalDateTime.class));
    }

    // ===== RedeemCodeService =====

    @Test
    @DisplayName("createBatch：SHARED sharedCode 非空但纯空白 → 走生成分支且 codeLength>0 生效（99 T,F / 101 T）")
    void redeemSharedBlankCodeFallsBackToGenerated() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(new GameEntity()));
        when(batchRepo.save(any(RedeemCodeBatchEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.save(any(RedeemCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.findByCode(anyString())).thenReturn(Optional.empty());

        RedeemCodeBatchEntity batch = new RedeemCodeBatchEntity();
        batch.gameId = "game_demo";
        batch.name = "活动码";
        batch.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":1}]";
        batch.codeType = RedeemCodeBatchEntity.CodeType.SHARED;
        batch.total = 0;
        batch.perUserLimit = 1;

        redeemCodeService.createBatch(batch, null, 8, "   ", "op_1");

        verify(codeRepo).save(argThat(c -> c.code != null && c.code.length() == 8));
    }

    // ===== CohortService =====

    @Test
    @DisplayName("createCohort：retentionPeriods null 与空集都落 null JSON（90 双侧）")
    void cohortCreateRetentionPeriodsNullAndEmpty() {
        when(cohortRepo.findByGameIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        cohortService.createCohort("g1", null, "c_null", "C", null, CohortEntity.CohortType.ACQUISITION,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), "day", "retention",
            null, null, null, "op");
        cohortService.createCohort("g1", null, "c_empty", "C", null, CohortEntity.CohortType.ACQUISITION,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), "day", "retention",
            null, null, List.of(), "op");

        verify(cohortRepo, times(2)).save(any(CohortEntity.class));
    }

    @Test
    @DisplayName("calculateCohort：gameId 纯空白诚实 FAILED（265）；timeUnit 纯空白回落 day 正常完成（283）")
    void cohortBlankGameIdFailsAndBlankTimeUnitDefaults() {
        // CH answer 照 CohortServiceDeepTest：首见分桶 + INTERVAL 回访
        when(clickHouse.isAvailable()).thenReturn(true);   // 覆盖 setUp 的 false（computeCohort 可用性前置）
        when(clickHouse.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("INTERVAL 1 ")) {
                return List.of(Map.of("first_bucket", "2026-09-01 00:00:00", "returned", 30L));
            }
            if (sql.contains("INTERVAL")) {
                return List.of();
            }
            return List.of(
                Map.of("first_bucket", "2026-09-01 00:00:00", "size", 100L),
                Map.of("first_bucket", "2026-09-02 00:00:00", "size", 50L));
        });

        CohortEntity blankGame = new CohortEntity();
        blankGame.id = "ch_bg";
        blankGame.gameId = "   ";
        blankGame.name = "n";
        blankGame.cohortType = CohortEntity.CohortType.ACQUISITION;
        blankGame.analysisType = "retention";
        blankGame.timeUnit = "day";
        blankGame.startDate = LocalDate.of(2026, 9, 1);
        blankGame.endDate = LocalDate.of(2026, 9, 7);
        blankGame.retentionPeriods = "[1, 7]";
        when(cohortRepo.findById("ch_bg")).thenReturn(Optional.of(blankGame));
        assertThrows(RuntimeException.class, () -> cohortService.calculateCohort("ch_bg"));
        assertEquals(CohortEntity.CohortStatus.FAILED, blankGame.status);

        CohortEntity blankUnit = new CohortEntity();
        blankUnit.id = "ch_bu";
        blankUnit.gameId = "g1";
        blankUnit.name = "n";
        blankUnit.cohortType = CohortEntity.CohortType.ACQUISITION;
        blankUnit.analysisType = "retention";
        blankUnit.timeUnit = "   ";
        blankUnit.startDate = LocalDate.of(2026, 9, 1);
        blankUnit.endDate = LocalDate.of(2026, 9, 7);
        blankUnit.retentionPeriods = "[1, 7]";
        when(cohortRepo.findById("ch_bu")).thenReturn(Optional.of(blankUnit));
        CohortEntity calculated = cohortService.calculateCohort("ch_bu");
        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
    }

    // ===== CrashMetricsService =====

    @Test
    @DisplayName("topGroups：environment 为 null → 跳过环境过滤（45 null 侧）")
    void crashTopGroupsNullEnvironmentSkipsEnvFilter() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of());

        crashMetricsService.topGroups("g", null, 14, null);

        // 参数只含 gameId + since（2 个），无 env
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(anyString(), params.capture());
        assertEquals(2, params.getValue().length);
    }

    // ===== PipelineService =====

    private io.oddsmaker.control.jpa.DataQualityRuleEntity qualityRule() {
        io.oddsmaker.control.jpa.DataQualityRuleEntity r = new io.oddsmaker.control.jpa.DataQualityRuleEntity();
        r.ruleName = "r-completeness";
        r.ruleType = io.oddsmaker.control.jpa.DataQualityRuleEntity.RuleType.COMPLETENESS;
        r.targetTable = "events";
        r.targetColumn = "device_id";
        r.severity = io.oddsmaker.control.jpa.DataQualityRuleEntity.Severity.WARNING;
        return r;
    }

    @Test
    @DisplayName("executePipelineJob：retryCount 已耗尽 → isRetryable 短路，失败直接停 FAILED（176 首条件 false 侧）")
    void pipelineRetryBudgetExhaustedSkipsRetryFlag() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p_1";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        when(pipelineRepo.findById("p_1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p_1")).thenReturn(List.of(qualityRule()));
        when(clickHouse.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("CH down"));

        PipelineJobEntity job = new PipelineJobEntity();
        job.id = "pj_done";
        job.pipelineId = "p_1";
        job.gameId = "g1";
        job.retryCount = 3;   // == maxRetries(3 初始化器) → isRetryable false（3 < 3 不成立）

        ReflectionTestUtils.invokeMethod(pipelineService, "executePipelineJob", job, pipeline);

        assertEquals(PipelineJobEntity.JobStatus.FAILED, job.jobStatus);   // 不再置 RETRYING
    }
}
