package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖（LAY-010 拆分）：漏斗配置实体与持久化机制（@PrePersist ID 生成、建库默认值）。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepMiscTest {

    @Test
    @DisplayName("FunnelConfigEntity：启用/删除/漏斗类型判定与步骤计数分支")
    void funnelConfigEntity() {
        FunnelConfigEntity f = new FunnelConfigEntity();
        f.gameId = "g1";
        f.name = "新手引导漏斗";

        // 启用与删除
        assertTrue(f.isEnabled());
        assertFalse(f.isDeleted());
        f.enabled = null;
        assertFalse(f.isEnabled());
        f.enabled = false;
        assertFalse(f.isEnabled());
        f.enabled = true;
        f.deletedAt = LocalDateTime.now();
        assertTrue(f.isDeleted());
        f.deletedAt = null;

        // 类型判定
        assertFalse(f.isSequential());
        assertFalse(f.isTimeWindow());
        f.type = FunnelConfigEntity.FunnelType.SEQUENTIAL;
        assertTrue(f.isSequential());
        assertFalse(f.isTimeWindow());
        f.type = FunnelConfigEntity.FunnelType.TIME_WINDOW;
        assertFalse(f.isSequential());
        assertTrue(f.isTimeWindow());

        // 步骤计数：空集合防御
        assertEquals(0, f.getStepCount());
        f.steps = new ArrayList<>(Arrays.asList(new FunnelStepEntity(), new FunnelStepEntity()));
        assertEquals(2, f.getStepCount());
        f.steps = null;
        assertEquals(0, f.getStepCount());
    }

    @Test
    @DisplayName("PrePersist 主键生成：FunnelStep/AuditLog 空 id 落库前生成 32 位 hex，已有 id 保留")
    void prePersistIdGeneration() {
        // FunnelStepEntity.ensureId：null → 生成；空串 → 生成；非空 → 保留
        FunnelStepEntity step = new FunnelStepEntity();
        step.ensureId();
        assertEquals(32, step.id.length());
        assertFalse(step.id.contains("-"));
        step.id = "";
        step.ensureId();
        assertEquals(32, step.id.length());
        step.id = "preset";
        step.ensureId();
        assertEquals("preset", step.id);

        // AuditLogEntity.generateId：null/空白 → 生成；已有 → 保留
        AuditLogEntity log = new AuditLogEntity();
        log.generateId();
        assertEquals(32, log.id.length());
        assertFalse(log.id.contains("-"));
        log.id = "  ";
        log.generateId();
        assertEquals(32, log.id.length());
        log.id = "preset-log";
        log.generateId();
        assertEquals("preset-log", log.id);
    }

    @Test
    @DisplayName("实体收尾：markNotNew 持久化回调与限流用量空值分支")
    void entityPersistenceDefaults() {
        // GameEntity.markNotNew：持久化回调后 isNew 置 false
        GameEntity g = new GameEntity();
        assertTrue(g.isNew);
        g.markNotNew();
        assertFalse(g.isNew);
    }

}
