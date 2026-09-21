package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.jpa.SymbolMappingEntity;
import io.oddsmaker.control.jpa.SymbolMappingRepo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 符号化服务测试：规则解析/应用、自动匹配映射、无映射回退、非法输入与坏规则容错。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("符号化服务测试")
class SymbolicationServiceTest {

    @Mock
    private SymbolMappingRepo repo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SymbolicationService service;

    private SymbolMappingEntity mapping(String rules) {
        SymbolMappingEntity m = new SymbolMappingEntity();
        m.id = "sym_1";
        m.gameId = "game_demo";
        m.platform = "android";
        m.appVersion = "1.2.0";
        m.mappingRules = rules;
        return m;
    }

    @BeforeEach
    void setUp() {
        service = new SymbolicationService(repo, objectMapper);
    }

    @Test
    @DisplayName("符号化：按规则替换混淆堆栈并计数命中规则")
    void symbolicateAppliesRules() {
        String rules = "[{\"pattern\":\"a\\\\.b\\\\.([A-Za-z]+)\",\"replacement\":\"com.game.real.$1\"},"
                + "{\"pattern\":\"libgame\\\\.so\",\"replacement\":\"libgame.symbol.so\"}]";
        when(repo.findActive("game_demo", "android", "1.2.0")).thenReturn(List.of(mapping(rules)));

        SymbolicationService.SymbolicationResult result = service.symbolicate(
            "game_demo", "android", "1.2.0",
            "at a.b.CombatSystem.resolve(libgame.so+0x1f)\nat a.b.Loop.tick(libgame.so+0x2a)");

        assertEquals("sym_1", result.mappingId());
        assertTrue(result.symbolized().contains("com.game.real.CombatSystem"));
        assertTrue(result.symbolized().contains("libgame.symbol.so"));
        assertEquals(2, result.rulesApplied());
    }

    @Test
    @DisplayName("符号化：无 ACTIVE 映射时原样返回 rulesApplied=0")
    void noMappingReturnsOriginal() {
        when(repo.findActive("game_demo", "ios", "2.0.0")).thenReturn(List.of());

        SymbolicationService.SymbolicationResult result =
            service.symbolicate("game_demo", "ios", "2.0.0", "at Foo.bar(Foo.kt:1)");

        assertNull(result.mappingId());
        assertEquals("at Foo.bar(Foo.kt:1)", result.symbolized());
        assertEquals(0, result.rulesApplied());
    }

    @Test
    @DisplayName("符号化：空堆栈拒绝")
    void blankStackRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.symbolicate("game_demo", "android", "1.2.0", " "));
    }

    @Test
    @DisplayName("规则解析：非法正则与非数组 JSON 被跳过")
    void parseRulesSkipsInvalid() {
        assertEquals(0, service.parseRules("[{\"pattern\":\"([unclosed\",\"replacement\":\"x\"}]").size());
        assertEquals(0, service.parseRules("{\"not\":\"array\"}").size());
        assertEquals(0, service.parseRules("not-json").size());
        assertEquals(1, service.parseRules(
            "[{\"pattern\":\"abc\",\"replacement\":\"x\"},{\"pattern\":\"\",\"replacement\":\"y\"}]").size());
    }

    @Test
    @DisplayName("坏规则 JSON 的映射不阻断符号化")
    void mappingWithBrokenRulesActsAsNoRules() {
        lenient().when(repo.findActive(any(), any(), any())).thenReturn(List.of(mapping("broken")));
        SymbolicationService.SymbolicationResult result =
            service.symbolicate("game_demo", "android", "1.2.0", "at A.a(A:1)");
        assertEquals("sym_1", result.mappingId());
        assertEquals("at A.a(A:1)", result.symbolized());
        assertEquals(0, result.rulesApplied());
    }


    @Test
    @DisplayName("对侧：null 堆栈、null/blank 规则串、不命中规则与缺 pattern 键节点")
    void symbolicateNullSides() {
        // 38 行 null 侧
        assertThrows(IllegalArgumentException.class,
            () -> service.symbolicate("game_demo", "android", "1.2.0", null));
        // 44 行 mappingRules null 侧
        when(repo.findActive("g", "ios", "1")).thenReturn(List.of(mapping(null)));
        var r1 = service.symbolicate("g", "ios", "1", "at A(A:1)");
        assertNull(r1.mappingId());
        assertEquals(0, r1.rulesApplied());
        // 44 行 mappingRules blank 侧
        when(repo.findActive("g2", "ios", "1")).thenReturn(List.of(mapping("   ")));
        var r2 = service.symbolicate("g2", "ios", "1", "at A(A:1)");
        assertNull(r2.mappingId());
        // 52 行不命中侧：规则存在但 pattern 不匹配 → applied=0
        when(repo.findActive("g3", "ios", "1")).thenReturn(List.of(
            mapping("[{\"pattern\":\"zzz-never\",\"replacement\":\"X\"}]")));
        var r3 = service.symbolicate("g3", "ios", "1", "at A(A:1)");
        assertEquals("sym_1", r3.mappingId());
        assertEquals(0, r3.rulesApplied());
        // 74 行 pattern 缺键侧（path("pattern") → null）
        assertEquals(0, service.parseRules("[{\"replacement\":\"z\"}]").size());
    }

}
