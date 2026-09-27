package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xlsx 最小解析器：测试用 ZipOutputStream 手工产出 xlsx 结构（zip+xml），零三方依赖。
 *
 * <p>两条臂刻意不强求覆盖（源码内均为兜底，构造不出来也不该改产品代码去凑）：
 * {@code firstSheetPath} 的 {@code getAttributeNS} 回退（parseXml 未开 namespace-aware，
 * {@code getAttribute("r:id")} 总能取到字面属性）与 {@code parseXml} 中
 * {@code setFeature(disallow-doctype-decl)} 抛错的 catch（JDK 内置解析器恒支持该特性）。
 */
class XlsxParserTest {

    @TempDir
    Path dir;

    private static final String WORKBOOK_XML = """
            <?xml version="1.0"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="dims" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
            """;
    private static final String RELS_XML = """
            <?xml version="1.0"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
            """;

    private void writeXlsx(Path file, String sharedXml, String sheetXml,
                           String workbookXml, String relsXml, String sheetEntry) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            if (sharedXml != null) {
                zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
                zip.write(sharedXml.getBytes(StandardCharsets.UTF_8));
            }
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write(workbookXml.getBytes(StandardCharsets.UTF_8));
            if (relsXml != null) {
                zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
                zip.write(relsXml.getBytes(StandardCharsets.UTF_8));
            }
            zip.putNextEntry(new ZipEntry(sheetEntry));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("共享字符串 + 数值 + 列引用定位（跳过空洞补空串）+ 科学计数法还原")
    void parsesSharedStringsNumbersAndGaps() throws Exception {
        Path f = dir.resolve("a.xlsx");
        writeXlsx(f, """
                <?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>item_code</t></si><si><t>weight</t></si><si><t>name</t></si>
                  <si><t>sword_01</t></si><si><t>legend</t></si>
                  <si><r><t>铁</t></r><r><t>剑</t></r></si>
                </sst>
                """, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
                    <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>1.735689605E12</v></c><c r="C2" t="s"><v>5</v></c></row>
                    <row r="3"><c r="A3" t="s"><v>3</v></c><c r="C3"><v>2.5</v></c></row>
                  </sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");

        List<String[]> rows = XlsxParser.parse(f);
        assertEquals(3, rows.size());
        assertEquals(3, rows.get(0).length);
        // 表头
        assertEquals("item_code", rows.get(0)[0]);
        // 富文本 si 拼接 + E 记法还原为整数文本
        assertEquals("sword_01", rows.get(1)[0]);
        assertEquals("1735689605000", rows.get(1)[1]);
        assertEquals("铁剑", rows.get(1)[2]);
        // B3 空洞补空串，列不错位
        assertEquals("sword_01", rows.get(2)[0]);
        assertEquals("", rows.get(2)[1]);
        assertEquals("2.5", rows.get(2)[2]);
    }

    @Test
    @DisplayName("无共享字符串表（全内联/数值/布尔）也能解析")
    void parsesInlineOnlyWorkbook() throws Exception {
        Path f = dir.resolve("b.xlsx");
        writeXlsx(f, null, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c><c r="B1" t="inlineStr"><is><t>active</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>shield_01</t></is></c><c r="B2" t="b"><v>1</v></c></row>
                  </sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");

        List<String[]> rows = XlsxParser.parse(f);
        assertEquals("item_code", rows.get(0)[0]);
        assertEquals("shield_01", rows.get(1)[0]);
        assertEquals("true", rows.get(1)[1]);
    }

    @Test
    @DisplayName("按 workbook rels 解析自定义工作表条目路径")
    void resolvesCustomSheetEntryFromRels() throws Exception {
        Path f = dir.resolve("c.xlsx");
        writeXlsx(f, null, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c></row></sheetData>
                </worksheet>
                """, WORKBOOK_XML, """
                <?xml version="1.0"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/my-data-sheet.xml"/>
                </Relationships>
                """, "xl/worksheets/my-data-sheet.xml");

        assertEquals(1, XlsxParser.parse(f).size());
    }

    @Test
    @DisplayName("损坏的 zip / 缺 workbook：明确的 IOException 而非其它异常")
    void corruptOrNonXlsxFiles() throws Exception {
        Path notZip = dir.resolve("bad.xlsx");
        Files.writeString(notZip, "this is not a zip");
        assertThrows(IOException.class, () -> XlsxParser.parse(notZip));

        Path noWorkbook = dir.resolve("nowb.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(noWorkbook))) {
            zip.putNextEntry(new ZipEntry("something.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
        }
        IOException e = assertThrows(IOException.class, () -> XlsxParser.parse(noWorkbook));
        assertTrue(e.getMessage().contains("xl/workbook.xml"));
    }

    @Test
    @DisplayName("缺值单元格的空洞语义：t=s 无 v / inlineStr 无 is / 数值无 v → 空串；t=b 无 v 或非 1 → false")
    void missingValueChildrenFallBackToEmptyOrFalse() throws Exception {
        Path f = dir.resolve("holes.xlsx");
        writeXlsx(f, """
                <?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>hdr</t></si></sst>
                """, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"/><c r="B1" t="inlineStr"/><c r="C1"/><c r="D1" t="b"><v>0</v></c><c r="E1" t="b"/>
                    </row>
                  </sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");

        String[] row = XlsxParser.parse(f).get(0);
        assertEquals(5, row.length);
        assertEquals(List.of("", "", "", "false", "false"), List.of(row));
    }

    @Test
    @DisplayName("无 r 列引用时按文档序定位（部分导出器不写 r）")
    void rowWithoutCellRefsFallsBackToDocumentOrder() throws Exception {
        Path f = dir.resolve("noref.xlsx");
        writeXlsx(f, """
                <?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>item_code</t></si></sst>
                """, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row><c t="s"><v>0</v></c><c><v>12.5</v></c></row>
                  </sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");

        assertEquals(List.of("item_code", "12.5"), List.of(XlsxParser.parse(f).get(0)));
    }

    @Test
    @DisplayName("共享字符串索引非法 / 越界：IOException 带上下文，不静默降级为空值")
    void badSharedStringIndexFailsLoudly() throws Exception {
        Path illegal = dir.resolve("idx-illegal.xlsx");
        writeXlsx(illegal, """
                <?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>x</t></si></sst>
                """, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row><c r="A1" t="s"><v>not-a-number</v></c></row></sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");
        IOException e1 = assertThrows(IOException.class, () -> XlsxParser.parse(illegal));
        assertTrue(e1.getMessage().contains("共享字符串索引非法"), e1.getMessage());

        Path outOfRange = dir.resolve("idx-oob.xlsx");
        writeXlsx(outOfRange, """
                <?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>x</t></si></sst>
                """, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row><c r="A1" t="s"><v>9</v></c></row></sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");
        IOException e2 = assertThrows(IOException.class, () -> XlsxParser.parse(outOfRange));
        assertTrue(e2.getMessage().contains("越界"), e2.getMessage());
        assertTrue(e2.getMessage().contains("共 1 条"), e2.getMessage());
    }

    @Test
    @DisplayName("rels 指向的工作表条目不在 zip 里：明确报「缺少工作表条目」")
    void missingSheetEntryFailsLoudly() throws Exception {
        Path f = dir.resolve("nosheet.xlsx");
        writeXlsx(f, null, """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row><c r="A1" t="inlineStr"><is><t>item_code</t></is></c></row></sheetData>
                </worksheet>
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/somewhere-else.xml");

        IOException e = assertThrows(IOException.class, () -> XlsxParser.parse(f));
        assertTrue(e.getMessage().contains("缺少工作表条目"), e.getMessage());
        assertTrue(e.getMessage().contains("xl/worksheets/sheet1.xml"), e.getMessage());
    }

    @Test
    @DisplayName("工作表路径解析各臂：无 rels 兜底约定路径 / Target 绝对路径去斜杠 / 多关系项按 Id 命中")
    void sheetPathResolutionArms() throws Exception {
        String sheet = """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row><c r="A1" t="inlineStr"><is><t>item_code</t></is></c></row></sheetData>
                </worksheet>
                """;

        // ① rels 缺失 → 约定路径 xl/worksheets/sheet1.xml
        Path noRels = dir.resolve("no-rels.xlsx");
        writeXlsx(noRels, null, sheet, WORKBOOK_XML, null, "xl/worksheets/sheet1.xml");
        assertEquals("item_code", XlsxParser.parse(noRels).get(0)[0]);

        // ② Target 以 / 开头（绝对路径写法）→ 去掉前导斜杠再查条目
        Path absolute = dir.resolve("abs-target.xlsx");
        writeXlsx(absolute, null, sheet, WORKBOOK_XML, """
                <?xml version="1.0"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet1.xml"/>
                </Relationships>
                """, "xl/worksheets/sheet1.xml");
        assertEquals("item_code", XlsxParser.parse(absolute).get(0)[0]);

        // ③ 多条 Relationship（含 styles/sharedStrings 等）→ 循环命中目标 Id，不被首条带偏
        Path multi = dir.resolve("multi-rels.xlsx");
        writeXlsx(multi, null, sheet, WORKBOOK_XML, """
                <?xml version="1.0"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId9" Type=".../styles" Target="styles.xml"/>
                  <Relationship Id="rId7" Type=".../sharedStrings" Target="sharedStrings.xml"/>
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/data.xml"/>
                </Relationships>
                """, "xl/worksheets/data.xml");
        assertEquals("item_code", XlsxParser.parse(multi).get(0)[0]);

        // ④ workbook 里 sheet 的 r:id 与 rels 中的 Id 对不上 → 兜底约定路径
        Path mismatched = dir.resolve("rid-mismatch.xlsx");
        writeXlsx(mismatched, null, sheet, WORKBOOK_XML, """
                <?xml version="1.0"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId42" Type=".../worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """, "xl/worksheets/sheet1.xml");
        assertEquals("item_code", XlsxParser.parse(mismatched).get(0)[0]);
    }

    @Test
    @DisplayName("XML 损坏（非 IOException 内部异常）：统一包装为「xlsx 解析失败」的 IOException")
    void malformedXmlIsWrappedAsIoException() throws Exception {
        Path f = dir.resolve("broken-xml.xlsx");
        writeXlsx(f, null, """
                <?xml version="1.0"?>
                <worksheet><sheetData><row><c r="A1">
                """, WORKBOOK_XML, RELS_XML, "xl/worksheets/sheet1.xml");

        IOException e = assertThrows(IOException.class, () -> XlsxParser.parse(f));
        assertTrue(e.getMessage().startsWith("xlsx 解析失败"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    @DisplayName("normalizeNumber 各臂：null→空串、无 E/e 原样、E 记法整数还原、小数/超界/无穷/非法回退原文")
    void numberNormalizationArms() {
        assertEquals("", XlsxParser.normalizeNumber(null));
        assertEquals("", XlsxParser.normalizeNumber("   "));
        assertEquals("42", XlsxParser.normalizeNumber("42"));           // 无指数记号
        assertEquals("1735689605000", XlsxParser.normalizeNumber("1.735689605E12"));
        assertEquals("-1000", XlsxParser.normalizeNumber("-1E3"));
        assertEquals("1.5E-1", XlsxParser.normalizeNumber("1.5E-1"));   // 小数不还原
        assertEquals("1E30", XlsxParser.normalizeNumber("1E30"));       // 超出 long 安全区
        assertEquals("1e999", XlsxParser.normalizeNumber("1e999"));     // 解析成无穷
        assertEquals("1.2e", XlsxParser.normalizeNumber("1.2e"));       // 非法数字，原文返回
    }

    @Test
    @DisplayName("colOf 小写字母引用与无引用（部分生成器写小写 r 属性）")
    void columnReferenceLowercaseAndEmpty() {
        assertEquals(1, XlsxParser.colOf("b3"));
        assertEquals(26, XlsxParser.colOf("aa2"));
        assertEquals(0, XlsxParser.colOf("a1"));
        assertEquals(-1, XlsxParser.colOf(""));
        assertEquals(-1, XlsxParser.colOf("12"));
        assertEquals(-1, XlsxParser.colOf(null));
    }

    @Test
    @DisplayName("列引用换算：A→0、Z→25、AA→26；colOf 对无字母引用返回 -1")
    void columnReferenceMath() {
        assertEquals(0, XlsxParser.colOf("A1"));
        assertEquals(25, XlsxParser.colOf("Z9"));
        assertEquals(26, XlsxParser.colOf("AA2"));
        assertEquals(-1, XlsxParser.colOf("123"));
        assertEquals(-1, XlsxParser.colOf(null));
        assertEquals("42", XlsxParser.normalizeNumber(" 4.2E1 "));
        assertEquals("1.5", XlsxParser.normalizeNumber("1.5"));
        assertEquals("abc", XlsxParser.normalizeNumber("abc"));
    }
}
