package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 源：与 CsvSource 同语义——文件序、文件粒度断点、缺 resource_id 行跳过。
 *
 * <p>两条臂刻意不强求覆盖（构造不出来也不该改产品代码去凑）：
 * 表头 null 单元格（{@link XlsxParser} 产出的 cells 恒为非 null 字符串，空洞补 ""）
 * 与空白 resource_id 行跳过（{@link JdbcSource#mapRow} 的 firstNonNull 保证
 * resourceId 非空或 null，isBlank 侧不可达）。
 */
class ExcelSourceTest {

    @TempDir
    Path dir;

    private static final String SHEET_TWO_ROWS = """
            <?xml version="1.0"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>dim_type</t></is></c><c r="B1" t="inlineStr"><is><t>item_code</t></is></c><c r="C1" t="inlineStr"><is><t>name</t></is></c><c r="D1" t="inlineStr"><is><t>rarity</t></is></c><c r="E1" t="inlineStr"><is><t>version_ts</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>item</t></is></c><c r="B2" t="inlineStr"><is><t>sword_01</t></is></c><c r="C2" t="inlineStr"><is><t>铁剑</t></is></c><c r="D2" t="inlineStr"><is><t>sr</t></is></c><c r="E2"><v>1735689605000</v></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>item</t></is></c><c r="B3"><v>4.2E1</v></c><c r="C3" t="inlineStr"><is><t>数字编号</t></is></c><c r="D3" t="inlineStr"><is><t>r</t></is></c><c r="E3" t="inlineStr"><is><t>2026-01-01T00:00:05Z</t></is></c></row>
              </sheetData>
            </worksheet>
            """;

    private void writeSheet(Path file, String sheetXml) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("""
                    <?xml version="1.0"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="dims" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            zip.write("""
                    <?xml version="1.0"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private AgentConfig cfg() {
        AgentConfig cfg = new AgentConfig();
        cfg.excelDir = dir.toString();
        cfg.dimType = "item";
        return cfg;
    }

    @Test
    @DisplayName("控制列映射正确；数值 item_code 的 E 记法还原；ISO version_ts 生效")
    void pollsXlsxWithControlColumns() throws Exception {
        writeSheet(dir.resolve("a_items.xlsx"), SHEET_TWO_ROWS);

        ExcelSource s = new ExcelSource(cfg());
        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(2, r.changes().size());
        DimensionChange first = r.changes().get(0);
        assertEquals("sword_01", first.resourceId);
        assertEquals("item", first.dimType);
        assertEquals("sr", first.attributes.get("rarity"));
        assertEquals(1735689605000L, first.versionTs);
        // 第二行：数值单元格 4.2E1 → "42"；ISO 时间解析
        DimensionChange second = r.changes().get(1);
        assertEquals("42", second.resourceId);
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:05Z").toEpochMilli(), second.versionTs);
        // lastEventTs = max(version_ts)：row3 的 2026-01-01 比 row2 的 2025-01-01 新
        assertEquals(1767225605000L, r.next().lastEventTs);
        assertEquals("excel", s.type());
        assertTrue(s.name().startsWith("excel:"));
    }

    @Test
    @DisplayName("文件粒度断点：处理过的 xlsx 不重读，新文件只拉新")
    void processedFilesAreNotReread() throws Exception {
        writeSheet(dir.resolve("a.xlsx"), SHEET_TWO_ROWS);
        ExcelSource s = new ExcelSource(cfg());
        DimensionSource.PollResult first = s.poll(new Checkpoint());
        assertEquals(2, first.changes().size());
        assertEquals(2L, first.next().files.get("a.xlsx"));

        DimensionSource.PollResult second = s.poll(first.next());
        assertTrue(second.changes().isEmpty());

        writeSheet(dir.resolve("b.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c><c r="B1" t="inlineStr"><is><t>name</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>shield_01</t></is></c><c r="B2" t="inlineStr"><is><t>木盾</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);
        DimensionSource.PollResult third = s.poll(second.next());
        assertEquals(1, third.changes().size());
        assertEquals("shield_01", third.changes().get(0).resourceId);
        assertFalse(second.next().files.containsKey("b.xlsx"));
    }

    @Test
    @DisplayName("缺 resource_id 的行跳过；仅表头（无数据行）计 0 并记断点")
    void skipsRowsWithoutResourceIdAndHandlesHeaderOnly() throws Exception {
        writeSheet(dir.resolve("a.xlsx"), SHEET_TWO_ROWS);
        // 第三行改成缺 item_code
        writeSheet(dir.resolve("b.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c><c r="B1" t="inlineStr"><is><t>name</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t> </t></is></c><c r="B2" t="inlineStr"><is><t>空白编号</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);
        // 仅表头
        writeSheet(dir.resolve("c.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);

        DimensionSource.PollResult r = new ExcelSource(cfg()).poll(new Checkpoint());
        assertEquals(2, r.changes().size());   // a.xlsx 两行；b.xlsx 的空编号行被跳过
        assertEquals(0L, r.next().files.get("b.xlsx"));
        assertEquals(0L, r.next().files.get("c.xlsx"));
        assertEquals(2L, r.next().files.get("a.xlsx"));
    }

    // === 分支对侧补充（BRANCH 收口）===

    @Test
    @DisplayName("空工作表（零行）：文件照常记断点 0，不产生变更")
    void emptySheetRecordsZeroAndContinues() throws Exception {
        writeSheet(dir.resolve("empty.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData></sheetData>
                </worksheet>
                """);
        DimensionSource.PollResult r = new ExcelSource(cfg()).poll(new Checkpoint());
        assertTrue(r.changes().isEmpty());
        assertEquals(0L, r.next().files.get("empty.xlsx"));
    }

    @Test
    @DisplayName("数据行单元格少于表头列数：只映射存在的列，多余表头列跳过")
    void rowWithFewerCellsThanHeaderMapsOnlyPresentColumns() throws Exception {
        writeSheet(dir.resolve("short.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>dim_type</t></is></c><c r="B1" t="inlineStr"><is><t>item_code</t></is></c><c r="C1" t="inlineStr"><is><t>name</t></is></c><c r="D1" t="inlineStr"><is><t>rarity</t></is></c><c r="E1" t="inlineStr"><is><t>version_ts</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>item</t></is></c><c r="B2" t="inlineStr"><is><t>sword_01</t></is></c><c r="C2" t="inlineStr"><is><t>铁剑</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);
        DimensionSource.PollResult r = new ExcelSource(cfg()).poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        DimensionChange c = r.changes().get(0);
        assertEquals("sword_01", c.resourceId);
        // rarity / version_ts 两列在数据行缺失 → 不进 attributes
        assertEquals(1, c.attributes.size());
        assertEquals("铁剑", c.attributes.get("name"));
    }

    @Test
    @DisplayName("表头空白单元格：该列跳过，不串位、不污染 attributes")
    void blankHeaderCellSkipsColumn() throws Exception {
        writeSheet(dir.resolve("blankhdr.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c><c r="B1" t="inlineStr"/><c r="C1" t="inlineStr"><is><t>name</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>sword_01</t></is></c><c r="B2" t="inlineStr"><is><t>ignored</t></is></c><c r="C2" t="inlineStr"><is><t>铁剑</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);
        DimensionSource.PollResult r = new ExcelSource(cfg()).poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        DimensionChange c = r.changes().get(0);
        assertEquals("sword_01", c.resourceId);
        // B 列表头空白 → 该列（ignored）被跳过
        assertEquals(1, c.attributes.size());
        assertEquals("铁剑", c.attributes.get("name"));
    }

    @Test
    @DisplayName("数据行单元格多于表头列数：超出表头的列被忽略（c < header.length 假分支）")
    void rowWithMoreCellsThanHeaderIgnoresExtraCells() throws Exception {
        writeSheet(dir.resolve("wide.xlsx"), """
                <?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>item_code</t></is></c><c r="B1" t="inlineStr"><is><t>name</t></is></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>sword_01</t></is></c><c r="B2" t="inlineStr"><is><t>铁剑</t></is></c><c r="C2" t="inlineStr"><is><t>extra1</t></is></c><c r="D2" t="inlineStr"><is><t>extra2</t></is></c></row>
                  </sheetData>
                </worksheet>
                """);
        DimensionSource.PollResult r = new ExcelSource(cfg()).poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        DimensionChange c = r.changes().get(0);
        assertEquals("sword_01", c.resourceId);
        assertEquals("铁剑", c.attributes.get("name"));
        // 额外列 extra1, extra2 不进 attributes
        assertEquals(1, c.attributes.size());
    }
}
