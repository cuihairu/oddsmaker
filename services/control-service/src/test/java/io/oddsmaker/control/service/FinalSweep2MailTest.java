package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：MailService.sweep——到期 SENT 邮件标记 EXPIRED 并审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2MailTest {

    @Mock
    private MailRepo mailRepo;

    @Mock
    private MailClaimRepo mailClaimRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private MailService mailService;

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
