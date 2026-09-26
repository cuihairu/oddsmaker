package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 4180 解析：引号包裹、"" 转义、引号内换行/逗号、\r\n、BOM。 */
class CsvParserTest {

    @Test
    @DisplayName("朴素单行")
    void simpleRow() {
        List<String[]> rows = CsvParser.parse("a,b,c");
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"a", "b", "c"}, rows.get(0));
    }

    @Test
    @DisplayName("引号字段含逗号；\"\" 转义为引号")
    void quotedFieldWithCommaAndEscape() {
        List<String[]> rows = CsvParser.parse("a,\"x,y\",\"x\"\"y\"");
        assertArrayEquals(new String[]{"a", "x,y", "x\"y"}, rows.get(0));
    }

    @Test
    @DisplayName("CRLF 与 LF 换行等价")
    void crlfAndLf() {
        List<String[]> crlf = CsvParser.parse("a,b\r\nc,d");
        List<String[]> lf = CsvParser.parse("a,b\nc,d");
        assertEquals(lf.size(), crlf.size());
        for (int i = 0; i < lf.size(); i++) {
            assertArrayEquals(lf.get(i), crlf.get(i));
        }
    }

    @Test
    @DisplayName("引号字段内换行不切行")
    void quotedNewlineStaysInField() {
        List<String[]> rows = CsvParser.parse("\"l1\nl2\",b");
        assertEquals(1, rows.size());
        assertArrayEquals(new String[]{"l1\nl2", "b"}, rows.get(0));
    }

    @Test
    @DisplayName("末行无换行符也成行")
    void trailingRowWithoutNewline() {
        List<String[]> rows = CsvParser.parse("a,b\nc,d");
        assertArrayEquals(new String[]{"c", "d"}, rows.get(1));
    }

    @Test
    @DisplayName("UTF-8 BOM 跳过；Reader 入口与 String 入口一致")
    void bomAndReaderEntry() throws Exception {
        List<String[]> rows = CsvParser.parse("\uFEFFa,b");
        assertArrayEquals(new String[]{"a", "b"}, rows.get(0));
        List<String[]> viaReader = CsvParser.parse(new StringReader("x,y\n1,2"));
        assertEquals(2, viaReader.size());
        assertArrayEquals(new String[]{"x", "y"}, viaReader.get(0));
    }

    @Test
    @DisplayName("空字段保留为空串")
    void emptyFields() {
        List<String[]> rows = CsvParser.parse("a,,c\n,d,");
        assertArrayEquals(new String[]{"a", "", "c"}, rows.get(0));
        assertArrayEquals(new String[]{"", "d", ""}, rows.get(1));
    }
}
