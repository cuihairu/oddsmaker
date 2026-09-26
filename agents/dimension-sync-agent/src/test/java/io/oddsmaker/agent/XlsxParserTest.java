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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** xlsx 最小解析器：测试用 ZipOutputStream 手工产出 xlsx 结构（zip+xml），零三方依赖。 */
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
