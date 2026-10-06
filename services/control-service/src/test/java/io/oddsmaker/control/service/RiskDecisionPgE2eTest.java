package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.RiskCaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划书 §5.4 B6 段的真 PG/CH 端到端：完整 Spring 上下文（真 Flyway 全量迁移 + 真
 * RiskEventConsumer 消费链）验证 Decision 状态机落库与处置全链路——
 * <ul>
 *   <li>BLOCK+HIGH → risk_cases 判定 BLOCK + block_lists 落名单 + audit_logs SECURITY_ALERT
 *       + risk_actions 归档（CH，携带 risk_case_id）；</li>
 *   <li>BLOCK+非 HIGH → trust 门槛 fail-closed 降级 REVIEW（[trust_gate] 标记入审核队列，
 *       不进名单）；</li>
 *   <li>ALERT→THROTTLE 跨事件严格升层；BLOCK 之后同层/回退判定拒绝（decision_rejected 归档）。</li>
 * </ul>
 * Kafka 监听关闭（auto-startup=false），直接驱动 onRiskEvent，事件 JSON 与 RiskJob.toJson 输出同构。
 *
 * 门禁：-Drisk.pg.e2e=true 才运行（CI 无 PG 自跳过；本地配合容器跑，用完删容器）。
 * 默认连 jdbc:postgresql://127.0.0.1:15434/oddsmaker（可用 -Drisk.pg.e2e.url/user/pass 覆盖）
 * 与 jdbc:clickhouse://127.0.0.1:18123/default（-Drisk.ch.e2e.url/user/pass 覆盖）。
 * 首跑由 Flyway 在空库上全量迁移（V0.1 → V0.9.19），与 demo 部署同路径。
 */
@DisplayName("B6 §5.4 真 PG/CH 端到端：Decision 状态机 + trust 门槛 + risk_actions 归档")
@EnabledIfSystemProperty(named = "risk.pg.e2e", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=${risk.pg.e2e.url:jdbc:postgresql://127.0.0.1:15434/oddsmaker}",
    "spring.datasource.username=${risk.pg.e2e.user:oddsmaker}",
    "spring.datasource.password=${risk.pg.e2e.pass:oddsmaker}",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.kafka.listener.auto-startup=false",   // e2e 直接驱动 consumer，不消费
    "management.health.kafka.enabled=false",
    "oddsmaker.clickhouse.url=${risk.ch.e2e.url:jdbc:clickhouse://127.0.0.1:18123/default}",
    "oddsmaker.clickhouse.user=${risk.ch.e2e.user:oddsmaker}",
    "oddsmaker.clickhouse.password=${risk.ch.e2e.pass:oddsmaker}",
})
class RiskDecisionPgE2eTest {

    @Autowired RiskEventConsumer consumer;
    @Autowired JdbcTemplate pg;                 // 指向 PG 容器的真 DataSource
    @Autowired ClickHouseClient clickHouseClient;

    /**
     * 事件引用的主数据种子：risk_cases/block_lists/review_queues 对 game_id/environment_id/risk_rule_id
     * 均有外键（V0.4.1），事件 environment='prod' 须有 game_environments 行（空库迁移不种子环境），
     * rule_id 取 V0.4.1 种子规则（rule_resource_spike，game DEFAULT）。
     */
    @BeforeEach
    void ensureE2eSeeds() {
        pg.update("INSERT INTO game_environments (id, game_id, name, type, status) "
                + "VALUES ('prod','DEFAULT','prod','PRODUCTION','ACTIVE') ON CONFLICT (id) DO NOTHING");
    }

    /** 主体每次运行唯一：避免上次残留案件影响 findOpenCase 的状态机起点（跨事件续判依赖干净起点） */
    private static String uniq(String prefix) {
        return prefix + "-" + Long.toHexString(System.nanoTime());
    }

