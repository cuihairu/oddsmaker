package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 兑换码生成器测试：字符集、长度、前缀、批内去重。
 */
@DisplayName("兑换码生成器测试")
class CodeGeneratorTest {

    @Test
    @DisplayName("生成：数量与前缀符合预期，且批内不重复")
    void generatesUniqueCodesWithPrefix() {
        List<String> codes = CodeGenerator.generate(500, 10, "NY26-");
        assertEquals(500, codes.size());
        assertEquals(500, new HashSet<>(codes).size());
        for (String code : codes) {
            assertTrue(code.startsWith("NY26-"));
            assertEquals(15, code.length());
        }
    }

    @Test
    @DisplayName("字符集去除易混淆字符（0/O/1/I/L）")
    void alphabetExcludesAmbiguousChars() {
        assertEquals("23456789ABCDEFGHJKMNPQRSTUVWXYZ", CodeGenerator.ALPHABET);
        for (char c : CodeGenerator.ALPHABET.toCharArray()) {
            assertFalse("0O1IL".indexOf(c) >= 0, "ambiguous char found: " + c);
        }
        for (String code : CodeGenerator.generate(100, 12, null)) {
            for (char c : code.toCharArray()) {
                assertTrue(CodeGenerator.ALPHABET.indexOf(c) >= 0, "unexpected char: " + c);
            }
        }
    }

    @Test
    @DisplayName("参数校验：count 与长度边界")
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> CodeGenerator.generate(0, 12, null));
        assertThrows(IllegalArgumentException.class, () -> CodeGenerator.generate(10, 5, null));
        assertThrows(IllegalArgumentException.class, () -> CodeGenerator.generate(10, 33, null));
        assertEquals(1, CodeGenerator.generate(1, 6, "").size());
    }

    @Test
    @DisplayName("随机性抽样：大样本分布覆盖多数字符")
    void largeSampleCoversAlphabet() {
        Set<Character> seen = new HashSet<>();
        for (String code : CodeGenerator.generate(2000, 16, null)) {
            for (char c : code.toCharArray()) {
                seen.add(c);
            }
        }
        // 31 个候选字符，32000 次抽样后应全部出现过（概率上几乎必然）
        assertEquals(CodeGenerator.ALPHABET.length(), seen.size());
    }
}
