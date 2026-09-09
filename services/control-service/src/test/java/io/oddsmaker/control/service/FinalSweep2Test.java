package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.api.RiskDashboardController;
import io.oddsmaker.control.dto.IdentityEventDto;
import io.oddsmaker.control.dto.RiskEventDto;
import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.AnnouncementRepo;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.MFAConfigEntity;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.TelemetryConfigEntity;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 覆盖率最终冲刺（第三轮）：与 ServicesFinalSweepTest / AnnouncementServiceTest /
 * IdentityConsumerTest / RiskActionRecorderTest / SecurityGameDeepTest /
 * RiskControllersTest / ControlServiceTest / UserPortalDeepTest 互补，
 * 覆盖 AnnouncementService 生命周期残余、SecurityService.disableMFA、
 * IdentityConsumer 合并与 primaryId 回退、RiskActionRecorder 的 risk_scores 回调、
 * RiskDashboardController 字段映射、ControlService 密钥与存储档案分支、
 * DeveloperPortalService 密钥/版本/遥测生命周期、MailService.sweep。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2Test {

    // ==================== 共享 Mock ====================

    @Mock
    private AuditLogService auditLog;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private AnnouncementRepo announcementRepo;

    @Mock
    private MFAConfigRepo mfaConfigRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ClickHouseClient clickHouseClient;

    @Mock
    private RiskDashboardService riskDashboardService;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @Mock
    private MailRepo mailRepo;

    @Mock
    private MailClaimRepo mailClaimRepo;

    // ==================== 被测服务 ====================

    @InjectMocks
    private AnnouncementService announcementService;

    @InjectMocks
    private SecurityService securityService;

    @InjectMocks
    private IdentityConsumer identityConsumer;

    @InjectMocks
    private RiskDashboardController riskDashboardController;

    @InjectMocks
    private ControlService controlService;

    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @InjectMocks
    private MailService mailService;

    private RiskActionRecorder riskActionRecorder;

    @BeforeEach
    void setUp() {
        riskActionRecorder = new RiskActionRecorder(clickHouseClient);
    }

    // ==================== 数据构造 ====================

    private static GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        return g;
    }

    private static GameEnvironmentEntity environment(String gameId, String id, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.name = name;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        return e;
    }

    private static AnnouncementEntity announcement(String id, AnnouncementEntity.Status status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.id = id;
        a.gameId = "game_demo";
        a.title = "维护公告";
        a.content = "今晚停服维护";
        a.status = status;
        return a;
    }

    private String identityJson(IdentityEventDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =========================================================
    // AnnouncementService：list / get / create 的入参与环境分支
    // =========================================================

    @Test
    @DisplayName("公告：list/get 查询与 create 全环境分支及入参守卫")
    void announcementListGetAndCreateGuards() {
        GameEntity demo = game("game_demo");
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(demo));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());

        AnnouncementEntity a = announcement("ann_1", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo
            .findByGameIdAndDeletedAtIsNullOrderByPriorityDescCreatedAtDesc("game_demo"))
            .thenReturn(List.of(a));
        assertEquals(1, announcementService.list("game_demo").size());
        assertThrows(IllegalArgumentException.class, () -> announcementService.list("game_x"));

        AnnouncementEntity deleted = announcement("ann_2", AnnouncementEntity.Status.DRAFT);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(announcementRepo.findById("ann_1")).thenReturn(Optional.of(a));
        lenient().when(announcementRepo.findById("ann_2")).thenReturn(Optional.of(deleted));
        assertSame(a, announcementService.get("ann_1"));
        assertNull(announcementService.get("ann_2"));
        assertNull(announcementService.get("ann_ghost"));

        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity blankEnv = announcement(null, null);
        blankEnv.gameId = "game_demo";
        blankEnv.environmentId = "   ";
        AnnouncementEntity created = announcementService.create(blankEnv, "op_1");
        assertNull(created.environmentId);
        assertTrue(created.id.startsWith("ann_"));
        assertEquals("op_1", created.createdBy);

        AnnouncementEntity noTitle = announcement(null, null);
        noTitle.gameId = "game_demo";
        noTitle.title = "  ";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noTitle, "op_1"));

        AnnouncementEntity noContent = announcement(null, null);
        noContent.gameId = "game_demo";
        noContent.content = " ";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noContent, "op_1"));

        AnnouncementEntity noGame = announcement(null, null);
        noGame.gameId = "game_x";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noGame, "op_1"));
        verify(announcementRepo, never()).save(noGame);
    }

    // =========================================================
    // AnnouncementService：update / publish / schedule / offline / delete / listActive
    // =========================================================

    @Test
    @DisplayName("公告：update 全字段与排期分支及生命周期残余")
    void announcementUpdateAndLifecycle() {
        AnnouncementEntity published = announcement("ann_p", AnnouncementEntity.Status.PUBLISHED);
        lenient().when(announcementRepo.findById("ann_p")).thenReturn(Optional.of(published));
        assertThrows(IllegalStateException.class,
            () -> announcementService.update("ann_p", new AnnouncementEntity(), "op"));

        lenient().when(announcementRepo.findById("ann_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_missing", new AnnouncementEntity(), "op"));

        AnnouncementEntity draft = announcement("ann_1", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_1")).thenReturn(Optional.of(draft));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity req = new AnnouncementEntity();
        req.title = "  新标题  ";
        req.content = "新内容";
        req.channel = AnnouncementEntity.Channel.MARQUEE;
        req.priority = 5;
        req.autoOfflineAt = LocalDateTime.now().plusDays(1);
        req.scheduledAt = LocalDateTime.now().plusHours(1);
        AnnouncementEntity updated = announcementService.update("ann_1", req, "op");
        assertEquals("新标题", updated.title);
        assertEquals("新内容", updated.content);
        assertEquals(AnnouncementEntity.Channel.MARQUEE, updated.channel);
        assertEquals(5, updated.priority);
        assertNotNull(updated.autoOfflineAt);
        assertEquals(AnnouncementEntity.Status.SCHEDULED, updated.status);
        assertNotNull(updated.scheduledAt);

        AnnouncementEntity pastSchedule = new AnnouncementEntity();
        pastSchedule.scheduledAt = LocalDateTime.now().minusMinutes(1);
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_1", pastSchedule, "op"));

        AnnouncementEntity blankTitle = new AnnouncementEntity();
        blankTitle.title = "   ";
        assertEquals("新标题", announcementService.update("ann_1", blankTitle, "op").title);

        // publish：已发布不可重复发布
        assertThrows(IllegalStateException.class,
            () -> announcementService.publish("ann_p", "op"));

        // schedule：草稿排期成功，过去时间/空时间拒绝，已发布拒绝
        AnnouncementEntity draftForSchedule = announcement("ann_s", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_s")).thenReturn(Optional.of(draftForSchedule));
        AnnouncementEntity scheduled = announcementService.schedule(
            "ann_s", LocalDateTime.now().plusHours(2), "op");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, scheduled.status);
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s", LocalDateTime.now().minusHours(1), "op"));
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s", null, "op"));
        assertThrows(IllegalStateException.class,
            () -> announcementService.schedule("ann_p", LocalDateTime.now().plusHours(1), "op"));

        // offline：PUBLISHED → OFFLINE
        AnnouncementEntity toOffline = announcement("ann_o", AnnouncementEntity.Status.PUBLISHED);
        lenient().when(announcementRepo.findById("ann_o")).thenReturn(Optional.of(toOffline));
        AnnouncementEntity offlined = announcementService.offline("ann_o", "op");
        assertEquals(AnnouncementEntity.Status.OFFLINE, offlined.status);
        assertNotNull(offlined.offlineAt);

        // delete：草稿成功、不存在返回 false
        AnnouncementEntity deletable = announcement("ann_d", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_d")).thenReturn(Optional.of(deletable));
        assertTrue(announcementService.delete("ann_d", "op"));
        assertNotNull(deletable.deletedAt);
        lenient().when(announcementRepo.findById("ann_ghost")).thenReturn(Optional.empty());
        assertFalse(announcementService.delete("ann_ghost", "op"));

        // listActive：环境 ID 直传命中与空环境
        GameEntity demo = game("game_demo");
        GameEnvironmentEntity prod = environment("game_demo", "env_prod", "prod");
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(demo));
        lenient().when(gameEnvironmentRepo.findById("env_prod")).thenReturn(Optional.of(prod));
        lenient().when(announcementRepo.findActive(eq("game_demo"), anyString(), any(LocalDateTime.class)))
            .thenReturn(List.of());

        announcementService.listActive("game_demo", "env_prod");
        verify(announcementRepo).findActive(eq("game_demo"), eq("env_prod"), any(LocalDateTime.class));
        announcementService.listActive("game_demo", "   ");
        verify(announcementRepo).findActive(eq("game_demo"), eq(""), any(LocalDateTime.class));
    }

    // =========================================================
    // SecurityService：disableMFA 三分支
    // =========================================================

    @Test
    @DisplayName("安全：disableMFA 不存在/非本人拒绝，成功禁用并审计")
    void securityDisableMfaBranches() {
        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_1";
        config.userId = "u1";
        config.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        config.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        lenient().when(mfaConfigRepo.findById("mfa_1")).thenReturn(Optional.of(config));
        lenient().when(mfaConfigRepo.findById("mfa_missing")).thenReturn(Optional.empty());
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class,
            () -> securityService.disableMFA("mfa_missing", "u1"));
        assertThrows(IllegalArgumentException.class,
            () -> securityService.disableMFA("mfa_1", "someone_else"));

        securityService.disableMFA("mfa_1", "u1");
        assertEquals(MFAConfigEntity.MFAStatus.DISABLED, config.mfaStatus);
        verify(mfaConfigRepo).save(config);
        verify(auditLog).logDelete(eq("mfa_config"), eq("mfa_1"), eq("TOTP"),
            eq("u1"), isNull(), isNull());
    }

    // =========================================================
    // IdentityConsumer：跳过分支 / 已有身份合并 / 环境 miss 缓存 / 异常吞掉
    // =========================================================

    @Test
    @DisplayName("身份消费：空 identityId 与坏 JSON 跳过，已有身份合并更新且环境解析 miss 走缓存")
    void identityConsumerSkipMergeAndEnvCache() {
        identityConsumer.onIdentityEvent("{}");
        identityConsumer.onIdentityEvent("not-a-json");
        verifyNoInteractions(identityRepo, identityLinkRepo, gameEnvironmentRepo);

        // 已有身份：eventCount 自增、firstSeen 回填、空白 device 跳过、链接 usageCount 自增
        IdentityEventDto merge = new IdentityEventDto();
        merge.gameId = "game_1";
        merge.identityId = "id_existing";
        merge.userId = "u9";
        merge.playerId = "p9";
        merge.deviceIds = List.of("dev_1", "  ");
        merge.playerIds = List.of("p_9");
        merge.firstSeen = 1_000L;
        merge.lastSeen = 2_000L;

        IdentityEntity existing = new IdentityEntity();
        existing.id = "id_existing";
        existing.gameId = "game_1";
        existing.eventCount = 5L;
        existing.firstSeenAt = null;
        lenient().when(identityRepo.findById("id_existing")).thenReturn(Optional.of(existing));
        lenient().when(identityRepo.save(any(IdentityEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdentityLinkEntity deviceLink = new IdentityLinkEntity();
        deviceLink.id = "ilk_1";
        deviceLink.identityId = "id_existing";
        deviceLink.linkedIdentityType = "device_id";
        deviceLink.linkedId = "dev_1";
        deviceLink.usageCount = 3L;
        lenient().when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(
            "id_existing", "device_id", "dev_1")).thenReturn(Optional.of(deviceLink));

        identityConsumer.onIdentityEvent(identityJson(merge));

        assertEquals(6L, existing.eventCount);
        assertNotNull(existing.firstSeenAt);
        assertEquals("u9", existing.userId);
        assertEquals("p9", existing.playerId);
        assertEquals("dev_1", existing.deviceId);
        assertEquals(4L, deviceLink.usageCount);
        verify(identityLinkRepo, org.mockito.Mockito.atLeast(3)).save(any(IdentityLinkEntity.class));

        // 新建身份：环境 miss → environmentId 为 null，并缓存结果
        IdentityEventDto first = new IdentityEventDto();
        first.gameId = "game_1";
        first.environment = "prod";
        first.identityId = "id_new";
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of());
        identityConsumer.onIdentityEvent(identityJson(first));

        IdentityEventDto second = new IdentityEventDto();
        second.gameId = "game_1";
        second.environment = "prod";
        second.identityId = "id_new2";
        identityConsumer.onIdentityEvent(identityJson(second));

        ArgumentCaptor<IdentityEntity> captor = ArgumentCaptor.forClass(IdentityEntity.class);
        verify(identityRepo, times(3)).save(captor.capture());
        assertNull(captor.getAllValues().get(1).environmentId);
        assertNull(captor.getAllValues().get(2).environmentId);
        verify(gameEnvironmentRepo, times(1))
            .findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod");

        // 上游落库异常被吞掉，不向 Kafka 抛出
        lenient().when(identityRepo.save(any(IdentityEntity.class)))
            .thenThrow(new RuntimeException("db down"));
        IdentityEventDto boom = new IdentityEventDto();
        boom.gameId = "game_1";
        boom.identityId = "id_err";
        assertDoesNotThrow(() -> identityConsumer.onIdentityEvent(identityJson(boom)));
    }

    // =========================================================
    // IdentityConsumer：新建身份 primaryId 回退链（设备 → userId → identityId）
    // =========================================================

    @Test
    @DisplayName("身份消费：新建身份 primaryId 依 首设备/userId/identityId 顺序回退")
    void identityConsumerPrimaryIdFallbackChain() {
        lenient().when(identityRepo.save(any(IdentityEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdentityEventDto withUser = new IdentityEventDto();
        withUser.gameId = "game_1";
        withUser.identityId = "id_u";
        withUser.userId = "u1";
        withUser.playerId = "p1";
        identityConsumer.onIdentityEvent(identityJson(withUser));

        IdentityEventDto bare = new IdentityEventDto();
        bare.gameId = "game_1";
        bare.identityId = "id_min";
        identityConsumer.onIdentityEvent(identityJson(bare));

        IdentityEventDto withDevices = new IdentityEventDto();
        withDevices.gameId = "game_1";
        withDevices.identityId = "id_dev";
        withDevices.deviceIds = List.of("dev_a", "dev_b");
        withDevices.lastSeen = 3_000L;
        identityConsumer.onIdentityEvent(identityJson(withDevices));

        ArgumentCaptor<IdentityEntity> captor = ArgumentCaptor.forClass(IdentityEntity.class);
        verify(identityRepo, times(3)).save(captor.capture());
        IdentityEntity byUser = captor.getAllValues().get(0);
        assertEquals("u1", byUser.primaryId);
        assertEquals(IdentityEntity.IdentityStatus.ACTIVE, byUser.status);
        assertEquals(1L, byUser.eventCount);
        assertNotNull(byUser.lastSeenAt);
        IdentityEntity minimal = captor.getAllValues().get(1);
        assertEquals("id_min", minimal.primaryId);
        IdentityEntity byDevice = captor.getAllValues().get(2);
        assertEquals("dev_a", byDevice.primaryId);
        assertEquals("dev_a", byDevice.deviceId);
    }

    // =========================================================
    // RiskActionRecorder：risk_scores 的 ConnectionCallback 真实执行
    // =========================================================

    @Test
    @DisplayName("风控归档：risk_scores 回调真实执行并写满 7 个参数，回调异常不外抛")
    void riskActionRecorderExecutesScoreCallback() throws Exception {
        lenient().when(clickHouseClient.isAvailable()).thenReturn(true);
        lenient().when(clickHouseClient.update(anyString(), any(Object[].class))).thenReturn(1);

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array reasons = mock(Array.class);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(statement);
        lenient().when(connection.createArrayOf(eq("String"), any(String[].class))).thenReturn(reasons);
        lenient().when(statement.executeUpdate()).thenReturn(1);
        doAnswer(inv -> ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(connection))
            .when(clickHouseClient).execute(any(ConnectionCallback.class));

        RiskEventDto event = new RiskEventDto();
        event.gameId = "game_demo";
        event.environment = "prod";
        event.ts = 1_730_000_000_000L;
        event.riskEventId = "re_001";
        event.ruleId = "rr_1";
        event.subjectType = "DEVICE";
        event.subjectId = "dev_abc";
        event.score = 0.95f;

        riskActionRecorder.record(event, "block", "blocked", "rc_1");

        verify(statement).setString(1, "game_demo");
        verify(statement).setString(2, "prod");
        verify(statement).setString(3, "DEVICE");
        verify(statement).setString(4, "dev_abc");
        verify(statement).setFloat(5, 0.95f);
        verify(statement).setTimestamp(eq(6), eq(new Timestamp(1_730_000_000_000L)));
        verify(statement).setArray(7, reasons);
        verify(statement).executeUpdate();

        doThrow(new RuntimeException("ch down"))
            .when(clickHouseClient).execute(any(ConnectionCallback.class));
        assertDoesNotThrow(() -> riskActionRecorder.record(event, "alert", "logged", null));
    }

    // =========================================================
    // RiskDashboardController：getActiveRules 字段映射 + 其余端点委托
    // =========================================================

    @Test
    @DisplayName("风控大屏：getActiveRules 字段映射（含空枚举回退）与 9 个端点委托")
    void riskDashboardActiveRulesMapping() {
        RiskRuleEntity rich = new RiskRuleEntity();
        rich.id = "rr_1";
        rich.name = "Speed";
        rich.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        rich.ruleConditions = "{}";
        rich.triggerThreshold = 5;
        rich.timeWindowMinutes = 10;
        rich.riskScore = 80;
        rich.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        rich.actionType = RiskRuleEntity.ActionType.BLOCK;
        RiskRuleEntity bare = new RiskRuleEntity();
        bare.id = "rr_2";
        bare.name = "Bare";
        bare.triggerThreshold = 1;
        bare.timeWindowMinutes = 2;
        bare.riskScore = 3;
        lenient().when(riskRuleRepo.findActiveByGameId("g")).thenReturn(List.of(rich, bare));

        List<Map<String, Object>> body = riskDashboardController.getActiveRules("g").getBody();
        assertEquals(2, body.size());
        Map<String, Object> first = body.get(0);
        assertEquals("rr_1", first.get("id"));
        assertEquals("Speed", first.get("name"));
        assertEquals("THRESHOLD", first.get("ruleType"));
        assertEquals("{}", first.get("ruleConditions"));
        assertEquals(5, first.get("triggerThreshold"));
        assertEquals(10, first.get("timeWindowMinutes"));
        assertEquals(80, first.get("riskScore"));
        assertEquals("HIGH", first.get("riskLevel"));
        assertEquals("BLOCK", first.get("actionType"));
        assertEquals("THRESHOLD", body.get(1).get("ruleType"));   // 实体默认枚举
        assertEquals("MEDIUM", body.get(1).get("riskLevel"));
        assertEquals("ALERT", body.get(1).get("actionType"));

        LocalDateTime since = LocalDateTime.now().minusHours(6);
        riskDashboardController.getOverview("g", since);
        riskDashboardController.getTrends("g", since, 12);
        riskDashboardController.getHighRiskTargets("g", since, 5);
        riskDashboardController.getRulePerformance("g");
        riskDashboardController.getBlockStats("g");
        riskDashboardController.getJobStats("g");
        riskDashboardController.getDashboard("g", null);
        riskDashboardController.getRecentCases("g", 7);
        riskDashboardController.getReviewQueueStats("g");

        verify(riskDashboardService).getOverview("g", since);
        verify(riskDashboardService).getRiskTrends("g", since, 12);
        verify(riskDashboardService).getHighRiskTargets("g", since, 5);
        verify(riskDashboardService).getRulePerformance("g");
        verify(riskDashboardService).getBlockStats("g");
        verify(riskDashboardService).getJobStats("g");
        verify(riskDashboardService).getDashboard("g", null);
        verify(riskDashboardService).getRecentCases("g", 7);
        verify(riskDashboardService).getReviewQueueStats("g");
    }

    // =========================================================
    // ControlService：createKey 角色分支 + getActiveKeyForGateway 不匹配分支
    // =========================================================

    @Test
    @DisplayName("密钥：SERVER/ADMIN/CLIENT 角色分支与网关视图的环境校验分支")
    void controlServiceKeyRolesAndGatewayBranches() {
        GameEntity demo = game("g");
        GameEnvironmentEntity prod = environment("g", "prod", "prod");
        prod.enableSampling = false;
        prod.sampleRate = 0.5;
        lenient().when(gameRepo.findById("g")).thenReturn(Optional.of(demo));
        lenient().when(gameEnvironmentRepo.findById("prod")).thenReturn(Optional.of(prod));
        lenient().when(apiKeyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        controlService.createKey("g", "prod", "server-key", "server");
        controlService.createKey("g", "prod", "admin-key", "admin");
        controlService.createKey("g", "prod", "client-key", null);

        ArgumentCaptor<ApiKeyEntity> keys = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepo, times(3)).save(keys.capture());
        ApiKeyEntity server = keys.getAllValues().get(0);
        assertEquals(ApiKeyEntity.ApiKeyType.SERVER, server.keyType);
        assertTrue(server.requireHmac);
        assertTrue(server.canWrite);
        ApiKeyEntity admin = keys.getAllValues().get(1);
        assertEquals(ApiKeyEntity.ApiKeyType.ADMIN, admin.keyType);
        assertFalse(admin.canWrite);
        assertTrue(admin.canRead);
        assertTrue(admin.canExport);
        assertTrue(admin.requireHmac);
        ApiKeyEntity client = keys.getAllValues().get(2);
        assertEquals(ApiKeyEntity.ApiKeyType.CLIENT, client.keyType);
        assertFalse(client.requireHmac);
        assertEquals("server", controlService.createKey("g", "prod", "s2", "SERVER").keyRole);

        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g", "prod", "k", "bogus"));

        GameEnvironmentEntity foreignEnv = environment("other", "qa", "qa");
        lenient().when(gameEnvironmentRepo.findById("qa")).thenReturn(Optional.of(foreignEnv));
        assertThrows(IllegalArgumentException.class,
            () -> controlService.createKey("g", "qa", "k", null));

        // getActiveKeyForGateway：成功映射环境策略
        ApiKeyEntity active = new ApiKeyEntity();
        active.apiKey = "pk_ok";
        active.secret = "sk_1";
        active.gameId = "g";
        active.environmentId = "prod";
        active.rpm = 100;
        active.ipRpm = 50;
        active.canWrite = true;
        active.requireHmac = true;
        active.propsAllowlist = "a,b";
        lenient().when(apiKeyRepo.findById("pk_ok")).thenReturn(Optional.of(active));
        Models.InternalApiKeyResp resp = controlService.getActiveKeyForGateway("pk_ok");
        assertEquals("sk_1", resp.secret);
        assertEquals("prod", resp.environment);
        assertEquals("active", resp.envStatus);
        assertEquals(Boolean.FALSE, resp.envEnableSampling);
        assertEquals(0.5, resp.envSampleRate);
        assertEquals(Boolean.TRUE, resp.canWrite);
        assertEquals(Boolean.TRUE, resp.requireHmac);
        assertEquals(Integer.valueOf(100), resp.rpm);
        assertEquals(List.of("a", "b"), resp.propsAllowlist);

        // 已吊销密钥 → null
        ApiKeyEntity revoked = new ApiKeyEntity();
        revoked.apiKey = "pk_revoked";
        revoked.gameId = "g";
        revoked.environmentId = "prod";
        revoked.status = ApiKeyEntity.ApiKeyStatus.REVOKED;
        lenient().when(apiKeyRepo.findById("pk_revoked")).thenReturn(Optional.of(revoked));
        assertNull(controlService.getActiveKeyForGateway("pk_revoked"));

        // 环境已删除 → null
        GameEnvironmentEntity deletedEnv = environment("g", "prod2", "prod2");
        deletedEnv.deletedAt = LocalDateTime.now();
        ApiKeyEntity keyEnvDel = new ApiKeyEntity();
        keyEnvDel.apiKey = "pk_envdel";
        keyEnvDel.gameId = "g";
        keyEnvDel.environmentId = "prod2";
        lenient().when(apiKeyRepo.findById("pk_envdel")).thenReturn(Optional.of(keyEnvDel));
        lenient().when(gameEnvironmentRepo.findById("prod2")).thenReturn(Optional.of(deletedEnv));
        assertNull(controlService.getActiveKeyForGateway("pk_envdel"));

        // 环境归属其他游戏 → null
        GameEnvironmentEntity mismatchEnv = environment("other", "prod3", "prod3");
        ApiKeyEntity keyMismatch = new ApiKeyEntity();
        keyMismatch.apiKey = "pk_mismatch";
        keyMismatch.gameId = "g";
        keyMismatch.environmentId = "prod3";
        lenient().when(apiKeyRepo.findById("pk_mismatch")).thenReturn(Optional.of(keyMismatch));
        lenient().when(gameEnvironmentRepo.findById("prod3")).thenReturn(Optional.of(mismatchEnv));
        assertNull(controlService.getActiveKeyForGateway("pk_mismatch"));
    }

    // =========================================================
    // ControlService：updatePolicy 字段分支 / deleteKeys 空参 / searchKeys
    // =========================================================

    @Test
    @DisplayName("密钥：updatePolicy 全字段覆盖、deleteKeys 空参零删除、searchKeys 透传")
    void controlServiceUpdatePolicyAndDeleteKeys() {
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "pk_p";
        key.gameId = "g";
        key.environmentId = "prod";
        key.name = "policy-key";
        key.rpm = 600;
        key.ipRpm = 300;
        lenient().when(apiKeyRepo.findById("pk_p")).thenReturn(Optional.of(key));
        lenient().when(apiKeyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameEnvironmentRepo.findById("prod"))
            .thenReturn(Optional.of(environment("g", "prod", "prod")));

        Models.KeyDetailResp req = new Models.KeyDetailResp();
        req.rpm = 120;
        req.ipRpm = 60;
        req.propsAllowlist = List.of("a", " b ");
        req.piiEmail = "mask";
        req.piiPhone = "drop";
        req.piiIp = "coarse";
        req.denyKeys = List.of("secret_key");
        req.maskKeys = List.of("user_name");
        Models.KeyDetailResp updated = controlService.updatePolicy("pk_p", req);
        assertEquals(Integer.valueOf(120), updated.rpm);
        assertEquals(Integer.valueOf(60), updated.ipRpm);
        assertEquals(List.of("a", "b"), updated.propsAllowlist);
        assertEquals("mask", updated.piiEmail);
        assertEquals("drop", updated.piiPhone);
        assertEquals("coarse", updated.piiIp);
        assertEquals(List.of("secret_key"), updated.denyKeys);
        assertEquals(List.of("user_name"), updated.maskKeys);

        Models.KeyDetailResp partial = new Models.KeyDetailResp();
        partial.rpm = 999;
        Models.KeyDetailResp patched = controlService.updatePolicy("pk_p", partial);
        assertEquals(Integer.valueOf(999), patched.rpm);
        assertEquals(Integer.valueOf(60), patched.ipRpm);

        lenient().when(apiKeyRepo.findById("pk_missing")).thenReturn(Optional.empty());
        assertNull(controlService.updatePolicy("pk_missing", new Models.KeyDetailResp()));

        assertEquals(0L, controlService.deleteKeys(null));
        assertEquals(0L, controlService.deleteKeys(List.of()));
        verify(apiKeyRepo, never()).deleteAllById(any());

        lenient().when(apiKeyRepo.existsById("pk_missing")).thenReturn(false);
        assertFalse(controlService.deleteKey("pk_missing"));

        lenient().when(apiKeyRepo.searchApiKeysByScope(eq("g"), eq("prod"), eq("ops"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(key)));
        ControlService.Paged<Models.KeyDetailResp> paged = controlService.searchKeys("g", "prod", "ops", 0, 5);
        assertEquals(1L, paged.total);
        assertEquals(1, paged.items.size());
        assertEquals("pk_p", paged.items.get(0).apiKey);
        verify(apiKeyRepo).searchApiKeysByScope(eq("g"), eq("prod"), eq("ops"), any(Pageable.class));
    }

    // =========================================================
    // ControlService：StorageProfile 创建/更新/删除全分支与策略解析
    // =========================================================

    @Test
    @DisplayName("存储档案：创建冲突/隔离策略解析、更新与删除占用分支、列表过滤排序")
    void controlServiceStorageProfileBranches() {
        StorageProfileEntity beta = new StorageProfileEntity();
        beta.id = "sp_1";
        beta.name = "Beta";
        StorageProfileEntity deleted = new StorageProfileEntity();
        deleted.id = "sp_del";
        deleted.name = "Alpha";
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findAll()).thenReturn(List.of(deleted, beta));
        lenient().when(storageProfileRepo.findById("sp_1")).thenReturn(Optional.of(beta));
        lenient().when(storageProfileRepo.findById("sp_del")).thenReturn(Optional.of(deleted));
        lenient().when(storageProfileRepo.save(any(StorageProfileEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(1, controlService.listStorageProfiles().size());
        assertEquals("Beta", controlService.listStorageProfiles().get(0).name);
        assertNull(controlService.getStorageProfile("sp_del"));

        lenient().when(storageProfileRepo.existsById("sp_1")).thenReturn(true);
        Models.CreateStorageProfileReq dupId = new Models.CreateStorageProfileReq();
        dupId.id = "sp_1";
        dupId.name = "Whatever";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(dupId));

        lenient().when(storageProfileRepo.existsById("beta-x")).thenReturn(false);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("Beta")).thenReturn(true);
        Models.CreateStorageProfileReq dupName = new Models.CreateStorageProfileReq();
        dupName.id = "beta-x";
        dupName.name = "Beta";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(dupName));

        Models.CreateStorageProfileReq noName = new Models.CreateStorageProfileReq();
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(noName));

        lenient().when(storageProfileRepo.existsById("sp_new")).thenReturn(false);
        lenient().when(storageProfileRepo.existsById("my-cluster-2")).thenReturn(false);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("New Profile")).thenReturn(false);
        Models.CreateStorageProfileReq explicit = new Models.CreateStorageProfileReq();
        explicit.id = "sp_new";
        explicit.name = "New Profile";
        explicit.isolationStrategy = "prod_isolated";
        explicit.active = null;
        Models.StorageProfileResp created = controlService.createStorageProfile(explicit);
        assertEquals("New Profile", created.displayName);
        assertEquals("PROD_ISOLATED", created.isolationStrategy);
        assertEquals(Boolean.TRUE, created.active);

        Models.CreateStorageProfileReq slugReq = new Models.CreateStorageProfileReq();
        slugReq.name = "My Cluster 2";
        Models.StorageProfileResp slugged = controlService.createStorageProfile(slugReq);
        assertEquals("my-cluster-2", slugged.id);
        assertEquals("SHARED", slugged.isolationStrategy);

        lenient().when(storageProfileRepo.findById("sp_x")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp_x", new Models.CreateStorageProfileReq()));

        StorageProfileEntity old = new StorageProfileEntity();
        old.id = "sp_old";
        old.name = "Old";
        lenient().when(storageProfileRepo.findById("sp_old")).thenReturn(Optional.of(old));
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("Taken")).thenReturn(true);
        Models.CreateStorageProfileReq renameToTaken = new Models.CreateStorageProfileReq();
        renameToTaken.name = "Taken";
        assertThrows(IllegalArgumentException.class,
            () -> controlService.updateStorageProfile("sp_old", renameToTaken));

        Models.CreateStorageProfileReq refresh = new Models.CreateStorageProfileReq();
        refresh.name = "Fresh";
        refresh.kafkaCluster = "kafka-1";
        Models.StorageProfileResp updated = controlService.updateStorageProfile("sp_old", refresh);
        assertEquals("Fresh", updated.name);
        assertEquals("kafka-1", updated.kafkaCluster);

        assertFalse(controlService.deleteStorageProfile("sp_x"));

        StorageProfileEntity busy = new StorageProfileEntity();
        busy.id = "sp_busy";
        busy.name = "Busy";
        lenient().when(storageProfileRepo.findById("sp_busy")).thenReturn(Optional.of(busy));
        lenient().when(gameEnvironmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp_busy"))
            .thenReturn(List.of(new GameEnvironmentEntity()));
        assertThrows(IllegalStateException.class, () -> controlService.deleteStorageProfile("sp_busy"));

        StorageProfileEntity free = new StorageProfileEntity();
        free.id = "sp_free";
        free.name = "Free";
        lenient().when(storageProfileRepo.findById("sp_free")).thenReturn(Optional.of(free));
        lenient().when(gameEnvironmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp_free"))
            .thenReturn(List.of());
        assertTrue(controlService.deleteStorageProfile("sp_free"));
        assertNotNull(free.deletedAt);
        assertEquals(Boolean.FALSE, free.active);
    }

    // =========================================================
    // DeveloperPortalService：SDK 密钥生命周期
    // =========================================================

    @Test
    @DisplayName("开发者门户：SDK 密钥 suspend/activate/revoke/delete 生命周期与列表查询")
    void portalSdkKeyLifecycle() {
        SDKKeyEntity key = new SDKKeyEntity();
        key.id = "sdk_1";
        key.keyName = "主密钥";
        key.gameId = "game_1";
        key.environment = "prod";
        lenient().when(sdkKeyRepo.findById("sdk_1")).thenReturn(Optional.of(key));
        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKKeyEntity suspended = developerPortalService.suspendSDKKey("sdk_1", "op");
        assertEquals(SDKKeyEntity.KeyStatus.SUSPENDED, suspended.keyStatus);
        verify(auditLog).logUpdate(eq("sdk_key"), eq("sdk_1"), eq("主密钥"),
            eq("op"), eq("op"), isNull(), anyMap());

        assertEquals(SDKKeyEntity.KeyStatus.ACTIVE,
            developerPortalService.activateSDKKey("sdk_1", "op").keyStatus);
        assertEquals(SDKKeyEntity.KeyStatus.REVOKED,
            developerPortalService.revokeSDKKey("sdk_1", "op").keyStatus);

        developerPortalService.deleteSDKKey("sdk_1", "op");
        assertNotNull(key.deletedAt);
        verify(auditLog).logDelete(eq("sdk_key"), eq("sdk_1"), eq("主密钥"),
            eq("op"), eq("op"), isNull());

        lenient().when(sdkKeyRepo.findByGameIdAndDeletedAtIsNull("game_1")).thenReturn(List.of(key));
        lenient().when(sdkKeyRepo.findByGameIdAndEnvironmentAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(key));
        assertEquals(1, developerPortalService.getGameSDKKeys("game_1", null).size());
        assertEquals(1, developerPortalService.getGameSDKKeys("game_1", "prod").size());

        lenient().when(sdkKeyRepo.findById("sdk_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.suspendSDKKey("sdk_missing", "op"));
    }

    // =========================================================
    // DeveloperPortalService：SDK 版本 + 遥测配置生命周期
    // =========================================================

    @Test
    @DisplayName("开发者门户：SDK 版本创建/查询/下载统计与遥测配置激活/停用/归档/删除")
    void portalSdkVersionAndTelemetryLifecycle() {
        lenient().when(sdkVersionRepo.existsByPlatformAndVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.0.0")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> developerPortalService.createSDKVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.0.0",
            SDKVersionEntity.ChangeType.MINOR, null, null, "op"));

        lenient().when(sdkVersionRepo.existsByPlatformAndVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.1.0")).thenReturn(false);
        lenient().when(sdkVersionRepo.save(any(SDKVersionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        SDKVersionEntity version = developerPortalService.createSDKVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.1.0",
            SDKVersionEntity.ChangeType.MINOR, "notes", "changelog", "op");
        assertTrue(version.id.startsWith("sdkver_"));
        verify(auditLog).logCreate(eq("sdk_version"), eq(version.id), eq("2.1.0"),
            eq("op"), eq("op"), isNull(), anyMap());

        lenient().when(sdkVersionRepo.findById("sv_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getSDKVersion("sv_missing"));

        lenient().when(sdkVersionRepo.findByPlatformOrderByCreatedAtDesc(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(List.of(version));
        lenient().when(sdkVersionRepo.findByPlatformAndVersionStatusOrderByCreatedAtDesc(
            eq(SDKVersionEntity.SDKPlatform.UNITY), eq(SDKVersionEntity.VersionStatus.RELEASED)))
            .thenReturn(List.of());
        assertEquals(1, developerPortalService
            .getPlatformVersions(SDKVersionEntity.SDKPlatform.UNITY, null).size());
        assertEquals(0, developerPortalService
            .getPlatformVersions(SDKVersionEntity.SDKPlatform.UNITY, "RELEASED").size());

        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(Optional.of(version));
        assertSame(version, developerPortalService.getLatestVersion(SDKVersionEntity.SDKPlatform.UNITY));
        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.ANDROID))
            .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getLatestVersion(SDKVersionEntity.SDKPlatform.ANDROID));

        lenient().when(sdkVersionRepo.findById("sv_1")).thenReturn(Optional.of(version));
        developerPortalService.recordDownload("sv_1");
        assertEquals(1L, version.totalDownloads);
        developerPortalService.updateActiveInstallations("sv_1", 500L);
        assertEquals(500L, version.activeInstallations);
        // createSDKVersion + recordDownload + updateActiveInstallations 共 3 次落库
        verify(sdkVersionRepo, times(3)).save(version);

        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.id = "tc_1";
        config.configName = "默认批量";
        lenient().when(telemetryConfigRepo.findById("tc_1")).thenReturn(Optional.of(config));
        lenient().when(telemetryConfigRepo.save(any(TelemetryConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(TelemetryConfigEntity.ConfigStatus.ACTIVE,
            developerPortalService.activateTelemetryConfig("tc_1", "op").configStatus);
        assertEquals(TelemetryConfigEntity.ConfigStatus.INACTIVE,
            developerPortalService.deactivateTelemetryConfig("tc_1", "op").configStatus);
        assertEquals(TelemetryConfigEntity.ConfigStatus.ARCHIVED,
            developerPortalService.archiveTelemetryConfig("tc_1", "op").configStatus);
        developerPortalService.deleteTelemetryConfig("tc_1", "op");
        assertNotNull(config.deletedAt);
        verify(auditLog).logDelete(eq("telemetry_config"), eq("tc_1"), eq("默认批量"),
            eq("op"), eq("op"), isNull());

        lenient().when(telemetryConfigRepo.findByGameIdAndDeletedAtIsNull("game_1"))
            .thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findByGameIdAndEnvironmentIdAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(config));
        assertEquals(1, developerPortalService.getGameTelemetryConfigs("game_1", null).size());
        assertEquals(1, developerPortalService.getGameTelemetryConfigs("game_1", "prod").size());
    }

    // =========================================================
    // MailService：sweep 过期标记
    // =========================================================

    @Test
    @DisplayName("邮件：sweep 到期 SENT 邮件标记 EXPIRED 并审计")
    void mailSweepExpiresDueMails() {
        MailEntity mail = new MailEntity();
        mail.id = "mail_1";
        mail.gameId = "game_1";
        mail.title = "补偿邮件";
        mail.status = MailEntity.Status.SENT;
        lenient().when(mailRepo.findByStatusAndExpireAtLessThanEqualAndDeletedAtIsNull(
            eq(MailEntity.Status.SENT), any(LocalDateTime.class))).thenReturn(List.of(mail));
        lenient().when(mailRepo.save(any(MailEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        mailService.sweep();

        assertEquals(MailEntity.Status.EXPIRED, mail.status);
        verify(mailRepo).save(mail);
        verify(auditLog).logUpdate(eq("op_mail"), eq("mail_1"), eq("补偿邮件"),
            eq("scheduler"), eq("scheduler"), isNull(), anyMap());
    }
}
