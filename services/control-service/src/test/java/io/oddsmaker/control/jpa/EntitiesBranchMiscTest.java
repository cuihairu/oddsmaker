package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：通用 onCreate 预置、软删 active 对侧、Report/Announcement/杂项实体侧、Segment/Dashboard ensureId 空 id 生成侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchMiscTest {

    @Test
    @DisplayName("MLModel/TelemetryConfig/HealthCheck：onCreate false 侧")
    void onCreatePresets() {
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        MLModelEntity m = new MLModelEntity();
        m.createdAt = preset;
        m.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        m.onCreate();
        assertEquals(preset, m.createdAt);
        assertEquals(MLModelEntity.ModelStatus.DEPLOYED, m.modelStatus);

        TelemetryConfigEntity t = new TelemetryConfigEntity();
        t.createdAt = preset;
        t.configStatus = TelemetryConfigEntity.ConfigStatus.ACTIVE;
        t.onCreate();
        assertEquals(preset, t.createdAt);
        assertEquals(TelemetryConfigEntity.ConfigStatus.ACTIVE, t.configStatus);

        HealthCheckEntity h = new HealthCheckEntity();
        h.createdAt = preset;
        h.onCreate();
        assertEquals(preset, h.createdAt);
    }

    @Test
    @DisplayName("ReportExecution：PENDING 对侧；Announcement：软删与非发布侧")
    void reportAndAnnouncementSides() {
        ReportExecutionEntity r = new ReportExecutionEntity();
        r.executionStatus = ReportExecutionEntity.ExecutionStatus.PENDING;
        assertTrue(r.isPending());
        r.executionStatus = ReportExecutionEntity.ExecutionStatus.RUNNING;
        assertFalse(r.isPending());

        AnnouncementEntity a = new AnnouncementEntity();
        a.status = AnnouncementEntity.Status.PUBLISHED;
        assertTrue(a.isActive());
        a.deletedAt = LocalDateTime.now();
        assertFalse(a.isActive());   // 已发布但软删
        a.deletedAt = null;
        a.status = AnnouncementEntity.Status.DRAFT;
        assertFalse(a.isActive());
    }

    @Test
    @DisplayName("Permission：isSystem 三态；Pipeline：runCount null/0；SystemAlert：OPEN 对侧")
    void miscEntitySides() {
        PermissionEntity p = new PermissionEntity();
        p.system = null;
        assertFalse(p.isSystem());
        p.system = true;
        assertTrue(p.isSystem());
        p.system = false;
        assertFalse(p.isSystem());

        PipelineEntity pl = new PipelineEntity();
        pl.runCount = null;
        assertEquals(0.0, pl.getSuccessRate());   // null 侧
        pl.runCount = 0;
        assertEquals(0.0, pl.getSuccessRate());
        pl.runCount = 4;
        pl.successCount = 3;
        assertEquals(75.0, pl.getSuccessRate());

        SystemAlertEntity sa = new SystemAlertEntity();
        sa.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        assertTrue(sa.isOpen());
        sa.alertStatus = SystemAlertEntity.AlertStatus.RESOLVED;
        assertFalse(sa.isOpen());
        sa.alertStatus = null;
        assertFalse(sa.isOpen());
    }

    @Test
    @DisplayName("RiskRule/SecurityPolicy/EventPropertyDefinition：isActive 三条件全侧")
    void activeWithSoftDeleteSides() {
        RiskRuleEntity rr = new RiskRuleEntity();
        rr.status = RiskRuleEntity.RuleStatus.ACTIVE;
        assertTrue(rr.isActive());
        rr.deletedAt = LocalDateTime.now();
        assertFalse(rr.isActive());
        rr.deletedAt = null;
        rr.status = RiskRuleEntity.RuleStatus.PAUSED;
        assertFalse(rr.isActive());

        SecurityPolicyEntity sp = new SecurityPolicyEntity();
        sp.enabled = null;
        assertFalse(sp.isEnabled());
        sp.enabled = false;
        assertFalse(sp.isEnabled());
        sp.enabled = true;
        assertTrue(sp.isEnabled());
        sp.deletedAt = LocalDateTime.now();
        assertFalse(sp.isEnabled());

        EventPropertyDefinitionEntity ep = new EventPropertyDefinitionEntity();
        ep.status = EventPropertyDefinitionEntity.PropertyStatus.ACTIVE;
        assertTrue(ep.isActive());
        ep.deletedAt = LocalDateTime.now();
        assertFalse(ep.isActive());
        ep.deletedAt = null;
        ep.status = EventPropertyDefinitionEntity.PropertyStatus.DISABLED;
        assertFalse(ep.isActive());
    }

    @Test
    @DisplayName("Segment/Dashboard：@PrePersist ensureId 的 null/空串/已有 id 三侧（同包直调）")
    void segmentAndDashboardEnsureIdSides() {
        SegmentEntity s = new SegmentEntity();
        s.ensureId();
        assertNotNull(s.id);
        assertEquals(32, s.id.length());   // UUID 去连字符

        SegmentEntity sBlank = new SegmentEntity();
        sBlank.id = "";
        sBlank.ensureId();
        assertNotNull(sBlank.id);
        assertEquals(32, sBlank.id.length());   // 空串侧同样生成

        DashboardEntity d = new DashboardEntity();
        d.ensureId();
        assertNotNull(d.id);
        assertEquals(32, d.id.length());

        DashboardEntity dPreset = new DashboardEntity();
        dPreset.id = "preset";
        dPreset.ensureId();
        assertEquals("preset", dPreset.id);   // 已有 id 不覆盖（false 侧）

        // 逐实体对侧补齐：Segment 已有 id 不覆盖（b-F 侧）、Dashboard 空串生成（b-T 侧）
        SegmentEntity sPreset = new SegmentEntity();
        sPreset.id = "preset";
        sPreset.ensureId();
        assertEquals("preset", sPreset.id);   // 已有 id 不覆盖

        DashboardEntity dBlank = new DashboardEntity();
        dBlank.id = "";
        dBlank.ensureId();
        assertNotNull(dBlank.id);
        assertEquals(32, dBlank.id.length()); // 空串侧同样生成
    }

}
