package io.oddsmaker.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV 解析：引号包裹、"" 转义、引号内跨行/逗号、\r\n 与 \n 换行；文件头 BOM 跳过。
 * 维度表量级可控（每行一个 item/level），整文件读入按索引解析，带前视无需回退 Reader。
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<String[]> parse(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return parse(sb.toString());
    }

    public static List<String[]> parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<String[]> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    i++;
                }
                case ',' -> {
                    cur.add(field.toString());
                    field.setLength(0);
                    i++;
                }
                case '\r' -> {
                    i += (i + 1 < n && text.charAt(i + 1) == '\n') ? 2 : 1;
                    endRow(rows, cur, field);
                }
                case '\n' -> {
                    i++;
                    endRow(rows, cur, field);
                }
                default -> {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            endRow(rows, cur, field);
        }
        return rows;
    }

    private static void endRow(List<String[]> rows, List<String> cur, StringBuilder field) {
        cur.add(field.toString());
        field.setLength(0);
        rows.add(cur.toArray(String[]::new));
        cur.clear();
    }
}
