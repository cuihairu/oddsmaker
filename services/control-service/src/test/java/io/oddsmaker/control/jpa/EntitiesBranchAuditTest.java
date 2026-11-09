package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：AuditLogAction/TrackingPlan/Integration/Sso/Cohort：动作侧、计划侧、集成与 SSO 侧、Cohort 空计数器侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchAuditTest {

    @Test
    @DisplayName("AuditLog：认证动作五枚举全真侧、数据动作全枚举与非数据侧")
    void auditLogActionSides() {
        AuditLogEntity a = new AuditLogEntity();
        for (AuditLogEntity.AuditAction act : new AuditLogEntity.AuditAction[]{
                AuditLogEntity.AuditAction.LOGIN, AuditLogEntity.AuditAction.LOGOUT,
                AuditLogEntity.AuditAction.LOGIN_FAILED, AuditLogEntity.AuditAction.PASSWORD_CHANGE,
                AuditLogEntity.AuditAction.PASSWORD_RESET}) {
            a.action = act;
            assertTrue(a.isAuthAction(), act + " 应为认证动作");
            assertFalse(a.isDataAction(), act + " 不应为数据动作");
        }
        for (AuditLogEntity.AuditAction act : new AuditLogEntity.AuditAction[]{
                AuditLogEntity.AuditAction.CREATE, AuditLogEntity.AuditAction.UPDATE,
                AuditLogEntity.AuditAction.DELETE, AuditLogEntity.AuditAction.EXPORT,
                AuditLogEntity.AuditAction.IMPORT}) {
            a.action = act;
            assertTrue(a.isDataAction(), act + " 应为数据动作");
            assertFalse(a.isAuthAction(), act + " 不应为认证动作");
        }
    }

    @Test
    @DisplayName("TrackingPlan：isDraft/canEdit 的非草稿与软删侧")
    void trackingPlanSides() {
        TrackingPlanEntity t = new TrackingPlanEntity();
        t.status = TrackingPlanEntity.PlanStatus.DRAFT;
        assertTrue(t.isDraft());
        assertTrue(t.canEdit());
        t.deletedAt = LocalDateTime.now();
        assertFalse(t.isDraft());    // 草稿但软删
        assertFalse(t.canEdit());
        t.deletedAt = null;
        t.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        assertFalse(t.isDraft());
        assertFalse(t.canEdit());
    }

    @Test
    @DisplayName("IntegrationLog：TIMEOUT 失败侧；SSOConfig：DISABLED 对侧")
    void integrationAndSsoSides() {
        IntegrationLogEntity i = new IntegrationLogEntity();
        i.callStatus = IntegrationLogEntity.CallStatus.TIMEOUT;
        assertTrue(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.FAILED;
        assertTrue(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.SUCCESS;
        assertFalse(i.isFailed());
        i.callStatus = IntegrationLogEntity.CallStatus.RETRYING;
        assertFalse(i.isFailed());

        SSOConfigEntity s = new SSOConfigEntity();
        s.ssoStatus = SSOConfigEntity.SSOStatus.DISABLED;
        assertTrue(s.isDisabled());
        s.ssoStatus = SSOConfigEntity.SSOStatus.ACTIVE;
        assertFalse(s.isDisabled());
        assertTrue(s.isActive());
    }

    @Test
    @DisplayName("Cohort：totalCalculations null 起点递增")
    void cohortNullCounterSide() {
        CohortEntity c = new CohortEntity();
        c.totalCalculations = null;
        c.markAsCompleted(10L, "{}", "sum", 5L);
        assertEquals(1L, c.totalCalculations);   // null → 1（三元 null 侧）
        c.markAsCompleted(11L, "{}", "sum", 5L);
        assertEquals(2L, c.totalCalculations);
    }

}
