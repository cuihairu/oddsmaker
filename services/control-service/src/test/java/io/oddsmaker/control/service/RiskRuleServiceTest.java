package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控规则服务测试：PATTERN 序列规则校验（create/update 终态）与非 PATTERN 类型不受影响。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("风控规则服务测试")
class RiskRuleServiceTest {

    @Mock
    private RiskRuleRepo ruleRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    private RiskRuleService service;

    @BeforeEach
    void setUp() {
        service = new RiskRuleService(ruleRepo, gameRepo, auditLog);
    }

    private RiskRuleEntity patternRule(String ruleConditions) {
        RiskRuleEntity r = new RiskRuleEntity();
        r.gameId = "g1";
        r.name = "seq-rule";
        r.ruleType = RiskRuleEntity.RuleType.PATTERN;
        r.ruleConditions = ruleConditions;
        return r;
    }

    private GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "g1";
        return g;
    }

    @Test
    @DisplayName("create：PATTERN 合法序列（2-8 步）入库，写审计日志")
    void createPersistsValidPatternRule() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(ruleRepo.findByGameId("g1")).thenReturn(List.of());
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity saved = service.create(
                patternRule("{\"sequence\":[\"login\",\"purchase\",\"refund\"]}"), "op");

        assertNotNull(saved.id);
        assertEquals(RiskRuleEntity.RuleType.PATTERN, saved.ruleType);
        verify(ruleRepo).save(saved);
        verify(auditLog).logCreate(eq("op"), eq("op"), eq("risk_rule"), eq(saved.id), eq("seq-rule"),
                anyString(), (String) isNull());
    }

    @Test
    @DisplayName("create：PATTERN 非法序列——缺失/单步/越界 9 步/空名/超长名/坏 JSON/非数组 均拒绝")
    void createRejectsInvalidPatternSequences() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));

        List<String> bad = List.of(
                "null",                                                   // 无条件
                "{\"no_seq\":1}",                                         // 缺 sequence
                "{\"sequence\":[]}",                                      // 空
                "{\"sequence\":[\"login\"]}",                             // 单步
                "{\"sequence\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}",   // 9 步越界
                "{\"sequence\":[\"login\",\"\"]}",                        // 空名
                "{\"sequence\":[\"login\",\"" + "x".repeat(101) + "\"]}", // 超长名
                "not-json",                                               // 坏 JSON
                "{\"sequence\":\"login\"}"                                // 非数组
        );
        for (String conditions : bad) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.create(patternRule(conditions), "op"),
                    "应拒绝: " + conditions);
            assertTrue(ex.getMessage().contains("sequence"), ex.getMessage());
        }
        verify(ruleRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：非 PATTERN 类型不校验 ruleConditions（THRESHOLD 携带任意串照常入库）")
    void createSkipsValidationForNonPatternTypes() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game()));
        when(ruleRepo.findByGameId("g1")).thenReturn(List.of());
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity r = patternRule("garbage-not-json");
        r.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        r.triggerThreshold = 100;
        RiskRuleEntity saved = service.create(r, "op");

        assertEquals(RiskRuleEntity.RuleType.THRESHOLD, saved.ruleType);
        verify(ruleRepo).save(saved);
    }

    @Test
    @DisplayName("update：触及 conditions/type 时按合并终态校验——改坏序列拒绝；改好放行")
    void updateValidatesMergedFinalStateWhenTouched() {
        RiskRuleEntity existing = patternRule("{\"sequence\":[\"login\",\"purchase\"]}");
        existing.id = "rr_1";
        when(ruleRepo.findById("rr_1")).thenReturn(Optional.of(existing));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 合法新序列 → 放行（显式置 null 模拟部分更新：实体 ruleType 默认 THRESHOLD 非 null）
        RiskRuleEntity good = new RiskRuleEntity();
        good.ruleType = null;
        good.ruleConditions = "{\"sequence\":[\"a\",\"b\",\"c\"]}";
        RiskRuleEntity saved = service.update("rr_1", good, "op");
        assertEquals("{\"sequence\":[\"a\",\"b\",\"c\"]}", saved.ruleConditions);

        // 非法新序列 → 拒绝且不落库
        RiskRuleEntity bad = new RiskRuleEntity();
        bad.ruleType = null;
        bad.ruleConditions = "{\"sequence\":[\"solo\"]}";
        assertThrows(IllegalArgumentException.class, () -> service.update("rr_1", bad, "op"));

        // 类型改成 PATTERN 而存量无条件 → 拒绝
        RiskRuleEntity bare = new RiskRuleEntity();
        bare.ruleType = RiskRuleEntity.RuleType.PATTERN;
        RiskRuleEntity noConditions = patternRule(null);
        noConditions.id = "rr_2";
        when(ruleRepo.findById("rr_2")).thenReturn(Optional.of(noConditions));
        assertThrows(IllegalArgumentException.class, () -> service.update("rr_2", bare, "op"));

        verify(ruleRepo, org.mockito.Mockito.times(1)).save(any());   // 仅第一次（合法）update 落库
    }

    @Test
    @DisplayName("update：不触及 type/conditions 的编辑不受存量坏序列影响（兜底由 job 侧跳过）")
    void updateSkipsValidationWhenConditionsUntouched() {
        RiskRuleEntity existing = patternRule("legacy-bad-conditions");
        existing.id = "rr_1";
        when(ruleRepo.findById("rr_1")).thenReturn(Optional.of(existing));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity rename = new RiskRuleEntity();
        rename.ruleType = null;
        rename.ruleConditions = null;   // 显式置 null：实体默认 THRESHOLD 会被 update 误触校验分支
        rename.displayName = "新名字";
        RiskRuleEntity saved = service.update("rr_1", rename, "op");

        assertEquals("新名字", saved.displayName);
        verify(ruleRepo).save(saved);
    }
}
