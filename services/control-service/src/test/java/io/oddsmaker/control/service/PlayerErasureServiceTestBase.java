package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestRepo;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 玩家数据删除请求服务测试公共基类（LAY-014 自 PlayerErasureServiceTest 拆出）：
 * 共享全部 @Mock/@Spy/@InjectMocks 字段（原类 LENIENT，窄类未用的 mock 安全）、@TempDir、
 * setUp 反射注入与默认 stub、identity/link/savedRequest 样例与 stubPgDeletes/stubChHappyPath。
 */
abstract class PlayerErasureServiceTestBase {

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

    protected static IdentityEntity identity(String id, String userId, String playerId,
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

    protected static IdentityLinkEntity link(String identityId, String type, String linkedId) {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.identityId = identityId;
        l.linkedIdentityType = type;
        l.linkedId = linkedId;
        return l;
    }

    /** 已固化的 PENDING 请求（resolvedIdentities：idt1/u1/p1/d1） */
    protected static PlayerErasureRequestEntity savedRequest() {
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

    protected void stubPgDeletes() {
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

    /** CH 全通 stub：预估/基线/轮询 query + execute 回调注入 mock Statement 收集 SQL 文本 */
    protected void stubChHappyPath(List<String> sqlLog) throws Exception {
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

}