    /** RiskJob.toJson 同构的事件 JSON（snake_case 契约字段） */
    private static String eventJson(String subjectId, String riskEventId, String action,
                                    String trustLevel, String severity, String reason) {
        return "{\"game_id\":\"DEFAULT\",\"environment\":\"prod\",\"ts\":" + System.currentTimeMillis()
                + ",\"risk_event_id\":\"" + riskEventId + "\",\"source_event_id\":\"src-" + riskEventId
                + "\",\"rule_id\":\"rule_resource_spike\",\"risk_type\":\"FEATURE\",\"severity\":\"" + severity
                + "\",\"subject_type\":\"PLAYER\",\"subject_id\":\"" + subjectId
                + "\",\"score\":85.0,\"trust_level\":\"" + trustLevel
                + "\",\"action\":\"" + action + "\",\"reason\":\"" + reason
                + "\",\"evidence\":{\"feature:gold_gain_1h\":\"600000\"}}";
    }

    private Map<String, Object> singleRow(String sql, Object... args) {
        List<Map<String, Object>> rows = pg.queryForList(sql, args);
        assertEquals(1, rows.size(), "期望恰好一行: " + sql + " args=" + List.of(args));
        return rows.get(0);
    }

    @Test
    @DisplayName("BLOCK+HIGH：risk_cases 判定 BLOCK + block_lists + audit + CH risk_actions 归档")
    void blockHighTrust_landsBlockCaseBlocklistAndArchive() {
        String subject = uniq("e2e-block-high");
        String rid = "re2e-" + subject;
        consumer.onRiskEvent(eventJson(subject, rid, "BLOCK", "HIGH", "HIGH", "e2e high trust block"));

        // 判定落库：OPEN→BLOCK 直达（合法跨层）
        Map<String, Object> row = singleRow(
                "SELECT status, action_taken FROM risk_cases WHERE target_id = ?", subject);
        assertEquals("BLOCK", row.get("status"));
        assertEquals("BLOCK", row.get("action_taken"));

        // 处置执行：名单落地（severity=HIGH → 非永久，1 天）
        Integer blocks = pg.queryForObject(
                "SELECT count(*) FROM block_lists WHERE target_value = ? AND target_type = 'player_id'",
                Integer.class, subject);
        assertEquals(1, blocks, "BLOCK+HIGH 应落封禁名单");

        // 审计：BLOCK 处置由 BlockListService 记 BLOCK/player_id 审计
        // （SECURITY_ALERT/risk_event 审计走 REVIEW/THROTTLE/MARK/ALERT 的 handleAuditOnly 路径）
        Integer audits = pg.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'BLOCK' AND resource_id = ?",
                Integer.class, subject);
        assertEquals(1, audits, "BLOCK 应留 BLOCK 审计");

