package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.MailClaimEntity;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 运营邮件系统测试：全服/个人邮件、附件校验、领取幂等、过期清理。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营邮件系统测试")
class MailServiceTest {

    @Mock
    private MailRepo mailRepo;

    @Mock
    private MailClaimRepo claimRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private MailService service;

    private final GameEntity game = new GameEntity();

    @BeforeEach
    void setUp() {
        game.id = "game_demo";
    }

    private static MailEntity mail(MailEntity.Scope scope, String recipients) {
        MailEntity m = new MailEntity();
        m.gameId = "game_demo";
        m.title = "补偿邮件";
        m.content = "服务器故障补偿";
        m.scope = scope;
        m.recipients = recipients;
        m.attachments = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        return m;
    }

    private void stubGame() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
    }

    @Test
    @DisplayName("创建：全服邮件默认草稿")
    void createAllScopeMail() {
        stubGame();
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MailEntity created = service.create(mail(MailEntity.Scope.ALL, null), "op_1");
        assertEquals(MailEntity.Status.DRAFT, created.status);
        assertNotNull(created.id);
    }

    @Test
    @DisplayName("创建：个人邮件必须有收件人")
    void individualMailRequiresRecipients() {
        stubGame();
        assertThrows(IllegalArgumentException.class,
            () -> service.create(mail(MailEntity.Scope.INDIVIDUAL, null), "op_1"));
        verify(mailRepo, never()).save(any());
    }

    @Test
    @DisplayName("创建：附件必须是合法 JSON 数组且含 type/id")
    void attachmentsValidated() {
        stubGame();
        MailEntity bad1 = mail(MailEntity.Scope.ALL, null);
        bad1.attachments = "not-json";
        assertThrows(IllegalArgumentException.class, () -> service.create(bad1, "op_1"));

        MailEntity bad2 = mail(MailEntity.Scope.ALL, null);
        bad2.attachments = "[{\"type\":\"item\"}]";  // 缺 id
        assertThrows(IllegalArgumentException.class, () -> service.create(bad2, "op_1"));

        MailEntity bad3 = mail(MailEntity.Scope.ALL, null);
        bad3.attachments = "[\"plain\"]";
        assertThrows(IllegalArgumentException.class, () -> service.create(bad3, "op_1"));
    }

    @Test
    @DisplayName("创建：过期时间必须在未来")
    void expireAtMustBeFuture() {
        stubGame();
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.expireAt = LocalDateTime.now().minusHours(1);
        assertThrows(IllegalArgumentException.class, () -> service.create(m, "op_1"));
    }

    @Test
    @DisplayName("发送：仅草稿可发送")
    void onlyDraftCanBeSent() {
        MailEntity sent = mail(MailEntity.Scope.ALL, null);
        sent.id = "mail_1";
        sent.status = MailEntity.Status.SENT;
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(sent));
        assertThrows(IllegalStateException.class, () -> service.send("mail_1", "op_1"));
    }

    @Test
    @DisplayName("领取：全服邮件任何玩家可领且幂等")
    void claimAllMailIdempotent() {
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.id = "mail_2";
        m.status = MailEntity.Status.SENT;
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_2")).thenReturn(Optional.of(m));
        when(claimRepo.findByMailIdAndPlayerKey("mail_2", "player_1")).thenReturn(Optional.empty());
        when(claimRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MailClaimEntity first = service.claim("mail_2", "player_1");
        assertNotNull(first);
        assertEquals("player_1", first.playerKey);
        assertEquals(m.attachments, first.claimedAttachments);  // 附件快照固化

        // 二次领取：返回已有记录
        MailClaimEntity existing = new MailClaimEntity();
        existing.mailId = "mail_2";
        existing.playerKey = "player_1";
        when(claimRepo.findByMailIdAndPlayerKey("mail_2", "player_1")).thenReturn(Optional.of(existing));
        MailClaimEntity second = service.claim("mail_2", "player_1");
        assertSame(existing, second);
        verify(claimRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("领取：个人邮件仅收件人可领")
    void individualMailOnlyForRecipients() {
        MailEntity m = mail(MailEntity.Scope.INDIVIDUAL, "alice, bob");
        m.id = "mail_3";
        m.status = MailEntity.Status.SENT;
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_3")).thenReturn(Optional.of(m));
        when(claimRepo.findByMailIdAndPlayerKey("mail_3", "carol")).thenReturn(Optional.empty());

        assertNull(service.claim("mail_3", "carol"));  // 非收件人不可领
        verify(claimRepo, never()).save(any());
    }

    @Test
    @DisplayName("领取：过期邮件拒绝")
    void expiredMailNotClaimable() {
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.id = "mail_4";
        m.status = MailEntity.Status.SENT;
        m.expireAt = LocalDateTime.now().minusMinutes(1);
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_4")).thenReturn(Optional.of(m));
        when(claimRepo.findByMailIdAndPlayerKey("mail_4", "p")).thenReturn(Optional.empty());

        assertNull(service.claim("mail_4", "p"));
    }

    @Test
    @DisplayName("领取：并发唯一约束冲突时返回既有记录")
    void concurrentClaimReturnsExisting() {
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.id = "mail_5";
        m.status = MailEntity.Status.SENT;
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_5")).thenReturn(Optional.of(m));
        MailClaimEntity winner = new MailClaimEntity();
        winner.mailId = "mail_5";
        winner.playerKey = "p";
        // 第一次查（领取前检查）为空，save 抛唯一约束冲突，第二次查（兜底）返回已存在记录
        when(claimRepo.findByMailIdAndPlayerKey("mail_5", "p"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(claimRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        MailClaimEntity claim = service.claim("mail_5", "p");
        assertSame(winner, claim);
    }

    @Test
    @DisplayName("过期清理：到期已发送邮件标记 EXPIRED")
    void sweepExpiresDueMails() {
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.id = "mail_6";
        m.status = MailEntity.Status.SENT;
        m.expireAt = LocalDateTime.now().minusMinutes(2);
        when(mailRepo.findByStatusAndExpireAtLessThanEqualAndDeletedAtIsNull(
            eq(MailEntity.Status.SENT), any(LocalDateTime.class)))
            .thenReturn(List.of(m));
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sweep();

        assertEquals(MailEntity.Status.EXPIRED, m.status);
        verify(auditLog).logUpdate(eq("op_mail"), eq("mail_6"), eq("补偿邮件"),
            eq("scheduler"), eq("scheduler"), isNull(), anyMap());
    }

    @Test
    @DisplayName("收件人匹配：精确匹配避免子串误命中")
    void recipientMatchingIsExact() {
        assertTrue(MailEntity.containsRecipient("alice, bob", "alice"));
        assertTrue(MailEntity.containsRecipient("alice, bob", "bob"));
        assertFalse(MailEntity.containsRecipient("alice, bob", "ali"));
        assertFalse(MailEntity.containsRecipient("alice, bob", "bobby"));
        assertFalse(MailEntity.containsRecipient("", "alice"));
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("创建：title/content 缺失与空白收件人、未来过期时间通过")
    void createValidationSides() {
        stubGame();
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MailEntity noTitle = mail(MailEntity.Scope.ALL, null);
        noTitle.title = null;
        assertThrows(IllegalArgumentException.class, () -> service.create(noTitle, "op_1"));
        MailEntity blankTitle = mail(MailEntity.Scope.ALL, null);
        blankTitle.title = "  ";
        assertThrows(IllegalArgumentException.class, () -> service.create(blankTitle, "op_1"));

        MailEntity noContent = mail(MailEntity.Scope.ALL, null);
        noContent.content = null;
        assertThrows(IllegalArgumentException.class, () -> service.create(noContent, "op_1"));
        MailEntity blankContent = mail(MailEntity.Scope.ALL, null);
        blankContent.content = " ";
        assertThrows(IllegalArgumentException.class, () -> service.create(blankContent, "op_1"));

        // INDIVIDUAL 收件人为空白串（isBlank 侧）
        MailEntity blankRcpts = mail(MailEntity.Scope.INDIVIDUAL, "   ");
        assertThrows(IllegalArgumentException.class, () -> service.create(blankRcpts, "op_1"));

        // 未来过期时间：!isAfter false 侧 → 校验通过
        MailEntity future = mail(MailEntity.Scope.ALL, null);
        future.expireAt = LocalDateTime.now().plusDays(1);
        assertEquals(MailEntity.Status.DRAFT, service.create(future, "op_1").status);
        verify(mailRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("创建：附件 null/空白直通（validateAttachments 早退侧）；缺 type 拒绝")
    void attachmentsNullBlankAndMissingType() {
        stubGame();
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MailEntity noAtt = mail(MailEntity.Scope.ALL, null);
        noAtt.attachments = null;
        assertEquals(MailEntity.Status.DRAFT, service.create(noAtt, "op_1").status);

        MailEntity blankAtt = mail(MailEntity.Scope.ALL, null);
        blankAtt.attachments = "   ";
        assertEquals(MailEntity.Status.DRAFT, service.create(blankAtt, "op_1").status);

        // 附件对象缺 type（m.get("type") == null 侧）
        MailEntity noType = mail(MailEntity.Scope.ALL, null);
        noType.attachments = "[{\"id\":\"gem\"}]";
        assertThrows(IllegalArgumentException.class, () -> service.create(noType, "op_1"));
    }

    @Test
    @DisplayName("发送：草稿带过期时间已过拒绝，未来过期时间正常发送")
    void sendChecksExpireAtSides() {
        MailEntity expired = mail(MailEntity.Scope.ALL, null);
        expired.id = "mail_exp_send";
        expired.status = MailEntity.Status.DRAFT;
        expired.expireAt = LocalDateTime.now().minusMinutes(1);
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_exp_send")).thenReturn(Optional.of(expired));
        assertThrows(IllegalStateException.class, () -> service.send("mail_exp_send", "op_1"));

        MailEntity ok = mail(MailEntity.Scope.ALL, null);
        ok.id = "mail_ok_send";
        ok.status = MailEntity.Status.DRAFT;
        ok.expireAt = LocalDateTime.now().plusDays(1);
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_ok_send")).thenReturn(Optional.of(ok));
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertEquals(MailEntity.Status.SENT, service.send("mail_ok_send", "op_1").status);

        // 无过期时间（expireAt == null 侧）：草稿直接可发
        MailEntity noExp = mail(MailEntity.Scope.ALL, null);
        noExp.id = "mail_noexp_send";
        noExp.status = MailEntity.Status.DRAFT;
        when(mailRepo.findByIdAndDeletedAtIsNull("mail_noexp_send")).thenReturn(Optional.of(noExp));
        assertEquals(MailEntity.Status.SENT, service.send("mail_noexp_send", "op_1").status);
    }

    @Test
    @DisplayName("收件箱：playerKey null/空白拒绝；environmentId null 回落空串")
    void inboxPlayerKeyAndEnvironmentSides() {
        stubGame();
        assertThrows(IllegalArgumentException.class, () -> service.inbox("game_demo", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.inbox("game_demo", null, "  "));

        when(mailRepo.findInbox(eq("game_demo"), eq(""), eq("p1"), any(LocalDateTime.class)))
            .thenReturn(List.of());
        service.inbox("game_demo", null, "p1");
        verify(mailRepo).findInbox(eq("game_demo"), eq(""), eq("p1"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("领取：playerKey null/空白拒绝；软删游戏拒绝")
    void claimPlayerKeySidesAndDeletedGame() {
        assertThrows(IllegalArgumentException.class, () -> service.claim("mail_1", null));
        assertThrows(IllegalArgumentException.class, () -> service.claim("mail_1", "  "));

        game.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        assertThrows(IllegalArgumentException.class, () -> service.list("game_demo"));
    }
}
