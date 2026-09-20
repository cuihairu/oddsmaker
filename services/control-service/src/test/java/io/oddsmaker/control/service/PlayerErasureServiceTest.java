package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除服务单测：标识展开不动点/防环/上限截断、PG 清洗计数与匿名化占位、
 * CH mutation 提交与确认轮询（基线法）、PARTIAL 重试、取消矩阵与 sweep 调度。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("玩家数据删除请求服务")
class PlayerErasureServiceTest {

    @Mock PlayerErasureRequestRepo requestRepo;
    @Mock GameRepo gameRepo;
    @Mock IdentityRepo identityRepo;
    @Mock IdentityLinkRepo identityLinkRepo;
    @Mock PlayerLoginLogRepo loginLogRepo;
    @Mock PlayerPaymentRepo paymentRepo;
    @Mock RedeemRecordRepo redeemRecordRepo;
    @Mock MailRepo mailRepo;
    @Mock MailClaimRepo mailClaimRepo;
    @Mock PlayerExportJobRepo exportJobRepo;
    @Mock RiskCaseRepo riskCaseRepo;
    @Mock ReviewQueueRepo reviewQueueRepo;
    @Mock BlockListRepo blockListRepo;
    @Mock AuditLogService auditLog;
    @Mock WebhookService webhookService;
    @Mock ClickHouseClient clickHouse;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    PlayerErasureService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "identityExpansionLimit", 500);
        ReflectionTestUtils.setField(service, "processingTimeoutMinutes", 60);
        // 默认 0：确认轮询立即超时（confirmed=false），避免测试挂 300s；
        // 需要 confirmed=true 的用例单独把 timeout 调回正值
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 0);
        ReflectionTestUtils.setField(service, "mutationPollIntervalSeconds", 0);
        ReflectionTestUtils.setField(service, "maxChRetries", 8);
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
    }

    private static IdentityEntity identity(String id, String userId, String playerId,
                                           String deviceId, String characterId) {
        IdentityEntity i = new IdentityEntity();
        i.id = id;
        i.gameId = "g1";
        i.userId = userId;
        i.playerId = playerId;
        i.deviceId = deviceId;
        i.characterId = characterId;
        return i;
    }

    private static IdentityLinkEntity link(String identityId, String type, String linkedId) {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.identityId = identityId;
        l.linkedIdentityType = type;
        l.linkedId = linkedId;
        return l;
    }

    /** 已固化的 PENDING 请求（resolvedIdentities：idt1/u1/p1/d1） */
    private static PlayerErasureRequestEntity savedRequest() {
        PlayerErasureRequestEntity req = new PlayerErasureRequestEntity();
        req.id = "per_test000000000000000000";
        req.gameId = "g1";
        req.status = PlayerErasureRequestEntity.Status.PENDING;
        req.requestType = PlayerErasureRequestEntity.RequestType.PLAYER_ID;
        req.requestValue = "p1";
        req.resolvedIdentities = "{\"inputType\":\"player_id\",\"inputValue\":\"p1\",\"gameId\":\"g1\","
            + "\"identityIds\":[\"idt1\"],\"userIds\":[\"u1\"],\"playerIds\":[\"p1\"],"
            + "\"deviceIds\":[\"d1\"],\"characterIds\":[],\"truncated\":false}";
        return req;
    }

    // ========== 标识展开 ==========

    @Test
    @DisplayName("展开收敛到不动点：player→identity→多标识→第二个 identity")
    void resolveConverges() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(
            identity("idt1", "u1", "p1", "d1", "c1")));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1"))
            .thenReturn(List.of(link("idt1", "user_id", "u1"), link("idt1", "device_id", "d1"),
                link("idt1", "character_id", "c1"), link("idt1", "player_id", "p2")));
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("user_id", "u1")).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus("g1", "u1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("device_id", "d1"))
            .thenReturn(List.of(link("idt2", "device_id", "d1")));
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus("g1", "d1")).thenReturn(List.of());
        when(identityRepo.findById("idt2")).thenReturn(Optional.of(
            identity("idt2", "u1", "p2", "d1", null)));

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.identityIds()).containsExactlyInAnyOrder("idt1", "idt2");
        assertThat(r.playerIds()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(r.userIds()).containsExactly("u1");
        assertThat(r.deviceIds()).containsExactly("d1");
        assertThat(r.characterIds()).containsExactly("c1");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("展开防环：p1↔p2 双向 link 只解析一次")
    void resolveCutsCycle() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(identity("idt1", null, "p1", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1"))
            .thenReturn(List.of(link("idt1", "player_id", "p2")));
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p2"))
            .thenReturn(List.of(link("idt2", "player_id", "p2")));
        when(identityRepo.findById("idt2")).thenReturn(Optional.of(identity("idt2", null, "p2", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt2"))
            .thenReturn(List.of(link("idt2", "player_id", "p1")));   // 回指 p1
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p2")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.playerIds()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    @DisplayName("展开超上限：第 limit+1 个新标识触发 truncated=true")
    void resolveTruncates() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        // 种子直匹配命中一个四字段齐全的身份行 → 展开出 3 个新标识，limit=2 时第 3 个触发截断
        IdentityEntity seed = identity("idt0", "u0", "p0", "d0", "c0");
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p0")).thenReturn(List.of(seed));
        when(identityRepo.findById("idt0")).thenReturn(Optional.of(seed));

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p0", 2);

        assertThat(r.truncated()).isTrue();
    }

    @Test
    @DisplayName("直匹配种子：无 identity_links 的历史身份行也被纳入")
    void resolveDirectMatchSeed() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndDeviceIdAnyStatus("g1", "d1"))
            .thenReturn(List.of(identity("idt9", "u9", null, "d1", null)));
        when(identityRepo.findById("idt9")).thenReturn(Optional.empty());
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt9")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.DEVICE_ID, "d1", 500);

        assertThat(r.identityIds()).containsExactly("idt9");
        assertThat(r.deviceIds()).containsExactly("d1");
        assertThat(r.truncated()).isFalse();
    }

    // ========== 创建 / 取消 ==========

    @Test
    @DisplayName("创建校验：游戏不存在 / 空 value / 非法类型均抛 IAE")
    void createValidation() {
        assertThatThrownBy(() -> service.create("gX", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", "PLAYER_ID", " ", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", "EMAIL", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("创建成功：固化 resolvedIdentities JSON + 审计 + PENDING")
    void createSuccess() throws Exception {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());

        PlayerErasureRequestEntity req = service.create("g1", "player_id", "p1", null, "op");

        assertThat(req.id).startsWith("per_").hasSize(28);
        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(req.requestType).isEqualTo(PlayerErasureRequestEntity.RequestType.PLAYER_ID);
        JsonNode resolved = objectMapper.readTree(req.resolvedIdentities);
        assertThat(resolved.get("playerIds").get(0).asText()).isEqualTo("p1");
        assertThat(resolved.get("truncated").asBoolean()).isFalse();
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.CREATE),
            eq("player_erasure_request"), eq(req.id), eq("p1"), anyString(),
            eq(AuditLogEntity.AuditResult.SUCCESS), eq("op"),
            isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("取消：PENDING 可取消；PROCESSING 抛 ISE")
    void cancelMatrix() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));

        PlayerErasureRequestEntity cancelled = service.cancel(req.id, "op2");
        assertThat(cancelled.status).isEqualTo(PlayerErasureRequestEntity.Status.CANCELLED);
        assertThat(cancelled.cancelledBy).isEqualTo("op2");
        assertThat(cancelled.cancelledAt).isNotNull();

        req.status = PlayerErasureRequestEntity.Status.PROCESSING;
        assertThatThrownBy(() -> service.cancel(req.id, "op2"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("get：不存在抛 IAE（Controller 映射 404）")
    void getNotFound() {
        when(requestRepo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("list：校验游戏存在后按创建倒序返回")
    void listRequiresGame() {
        when(requestRepo.findByGameIdOrderByCreatedAtDesc("g1"))
            .thenReturn(List.of(savedRequest()));
        assertThat(service.list("g1")).hasSize(1);
        assertThatThrownBy(() -> service.list("gX"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== PG 清洗 ==========

    @Test
    @DisplayName("PG 清洗：硬删计数 + 导出文件删除 + 邮件摘除 + 风控匿名化占位")
    void purgePostgresCoversAllTables() throws Exception {
        PlayerErasureService.ResolvedIdentities ids = objectMapper.readValue(
            savedRequest().resolvedIdentities, PlayerErasureService.ResolvedIdentities.class);

        Path exportFile = tempDir.resolve("export.json");
        Files.writeString(exportFile, "{}");
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.filePath = exportFile.toString();
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(List.of(job));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(2);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(3);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(4);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(5);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(6);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(7);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(1);

        MailEntity mail = new MailEntity();
        mail.recipients = "p1,other";
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "p1")).thenReturn(List.of(mail));
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "u1")).thenReturn(List.of());

        RiskCaseEntity rc = new RiskCaseEntity();
        rc.targetId = "p1";
        rc.targetName = "player p1";
        rc.evidenceData = "{\"subject\":\"p1\"}";
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(rc));
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());

        BlockListEntity bl = new BlockListEntity();
        bl.targetValue = "d1";
        bl.targetType = "device_id";
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of(bl));

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_test000000000000000000");

        assertThat(pg.get("identities")).isEqualTo(2L);
        assertThat(pg.get("identity_links")).isEqualTo(3L);
        assertThat(pg.get("player_login_logs")).isEqualTo(4L);
        assertThat(pg.get("player_payments")).isEqualTo(5L);
        assertThat(pg.get("redeem_records")).isEqualTo(6L);
        assertThat(pg.get("op_mail_claims")).isEqualTo(7L);
        assertThat(pg.get("player_export_jobs")).isEqualTo(1L);
        assertThat(pg.get("export_files_deleted")).isEqualTo(1L);
        assertThat(Files.exists(exportFile)).isFalse();
        assertThat(pg.get("op_mails_recipients_scrubbed")).isEqualTo(1L);
        assertThat(mail.recipients).isEqualTo("other");
        assertThat(pg.get("risk_cases_anonymized")).isEqualTo(1L);
        assertThat(rc.targetId).isEqualTo("erased:per_test000000000000000000");
        assertThat(rc.evidenceData).contains("erased:").doesNotContain("p1");
        assertThat(pg.get("block_lists_anonymized")).isEqualTo(1L);
        assertThat(bl.targetValue).isEqualTo("erased:per_test000000000000000000");
    }

    @Test
    @DisplayName("邮件摘空保留空串；playerKey 集合 = playerIds ∪ userIds")
    void purgePostgresScrubEmptyAndPlayerKeyUnion() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of("u1"), List.of("p1"), List.of(), List.of(), false);
        stubPgDeletes();

        MailEntity mail = new MailEntity();
        mail.recipients = "u1";
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(eq("g1"), anyString()))
            .thenReturn(List.of(mail));

        service.purgePostgres("g1", ids, "per_x");

        assertThat(mail.recipients).isEmpty();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redeemRecordRepo).deleteByGameIdAndPlayerKeyIn(eq("g1"), keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder("p1", "u1");
    }

    private void stubPgDeletes() {
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(0);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(0);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(0);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of());
    }

    // ========== CH 清洗 ==========

    /** CH 全通 stub：预估/基线/轮询 query + execute 回调注入 mock Statement 收集 SQL 文本 */
    private void stubChHappyPath(List<String> sqlLog) throws Exception {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 5L)));
        when(clickHouse.query(contains("system.mutations"), any(String.class)))
            .thenReturn(List.of(Map.of("c", 0L)));
        when(clickHouse.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<?> cb = inv.getArgument(0);
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.execute(anyString())).thenAnswer(a -> {
                sqlLog.add(a.getArgument(0));
                return true;
            });
            return cb.doInConnection(conn);
        });
    }

    @Test
    @DisplayName("CH 不可用降级：SKIPPED_UNCONFIGURED 且零 SQL")
    void purgeClickHouseSkipsWhenUnavailable() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of("idt1"), List.of("u1"), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(false);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("SKIPPED_UNCONFIGURED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        verify(clickHouse, never()).execute(any());
    }

    @Test
    @DisplayName("CH mutation：9 张表提交 + 谓词裸值 + per 标记 + 确认完成")
    void purgeClickHouseSubmitsMutations() throws Exception {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of("idt1"), List.of("u1"), List.of("p1"),
            List.of("d1", "d2"), List.of("c1"), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        List<String> sqlLog = new ArrayList<>();
        stubChHappyPath(sqlLog);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_abc");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        assertThat((List<?>) ch.get("mutations")).hasSize(9);
        assertThat(sqlLog).hasSize(9);
        assertThat(sqlLog.get(0)).startsWith("ALTER TABLE events DELETE").contains("/* per:per_abc */");
        // risk_* 的 subject_id 是裸 id（两列分离存储），无 "PLAYER:" 前缀
        assertThat(sqlLog.get(3)).contains("subject_id IN ('p1','u1','d1','d2','c1')");
        assertThat(sqlLog.get(2)).contains("hasAny(device_ids, ['d1','d2'])").contains("identity_id IN ('idt1')");
        assertThat(sqlLog.get(8)).isEqualTo("ALTER TABLE predictions DELETE WHERE game_id='g1' "
            + "AND user_id IN ('u1') /* per:per_abc */");
    }

    @Test
    @DisplayName("CH 提交异常：PENDING_RETRY（整体走 PARTIAL 重试）")
    void purgeClickHouseSubmitFailure() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(true);
        // 预估返回无 c 键的行：estimate 按 0 处理，不影响提交
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("x", 1L)));
        when(clickHouse.execute(any(ConnectionCallback.class)))
            .thenThrow(new RuntimeException("clickhouse down"));

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("PENDING_RETRY");
        assertThat(ch.get("confirmed")).isEqualTo(false);
        assertThat(ch.get("error")).isEqualTo("clickhouse down");
    }

    @Test
    @DisplayName("CH 确认轮询超时：EXECUTED 但 confirmed=false（mutation 已持久化不判失败）")
    void purgeClickHouseConfirmTimeout() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 1L)));
        // mutationTimeoutSeconds=0：轮询立即超时（confirmed=false），不真挂 300s
        when(clickHouse.query(contains("system.mutations"), any(String.class)))
            .thenReturn(List.of(Map.of("c", 1L)));

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(false);
    }

    // ========== process 流程 ==========

    @Test
    @DisplayName("process 全链：PENDING → PG 段中间落库 → CH → COMPLETED + webhook + 审计")
    void processFullChain() throws Exception {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubPgDeletes();
        stubChHappyPath(new ArrayList<>());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        assertThat(req.completedAt).isNotNull();
        JsonNode summary = objectMapper.readTree(req.executionSummary);
        assertThat(summary.get("pgDone").asBoolean()).isTrue();
        assertThat(summary.get("pg").get("identities").asInt()).isEqualTo(0);
        assertThat(summary.get("ch").get("status").asText()).isEqualTo("EXECUTED");
        verify(requestRepo, atLeastOnce()).save(req);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).sendCustomWebhook(eq("g1"), eq("player_data_erasure"), payload.capture());
        assertThat(payload.getValue().get("event")).isEqualTo("player_data_erasure");
        assertThat(payload.getValue().get("requestId")).isEqualTo(req.id);
        assertThat(payload.getValue().get("status")).isEqualTo("COMPLETED");
        assertThat(payload.getValue().get("ok")).isEqualTo(true);
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.DELETE),
            eq("player_erasure_request"), eq(req.id), eq("p1"), anyString(),
            eq(AuditLogEntity.AuditResult.SUCCESS), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PG 段异常 → FAILED（图谱已删无法重试）")
    void processPgFailureBecomesFailed() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenThrow(new RuntimeException("disk io error"));

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.FAILED);
        assertThat(req.errorMessage).contains("disk io error");
        verify(webhookService).sendCustomWebhook(eq("g1"), eq("player_data_erasure"), any());
    }

    @Test
    @DisplayName("CH 段异常且 pgDone → PARTIAL（自动重试）")
    void processChFailureBecomesPartial() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubPgDeletes();
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 0L)));
        when(clickHouse.execute(any(ConnectionCallback.class))).thenThrow(new RuntimeException("ch error"));

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        assertThat(req.errorMessage).isNotNull();
    }

    @Test
    @DisplayName("PARTIAL 重入只走 CH 段：PG Repo 零交互")
    void processPartialRetrySkipsPg() throws Exception {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.PARTIAL;
        req.executionSummary = "{\"pgDone\":true,\"retries\":1,\"pg\":{\"identities\":2}}";
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        stubChHappyPath(new ArrayList<>());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        verifyNoInteractions(identityRepo, identityLinkRepo, loginLogRepo, paymentRepo);
    }

    @Test
    @DisplayName("COMPLETED/PROCESSING 状态不允许 process")
    void processRejectsTerminalStates() {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.COMPLETED;
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        assertThatThrownBy(() -> service.process(req.id))
            .isInstanceOf(IllegalStateException.class);
    }

    // ========== sweep ==========

    @Test
    @DisplayName("sweep：僵尸 PROCESSING 重置回 PENDING")
    void sweepRequeuesStale() {
        PlayerErasureRequestEntity stale = savedRequest();
        stale.status = PlayerErasureRequestEntity.Status.PROCESSING;
        stale.startedAt = LocalDateTime.now().minusMinutes(120);
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(any())).thenReturn(List.of());

        service.sweep();

        assertThat(stale.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(stale.startedAt).isNull();
        assertThat(stale.errorMessage).contains("requeued");
    }

    @Test
    @DisplayName("sweep：未来 scheduledFor 跳过；重试耗尽的 PARTIAL 不再 process")
    void sweepSkipsFutureAndExhausted() {
        PlayerErasureRequestEntity future = savedRequest();
        future.scheduledFor = LocalDateTime.now().plusHours(1);
        PlayerErasureRequestEntity exhausted = savedRequest();
        exhausted.status = PlayerErasureRequestEntity.Status.PARTIAL;
        exhausted.executionSummary = "{\"pgDone\":true,\"retries\":8}";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status.PENDING))
            .thenReturn(List.of(future));
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(exhausted));

        service.sweep();

        assertThat(future.status).isEqualTo(PlayerErasureRequestEntity.Status.PENDING);
        assertThat(exhausted.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        verify(requestRepo, never()).save(exhausted);
    }

    @Test
    @DisplayName("sweep：单请求异常不中断后续处理")
    void sweepIsolatesFailures() {
        PlayerErasureRequestEntity first = savedRequest();
        PlayerErasureRequestEntity second = savedRequest();
        second.id = "per_second0000000000000000";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status.PENDING))
            .thenReturn(List.of(first, second));
        when(requestRepo.findByStatus(any())).thenReturn(List.of());
        // first 抛异常（process 内 get 直接抛出），second 正常完成
        when(requestRepo.findById(first.id)).thenThrow(new IllegalArgumentException("boom"));
        when(requestRepo.findById(second.id)).thenReturn(Optional.of(second));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();

        service.sweep();

        assertThat(second.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        verify(requestRepo, times(1)).findById(first.id);
    }

    @Test
    @DisplayName("webhook 失败不影响主流程")
    void webhookFailureIsTolerated() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();
        doThrow(new RuntimeException("webhook down"))
            .when(webhookService).sendCustomWebhook(anyString(), anyString(), any());

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
    }

    // ========== 覆盖补强：分支与防御路径 ==========

    @Test
    @DisplayName("links 中未知类型 / 空 linkedId 项被安全跳过")
    void expandIdentitySkipsBlankAndUnknownLinks() {
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "player_id", "p1")));
        when(identityRepo.findById("idt1"))
            .thenReturn(Optional.of(identity("idt1", "u1", "p1", null, null)));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1")).thenReturn(List.of(
            link("idt1", "other_type", "zzz"),   // 未知类型 → 跳过
            link("idt1", "user_id", " "),         // blank → 跳过
            link("idt1", null, "nn")));           // null 类型 → 跳过
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of());

        PlayerErasureService.ResolvedIdentities r = service.resolve(
            "g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertThat(r.userIds()).containsExactly("u1");
        assertThat(r.deviceIds()).isEmpty();
        assertThat(r.characterIds()).isEmpty();
    }

    @Test
    @DisplayName("创建：requestType 缺省抛 IAE；user_id 类型展开落 JSON")
    void createUserIdTypeAndNullType() throws Exception {
        assertThatThrownBy(() -> service.create("g1", null, "x", null, "op"))
            .isInstanceOf(IllegalArgumentException.class);

        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndUserIdAnyStatus("g1", "u1"))
            .thenReturn(List.of(identity("idu1", "u1", null, null, null)));
        when(identityRepo.findById("idu1")).thenReturn(Optional.empty());
        when(identityLinkRepo.findByIdentityIdAnyStatus("idu1")).thenReturn(List.of());

        PlayerErasureRequestEntity req = service.create("g1", "user_id", "u1", null, "op");
        assertThat(req.requestType).isEqualTo(PlayerErasureRequestEntity.RequestType.USER_ID);
        JsonNode resolved = objectMapper.readTree(req.resolvedIdentities);
        assertThat(resolved.get("userIds").get(0).asText()).isEqualTo("u1");
    }

    @Test
    @DisplayName("PG 清洗：无路径/文件缺失的导出任务安全跳过；无变化的风控案件不落库；审核队列与封禁 targetName 一并匿名化")
    void purgePostgresSkipsNullPathAndNoChangeHits() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);

        PlayerExportJobEntity nullPathJob = new PlayerExportJobEntity();
        PlayerExportJobEntity missingFileJob = new PlayerExportJobEntity();
        missingFileJob.filePath = tempDir.resolve("nope.json").toString();
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenReturn(List.of(nullPathJob, missingFileJob));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(0);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(0);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(0);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());

        // LIKE 命中但文本不含任何标识 → 替换无变化 → 不 save、不计数
        RiskCaseEntity unchanged = new RiskCaseEntity();
        unchanged.targetId = "keep";
        unchanged.evidenceData = "nothing to replace";
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(unchanged));
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());

        ReviewQueueEntity rq = new ReviewQueueEntity();
        rq.targetId = "p1";
        rq.targetName = "name p1";
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of(rq));

        BlockListEntity bl = new BlockListEntity();
        bl.targetValue = "p1";
        bl.targetType = "player_id";
        bl.targetName = "nick p1";
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of(bl));

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_x");

        assertThat(pg.get("export_files_deleted")).isEqualTo(0L);
        assertThat(pg.get("risk_cases_anonymized")).isEqualTo(0L);
        verify(riskCaseRepo, never()).save(any());
        assertThat(rq.targetId).isEqualTo("erased:per_x");
        assertThat(rq.targetName).isEqualTo("name erased:per_x");
        assertThat(pg.get("review_queues_anonymized")).isEqualTo(1L);
        assertThat(bl.targetValue).isEqualTo("erased:per_x");
        assertThat(bl.targetName).isEqualTo("nick erased:per_x");
    }

    @Test
    @DisplayName("PARTIAL 且无 pgDone 的请求拒绝重试（防状态错乱）")
    void processPartialWithoutPgDoneRejected() {
        PlayerErasureRequestEntity req = savedRequest();
        req.status = PlayerErasureRequestEntity.Status.PARTIAL;
        req.executionSummary = "{\"retries\":1}";
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        assertThatThrownBy(() -> service.process(req.id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pgDone");
    }

    @Test
    @DisplayName("resolvedIdentities 坏 JSON → FAILED；executionSummary 坏 JSON 仍可正常处理")
    void processToleratesBadJsonFields() {
        PlayerErasureRequestEntity badResolved = savedRequest();
        badResolved.resolvedIdentities = "{bad json";
        when(requestRepo.findById(badResolved.id)).thenReturn(Optional.of(badResolved));
        when(clickHouse.isAvailable()).thenReturn(false);
        service.process(badResolved.id);
        assertThat(badResolved.status).isEqualTo(PlayerErasureRequestEntity.Status.FAILED);
        assertThat(badResolved.errorMessage).contains("resolvedIdentities");

        PlayerErasureRequestEntity badSummary = savedRequest();
        badSummary.id = "per_second0000000000000000";
        badSummary.executionSummary = "{bad json";
        when(requestRepo.findById(badSummary.id)).thenReturn(Optional.of(badSummary));
        stubPgDeletes();
        service.process(badSummary.id);
        assertThat(badSummary.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
    }

    @Test
    @DisplayName("sweep：未耗尽重试的 PARTIAL 正常重走 CH 段直至 COMPLETED")
    void sweepRetriesPartialNormally() throws Exception {
        PlayerErasureRequestEntity partial = savedRequest();
        partial.status = PlayerErasureRequestEntity.Status.PARTIAL;
        partial.executionSummary = "{\"pgDone\":true,\"retries\":1}";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(partial));
        when(requestRepo.findById(partial.id)).thenReturn(Optional.of(partial));
        stubChHappyPath(new ArrayList<>());

        service.sweep();

        assertThat(partial.status).isEqualTo(PlayerErasureRequestEntity.Status.COMPLETED);
        assertThat(partial.executionSummary).contains("\"retries\":1");   // 成功路径不递增重试计数
    }

    @Test
    @DisplayName("CH 确认轮询：pending 高于基线时继续轮询，回落后确认成功")
    void purgeClickHousePollConfirmCycles() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 2);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 1L)));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // 前 9 次 = 基线（0），第一轮轮询 pending=1，之后回落 0
        when(clickHouse.query(contains("system.mutations"), any(String.class))).thenAnswer(inv -> {
            int i = calls.getAndIncrement();
            long c = i < 9 ? 0L : (i < 18 ? 1L : 0L);
            return List.of(Map.of("c", c));
        });
        when(clickHouse.execute(any(ConnectionCallback.class))).thenReturn(null);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
    }

    @Test
    @DisplayName("CH 轮询期查询异常：降级为 confirmed=false 不抛出（基线正常取得）")
    void purgeClickHouseToleratesMutationQueryErrors() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", 0L)));
        // 前 9 次（基线）正常返回 0，轮询期起查询失败（pendingMutations 返回 null → 无法确认）
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        when(clickHouse.query(contains("system.mutations"), any(String.class))).thenAnswer(inv -> {
            if (n.getAndIncrement() < 9) {
                return List.of(Map.of("c", 0L));
            }
            throw new RuntimeException("mutations query down");
        });

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(false);
    }

    @Test
    @DisplayName("CH 预估查询异常：estimate=-1 不影响 mutation 提交")
    void purgeClickHouseEstimateFailureIsTolerated() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        ReflectionTestUtils.setField(service, "mutationTimeoutSeconds", 5);
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString())).thenThrow(new RuntimeException("estimate fail"));
        when(clickHouse.query(contains("system.mutations"), any(String.class)))
            .thenReturn(List.of(Map.of("c", 0L)));
        when(clickHouse.execute(any(ConnectionCallback.class))).thenReturn(null);

        Map<String, Object> ch = service.purgeClickHouse("g1", ids, "per_x");

        assertThat(ch.get("status")).isEqualTo("EXECUTED");
        assertThat(ch.get("confirmed")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) ch.get("mutations");
        assertThat(mutations.get(0).get("estimate")).isEqualTo(-1L);
    }

    // ========== 静态辅助 ==========

    @Test
    @DisplayName("removeRecipient 精确 token 摘除（含前后逗号边界与前缀混淆）")
    void removeRecipientExactToken() {
        assertThat(PlayerErasureService.removeRecipient("a,p1,b", List.of("p1"))).isEqualTo("a,b");
        assertThat(PlayerErasureService.removeRecipient("p1,a", List.of("p1"))).isEqualTo("a");
        assertThat(PlayerErasureService.removeRecipient("a,p1", List.of("p1"))).isEqualTo("a");
        assertThat(PlayerErasureService.removeRecipient("p11", List.of("p1"))).isEqualTo("p11");
        assertThat(PlayerErasureService.removeRecipient(null, List.of("p1"))).isNull();
        assertThat(PlayerErasureService.removeRecipient("p1", List.of("p1"))).isEmpty();
        assertThat(PlayerErasureService.removeRecipient("a, p1 ,b", List.of("p1"))).isEqualTo("a,b");
    }

    @Test
    @DisplayName("inList 字面量拼接与单引号转义；replaceAll 字面量替换")
    void inListEscapesAndReplaceAll() {
        assertThat(PlayerErasureService.inList(List.of("a", "b"))).isEqualTo("('a','b')");
        assertThat(PlayerErasureService.inList(List.of("o'brien"))).isEqualTo("('o''brien')");
        assertThat(PlayerErasureService.inList(List.of())).isEqualTo("('')");
        assertThat(PlayerErasureService.inList(java.util.Arrays.asList("a", null))).isEqualTo("('a')");
        assertThat(PlayerErasureService.replaceAll("x=a x=b", List.of("a", "b"), "erased:r"))
            .isEqualTo("x=erased:r x=erased:r");
        assertThat(PlayerErasureService.replaceAll(null, List.of("a"), "erased:r")).isNull();
    }

    @Test
    @DisplayName("inList 反斜杠转义：CH 字面量 \\' 是转义单引号，反斜杠结尾的值只翻单引号会吃掉闭合引号")
    void inListEscapesBackslash() {
        // 值 a\' 逃逸形态：只翻单引号得 'a\''，CH 解析为字符串 a' 后 OR 1=1 逃出字符串
        assertThat(PlayerErasureService.inList(List.of("a\\' OR 1=1")))
            .isEqualTo("('a\\\\'' OR 1=1')");
        // 普通反斜杠值翻倍；反斜杠+单引号组合完整闭合
        assertThat(PlayerErasureService.inList(List.of("a\\b"))).isEqualTo("('a\\\\b')");
        assertThat(PlayerErasureService.inList(List.of("\\'"))).isEqualTo("('\\\\''')");
    }

    // ========== 覆盖补强（二）：审计失败 PARTIAL、sweep 防御、坏路径与序列化兜底 ==========

    @Test
    @DisplayName("创建：展开截断时审计详情带 [truncated] 标记")
    void createTruncatedDetail() {
        ReflectionTestUtils.setField(service, "identityExpansionLimit", 2);
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        IdentityEntity seed = identity("idt0", "u0", "p0", "d0", "c0");
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p0")).thenReturn(List.of(seed));
        when(identityRepo.findById("idt0")).thenReturn(Optional.of(seed));

        PlayerErasureRequestEntity req = service.create("g1", "player_id", "p0", null, "op");

        assertThat(req.resolvedIdentities).contains("\"truncated\":true");
        verify(auditLog).log(eq(AuditLogEntity.AuditAction.CREATE),
            eq("player_erasure_request"), eq(req.id), eq("p0"), contains("[truncated]"),
            eq(AuditLogEntity.AuditResult.SUCCESS), eq("op"),
            isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("已删游戏上创建请求被拒绝（deletedAt 非空）")
    void createOnDeletedGameRejected() {
        GameEntity deleted = new GameEntity();
        deleted.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("gD")).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.create("gD", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("序列化失败：create 抛出带原因的 IAE")
    void createToJsonFailure() throws Exception {
        when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any()))
            .thenThrow(new RuntimeException("no jackson"));
        assertThatThrownBy(() -> service.create("g1", "PLAYER_ID", "p1", null, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serialize");
    }

    @Test
    @DisplayName("CH 段成功但审计写失败 → PARTIAL（清洗已落库不回滚，重试幂等）")
    void processAuditFailureAfterChBecomesPartial() {
        PlayerErasureRequestEntity req = savedRequest();
        when(requestRepo.findById(req.id)).thenReturn(Optional.of(req));
        when(clickHouse.isAvailable()).thenReturn(false);
        stubPgDeletes();
        when(auditLog.log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("audit store down"))
            .thenReturn(null);   // catch 块内的失败审计写放行，避免异常从 catch 逃逸

        service.process(req.id);

        assertThat(req.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
        assertThat(req.errorMessage).contains("audit store down");
        // 重试计数递增：catch 路径 canRetry=true 分支
        assertThat(req.executionSummary).contains("\"retries\":1");
    }

    @Test
    @DisplayName("sweep：PARTIAL 重试内部异常被逐请求捕获，外层异常不影响方法返回")
    void sweepCatchesRetryAndOuterFailures() {
        PlayerErasureRequestEntity broken = savedRequest();
        broken.status = PlayerErasureRequestEntity.Status.PARTIAL;
        broken.executionSummary = "{\"pgDone\":false}";   // 无 pgDone → process 抛 ISE → ③ 内 catch
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("stale query down"));   // ① 抛 → ② ③ 跳过
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(broken));
        when(requestRepo.findById(broken.id)).thenReturn(Optional.of(broken));

        service.sweep();   // 外层 catch 吞掉 ① 异常；③ 不会执行（外层已中断）

        // 外层 catch 生效：方法正常返回，broken 未被触碰
        assertThat(broken.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
    }

    @Test
    @DisplayName("sweep：PARTIAL 重试内部异常被逐请求捕获")
    void sweepCatchesRetryFailure() {
        PlayerErasureRequestEntity broken = savedRequest();
        broken.status = PlayerErasureRequestEntity.Status.PARTIAL;
        broken.executionSummary = "{\"pgDone\":false}";   // 无 pgDone → process 抛 ISE
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL))
            .thenReturn(List.of(broken));
        when(requestRepo.findById(broken.id)).thenReturn(Optional.of(broken));

        service.sweep();   // ③ 的 try-catch 吞掉 ISE

        assertThat(broken.status).isEqualTo(PlayerErasureRequestEntity.Status.PARTIAL);
    }

    @Test
    @DisplayName("PG 清洗：导出文件路径非法时告警跳过不中断")
    void purgePostgresExportFileBadPath() {
        PlayerErasureService.ResolvedIdentities ids = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        PlayerExportJobEntity badPathJob = new PlayerExportJobEntity();
        badPathJob.filePath = "bad\0path";
        when(exportJobRepo.findByGameIdAndPlayerIdIn(eq("g1"), anyList()))
            .thenReturn(List.of(badPathJob));
        when(identityRepo.deleteAllByIdIn(anyList())).thenReturn(0);
        when(identityLinkRepo.deleteByIdentityIdIn(anyList())).thenReturn(0);
        when(loginLogRepo.deleteByGameIdAndPlayerIdInOrDeviceIdIn(eq("g1"), anyList(), anyList())).thenReturn(0);
        when(paymentRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(redeemRecordRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(mailClaimRepo.deleteByGameIdAndPlayerKeyIn(eq("g1"), anyList())).thenReturn(0);
        when(exportJobRepo.deleteByGameIdAndPlayerIdIn(eq("g1"), anyList())).thenReturn(0);
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString())).thenReturn(List.of());
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(eq("g1"), anyList())).thenReturn(List.of());
        when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(eq("g1"), anyList(), anyList()))
            .thenReturn(List.of());

        Map<String, Long> pg = service.purgePostgres("g1", ids, "per_x");

        assertThat(pg.get("export_files_deleted")).isEqualTo(0L);   // 非法路径被 catch 后继续
    }
}