        // CH 归档：risk_actions 携带案件 id
        assertTrue(clickHouseClient.isAvailable(), "e2e 要求 oddsmaker.clickhouse.url 指向 CH 容器");
        List<Map<String, Object>> archived = clickHouseClient.query(
                "SELECT action, state, risk_case_id FROM risk_actions WHERE subject_id = ?", subject);
        assertEquals(1, archived.size());
        assertEquals("block", archived.get(0).get("action"));
        assertEquals("blocked", archived.get(0).get("state"));
        assertTrue(String.valueOf(archived.get(0).get("risk_case_id")).startsWith("rc_"),
                "归档应携带 Decision 案件 id");
    }

    @Test
    @DisplayName("BLOCK+非 HIGH：trust 门槛 fail-closed 降级 REVIEW，[trust_gate] 入审核队列不进名单")
    void blockLowTrust_downgradedToReviewWithTrustGate() {
        String subject = uniq("e2e-block-low");
        consumer.onRiskEvent(eventJson(subject, "re2e-" + subject, "BLOCK", "LOW", "CRITICAL", "e2e low trust"));

        Map<String, Object> row = singleRow(
                "SELECT status, action_taken, action_description FROM risk_cases WHERE target_id = ?", subject);
        assertEquals("REVIEW", row.get("status"), "非 HIGH 信任的 BLOCK 必须降级");
        assertEquals("REVIEW", row.get("action_taken"));
        assertTrue(String.valueOf(row.get("action_description")).startsWith("[trust_gate]"),
                "降级原因应打 [trust_gate] 标记");

        Integer queued = pg.queryForObject(
                "SELECT count(*) FROM review_queues WHERE target_id = ?", Integer.class, subject);
        assertEquals(1, queued, "降级后应进人工审核队列");
        Integer blocks = pg.queryForObject(
                "SELECT count(*) FROM block_lists WHERE target_value = ?", Integer.class, subject);
        assertEquals(0, blocks, "降级路径不得封禁");
    }

    @Test
    @DisplayName("ALERT→THROTTLE 跨事件严格升层：同一案件 tier 1→2，两次处置均归档")
    void alertThenThrottle_tierAscentOnSameCase() {
        String subject = uniq("e2e-ascent");
        consumer.onRiskEvent(eventJson(subject, "re2e-a1-" + subject, "ALERT", "LOW", "MEDIUM", "e2e alert first"));
        consumer.onRiskEvent(eventJson(subject, "re2e-a2-" + subject, "THROTTLE", "HIGH", "HIGH", "e2e throttle next"));

        // 单案件两次升层，不新建案件
        List<Map<String, Object>> cases = pg.queryForList(
                "SELECT id, status, action_taken FROM risk_cases WHERE target_id = ? ORDER BY created_at", subject);
        assertEquals(1, cases.size(), "升层应复用同一案件");
        assertEquals("THROTTLE", cases.get(0).get("status"));
        assertEquals("THROTTLE", cases.get(0).get("action_taken"));
        String caseId = String.valueOf(cases.get(0).get("id"));

        List<Map<String, Object>> archived = clickHouseClient.query(
                "SELECT action, state, risk_case_id FROM risk_actions WHERE subject_id = ?", subject);
        assertEquals(2, archived.size(), "alert 与 throttle 两次处置均应归档");
        // 同毫秒双行 ts 相同，不依赖 CH 返回顺序，按 action 取行断言
        Map<String, Object> alertRow = archived.stream()
                .filter(r -> "alert".equals(r.get("action"))).findFirst().orElse(null);
        Map<String, Object> throttleRow = archived.stream()
                .filter(r -> "throttle".equals(r.get("action"))).findFirst().orElse(null);
        assertNotNull(alertRow, "alert 处置应归档");
        assertNotNull(throttleRow, "throttle 处置应归档");
        assertEquals("logged", alertRow.get("state"));
        assertEquals("throttled", throttleRow.get("state"));
        assertEquals(caseId, throttleRow.get("risk_case_id"));
    }

    @Test
    @DisplayName("BLOCK 之后 MARK：同层/回退判定非法，动作拒绝并归档 decision_rejected")
    void blockThenMark_illegalTransitionRejected() {
        String subject = uniq("e2e-illegal");
        String rid2 = "re2e-m2-" + subject;
        consumer.onRiskEvent(eventJson(subject, "re2e-m1-" + subject, "BLOCK", "HIGH", "HIGH", "e2e block first"));
        consumer.onRiskEvent(eventJson(subject, rid2, "MARK", "HIGH", "MEDIUM", "e2e mark second"));

        // 案件保持 BLOCK，未新建、未回退
        List<Map<String, Object>> cases = pg.queryForList(
                "SELECT status, action_taken FROM risk_cases WHERE target_id = ?", subject);
        assertEquals(1, cases.size());
        assertEquals("BLOCK", cases.get(0).get("status"));
        assertEquals("BLOCK", cases.get(0).get("action_taken"));

        // 第二个事件的处置未执行：无新名单、无审计，归档 decision_rejected
        Integer blocks = pg.queryForObject(
                "SELECT count(*) FROM block_lists WHERE target_value = ?", Integer.class, subject);
        assertEquals(1, blocks, "只有第一次 BLOCK 落名单");
        Integer audits2 = pg.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ?", Integer.class, rid2);
        assertEquals(0, audits2, "被拒绝的判定不得触发审计");
        List<Map<String, Object>> rejected = clickHouseClient.query(
                "SELECT action, state FROM risk_actions WHERE subject_id = ? AND state = 'decision_rejected'",
                subject);
        assertEquals(1, rejected.size());
        assertEquals("mark", rejected.get(0).get("action"));
    }
}
