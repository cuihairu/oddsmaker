package io.oddsmaker.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 最小 xlsx（Office Open XML spreadsheet）读取器：仅标准库 zip + XML DOM，
 * 刻意不引入 POI 等重依赖（维度表场景不值得）。覆盖范围：
 * 首个工作表；共享字符串（含富文本 run 拼接）/ 内联字符串 / 数值 / 布尔单元格；
 * 依据单元格 r 引用定位列（跳过的空单元格补空串，不串列）；
 * 大数科学计数法（1.73E12）还原为整数文本。不在范围：公式重算（取缓存值）、
 * 样式/合并单元格/日期格式化——日期列建议源头导出 epoch millis 或 ISO 文本。
 */
public final class XlsxParser {

    private XlsxParser() {
    }

    /** 解析为行数组：首行为表头，每行长度 = 该行最大列引用 + 1，空洞为空串。 */
    public static List<String[]> parse(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> shared = readSharedStrings(zip);
            String sheetPath = firstSheetPath(zip);
            ZipEntry entry = zip.getEntry(sheetPath);
            if (entry == null) {
                throw new IOException("xlsx 缺少工作表条目: " + sheetPath);
            }
            Document sheet = parseXml(zip.getInputStream(entry));
            NodeList rows = sheet.getElementsByTagName("row");
            List<String[]> out = new ArrayList<>(rows.getLength());
            for (int i = 0; i < rows.getLength(); i++) {
                out.add(parseRow((Element) rows.item(i), shared));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("xlsx 解析失败: " + e.getMessage(), e);
        }
    }

    private static String[] parseRow(Element row, List<String> shared) throws IOException {
        List<String> cells = new ArrayList<>();
        int nextSequential = 0;
        NodeList cellNodes = row.getElementsByTagName("c");
        for (int i = 0; i < cellNodes.getLength(); i++) {
            Element cell = (Element) cellNodes.item(i);
            int col = colOf(cell.getAttribute("r"));
            if (col < 0) {
                col = nextSequential;   // 无 r 引用时退回文档序
            }
            nextSequential = col + 1;
            while (cells.size() <= col) {
                cells.add("");
            }
            cells.set(col, cellText(cell, shared));
        }
        return cells.toArray(new String[0]);
    }

    /** 单元格文本：t=s 共享字符串 / t=inlineStr 内联 / t=b 布尔 / 其余按数值原文（含公式缓存值）。 */
    private static String cellText(Element cell, List<String> shared) throws IOException {
        String t = cell.getAttribute("t");
        switch (t) {
            case "s": {
                NodeList vs = cell.getElementsByTagName("v");
                if (vs.getLength() == 0) {
                    return "";
                }
                int idx;
                try {
                    idx = Integer.parseInt(vs.item(0).getTextContent().trim());
                } catch (NumberFormatException e) {
                    throw new IOException("共享字符串索引非法: " + vs.item(0).getTextContent());
                }
                if (idx < 0 || idx >= shared.size()) {
                    throw new IOException("共享字符串索引越界: " + idx + "（共 " + shared.size() + " 条）");
                }
                return shared.get(idx);
            }
            case "inlineStr": {
                NodeList is = cell.getElementsByTagName("is");
                if (is.getLength() == 0) {
                    return "";
                }
                NodeList ts = ((Element) is.item(0)).getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ts.getLength(); i++) {
                    sb.append(ts.item(i).getTextContent());
                }
                return sb.toString();
            }
            case "b": {
                NodeList vs = cell.getElementsByTagName("v");
                return vs.getLength() > 0 && "1".equals(vs.item(0).getTextContent().trim()) ? "true" : "false";
            }
            default: {
                NodeList vs = cell.getElementsByTagName("v");
                return vs.getLength() > 0 ? normalizeNumber(vs.item(0).getTextContent()) : "";
            }
        }
    }

    /** 科学计数法整数还原（Excel 对大数可能写 1.735689605E12）；其余原样。 */
    static String normalizeNumber(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int e = s.indexOf('E') >= 0 ? s.indexOf('E') : s.indexOf('e');
        if (e <= 0) {
            return s;
        }
        try {
            double d = Double.parseDouble(s);
            if (!Double.isInfinite(d) && Math.abs(d) < 9.2e18 && d == Math.rint(d)) {
                return Long.toString((long) d);
            }
        } catch (NumberFormatException ignored) {
            // 非数值文本原样返回
        }
        return s;
    }

    /** "B3" → 1（0 起）；无字母前缀返回 -1。 */
    static int colOf(String cellRef) {
        if (cellRef == null) {
            return -1;
        }
        int idx = 0;
        boolean hasLetter = false;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = cellRef.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                idx = idx * 26 + (ch - 'A' + 1);
                hasLetter = true;
            } else if (ch >= 'a' && ch <= 'z') {
                idx = idx * 26 + (ch - 'a' + 1);
                hasLetter = true;
            } else {
                break;
            }
        }
        return hasLetter ? idx - 1 : -1;
    }

    /** xl/sharedStrings.xml → 每个 si 的全部 t 文本拼接（富文本 run）；条目缺失返回空表。 */
    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        List<String> out = new ArrayList<>();
        if (entry == null) {
            return out;
        }
        NodeList sis = parseXml(zip.getInputStream(entry)).getElementsByTagName("si");
        for (int i = 0; i < sis.getLength(); i++) {
            NodeList ts = ((Element) sis.item(i)).getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < ts.getLength(); j++) {
                sb.append(ts.item(j).getTextContent());
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 首个工作表路径：workbook.xml 第一个 sheet 的 r:id → workbook.xml.rels 的 Target。 */
    private static String firstSheetPath(ZipFile zip) throws Exception {
        ZipEntry wb = zip.getEntry("xl/workbook.xml");
        if (wb == null) {
            throw new IOException("非 xlsx 结构：缺少 xl/workbook.xml");
        }
        Document doc = parseXml(zip.getInputStream(wb));
        String rid = "";
        NodeList sheets = doc.getElementsByTagName("sheet");
        if (sheets.getLength() > 0) {
            Element sheet = (Element) sheets.item(0);
            rid = sheet.getAttribute("r:id");
            if (rid.isEmpty()) {
                rid = sheet.getAttributeNS(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            }
        }
        if (!rid.isEmpty()) {
            ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
            if (rels != null) {
                NodeList relations = parseXml(zip.getInputStream(rels)).getElementsByTagName("Relationship");
                for (int i = 0; i < relations.getLength(); i++) {
                    Element rel = (Element) relations.item(i);
                    if (rid.equals(rel.getAttribute("Id"))) {
                        String target = rel.getAttribute("Target");
                        if (target.startsWith("/")) {
                            return target.substring(1);
                        }
                        return "xl/" + target;
                    }
                }
            }
        }
        // 关系缺失时按约定路径兜底
        return "xl/worksheets/sheet1.xml";
    }

    /** DOM 解析（禁 DTD，防 XXE；xlsx 合法内容不含 DTD）。 */
    private static Document parseXml(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // 特性不被实现支持时按默认行为继续
        }
        DocumentBuilder db = f.newDocumentBuilder();
        try (InputStream stream = in) {
            return db.parse(stream);
        }
    }
}
