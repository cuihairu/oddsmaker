package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FunnelConfigServiceTest {

    @Mock
    private FunnelConfigRepo funnelConfigRepo;

    @Mock
    private FunnelStepRepo funnelStepRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private FunnelConfigService funnelConfigService;

    private FunnelConfigEntity testFunnel;
    private FunnelStepEntity testStep;

    @BeforeEach
    void setUp() {
        // 创建测试步骤
        testStep = new FunnelStepEntity();
        testStep.id = "s1";
        testStep.stepOrder = 1;
        testStep.name = "Level Start";
        testStep.eventName = "level_start";

        // 创建测试漏斗
        testFunnel = new FunnelConfigEntity();
        testFunnel.id = "funnel_test123";
        testFunnel.gameId = "game_123";
        testFunnel.name = "Level Funnel";
        testFunnel.description = "Level progression funnel";
        testFunnel.type = FunnelConfigEntity.FunnelType.STANDARD;
        testFunnel.userKey = "user_id";
        testFunnel.enabled = true;
        testFunnel.steps = new ArrayList<>(List.of(testStep));
        testFunnel.createdAt = LocalDateTime.now();
        testFunnel.updatedAt = LocalDateTime.now();
    }

    @Test
    void createFunnel_Success() {
        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_123", "Level Funnel"))
            .thenReturn(false);
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenReturn(testFunnel);
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenReturn(testStep);

        FunnelConfigEntity result = funnelConfigService.createFunnel(testFunnel);

        assertNotNull(result);
        assertEquals("Level Funnel", result.name);
        verify(funnelConfigRepo).save(any(FunnelConfigEntity.class));
        verify(funnelStepRepo).save(any(FunnelStepEntity.class));
    }

    @Test
    void createFunnel_NameExists_ThrowsException() {
        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_123", "Level Funnel"))
            .thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> {
            funnelConfigService.createFunnel(testFunnel);
        });

        verify(funnelConfigRepo, never()).save(any());
    }

    @Test
    void updateFunnel_Success() {
        FunnelConfigEntity updates = new FunnelConfigEntity();
        updates.name = "Updated Funnel";
        updates.description = "Updated description";

        when(funnelConfigRepo.findById("funnel_test123")).thenReturn(Optional.of(testFunnel));
        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_123", "Updated Funnel"))
            .thenReturn(false);
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenReturn(testFunnel);

        FunnelConfigEntity result = funnelConfigService.updateFunnel("funnel_test123", updates);

        assertNotNull(result);
        verify(funnelConfigRepo).save(any(FunnelConfigEntity.class));
    }

    @Test
    void updateFunnel_NotFound_ThrowsException() {
        when(funnelConfigRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            funnelConfigService.updateFunnel("nonexistent", new FunnelConfigEntity());
        });
    }

    @Test
    void deleteFunnel_Success() {
        when(funnelConfigRepo.findById("funnel_test123")).thenReturn(Optional.of(testFunnel));
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenReturn(testFunnel);

        funnelConfigService.deleteFunnel("funnel_test123");

        assertNotNull(testFunnel.deletedAt);
        verify(funnelConfigRepo).save(testFunnel);
    }

    @Test
    void findById_Success() {
        when(funnelConfigRepo.findById("funnel_test123")).thenReturn(Optional.of(testFunnel));

        FunnelConfigEntity result = funnelConfigService.findById("funnel_test123");

        assertNotNull(result);
        assertEquals("Level Funnel", result.name);
    }

    @Test
    void findById_NotFound_ThrowsException() {
        when(funnelConfigRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            funnelConfigService.findById("nonexistent");
        });
    }

    @Test
    void findByGameId_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<FunnelConfigEntity> page = new PageImpl<>(List.of(testFunnel));

        when(funnelConfigRepo.findByGameIdAndDeletedAtIsNull("game_123", pageable))
            .thenReturn(page);

        Page<FunnelConfigEntity> result = funnelConfigService.findByGameId("game_123", pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
    }

    @Test
    void findEnabledByGameId_Success() {
        when(funnelConfigRepo.findByGameIdAndEnabledTrueAndDeletedAtIsNull("game_123"))
            .thenReturn(List.of(testFunnel));

        List<FunnelConfigEntity> result = funnelConfigService.findEnabledByGameId("game_123");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void toggleFunnel_Success() {
        when(funnelConfigRepo.findById("funnel_test123")).thenReturn(Optional.of(testFunnel));
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenReturn(testFunnel);

        FunnelConfigEntity result = funnelConfigService.toggleFunnel("funnel_test123", false);

        assertNotNull(result);
        assertFalse(result.enabled);
        verify(funnelConfigRepo).save(testFunnel);
    }

    @Test
    void addStep_Success() {
        FunnelStepEntity newStep = new FunnelStepEntity();
        newStep.name = "Level Complete";
        newStep.eventName = "level_complete";

        when(funnelConfigRepo.findById("funnel_test123")).thenReturn(Optional.of(testFunnel));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenReturn(newStep);

        FunnelStepEntity result = funnelConfigService.addStep("funnel_test123", newStep);

        assertNotNull(result);
        assertEquals("Level Complete", result.name);
        verify(funnelStepRepo).save(any(FunnelStepEntity.class));
    }

    @Test
    void updateStep_Success() {
        FunnelStepEntity updates = new FunnelStepEntity();
        updates.name = "Updated Step";
        updates.eventName = "updated_event";

        when(funnelStepRepo.findById("s1")).thenReturn(Optional.of(testStep));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenReturn(testStep);

        FunnelStepEntity result = funnelConfigService.updateStep("s1", updates);

        assertNotNull(result);
        verify(funnelStepRepo).save(any(FunnelStepEntity.class));
    }

    @Test
    void updateStep_NullOptionalKept() {
        // optional 有 false 初始化器：HTTP 链路恒非 null，null 跳过侧须显式置 null 直达
        testStep.optional = true;
        FunnelStepEntity updates = new FunnelStepEntity();
        updates.optional = null;

        when(funnelStepRepo.findById("s1")).thenReturn(Optional.of(testStep));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenReturn(testStep);

        funnelConfigService.updateStep("s1", updates);

        assertTrue(testStep.optional);  // null patch 跳过，原值保留
    }

    @Test
    void deleteStep_Success() {
        when(funnelStepRepo.findById("s1")).thenReturn(Optional.of(testStep));

        funnelConfigService.deleteStep("s1");

        verify(funnelStepRepo).delete(testStep);
    }

    @Test
    void searchByName_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<FunnelConfigEntity> page = new PageImpl<>(List.of(testFunnel));

        when(funnelConfigRepo.searchByName("game_123", "Level", pageable))
            .thenReturn(page);

        Page<FunnelConfigEntity> result = funnelConfigService.searchByName("game_123", "Level", pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
    }
}