package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.MFAConfigEntity;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：SecurityService.disableMFA——不存在拒绝、非本人拒绝、成功禁用并审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2SecurityTest {

    @Mock
    private MFAConfigRepo mfaConfigRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private SecurityService securityService;

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

}
