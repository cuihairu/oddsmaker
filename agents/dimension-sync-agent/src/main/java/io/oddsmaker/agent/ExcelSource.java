package io.oddsmaker.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Excel（.xlsx）文件源：与 {@link CsvSource} 同语义——按文件名序扫描目录下 *.xlsx，
 * 文件粒度断点（处理完整文件才记入 checkpoint.files，同名不重读——增量约定是投递
 * 新文件，不改旧文件）；首个工作表首行为表头，列名语义与 JdbcSource.mapRow 一致。
 * 表内容解析见 {@link XlsxParser}（仅标准库 zip+xml，无 POI）。
 */
public final class ExcelSource implements DimensionSource {

    private final AgentConfig cfg;
    private final Path dir;

    public ExcelSource(AgentConfig cfg) {
        this.cfg = cfg;
        this.dir = Path.of(cfg.excelDir);
    }

    @Override
    public String name() {
        return "excel:" + cfg.excelDir;
    }

    @Override
    public String type() {
        return "excel";
    }

    @Override
    public PollResult poll(Checkpoint current) throws IOException {
        List<Path> pending;
        try (Stream<Path> list = Files.list(dir)) {
            pending = list
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .filter(p -> !current.files.containsKey(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        List<DimensionChange> changes = new ArrayList<>();
        Checkpoint next = current.copy();
        long now = System.currentTimeMillis();
        for (Path p : pending) {
            List<String[]> rows = XlsxParser.parse(p);
            if (rows.isEmpty()) {
                next.files.put(p.getFileName().toString(), 0L);
                continue;
            }
            String[] header = rows.get(0);
            int dataRows = 0;
            for (int r = 1; r < rows.size(); r++) {
                Map<String, String> row = new java.util.LinkedHashMap<>();
                String[] cells = rows.get(r);
                for (int c = 0; c < header.length && c < cells.length; c++) {
                    if (header[c] != null && !header[c].isBlank()) {
                        row.put(header[c].trim().toLowerCase(), cells[c]);
                    }
                }
                DimensionChange change = JdbcSource.mapRow(row, cfg.dimType, now);
                if (change.resourceId == null || change.resourceId.isBlank()) {
                    System.err.println("[excel] 跳过缺 resource_id 的行: " + p.getFileName() + ": sheet 行 " + (r + 1));
                    continue;
                }
                changes.add(change);
                dataRows++;
            }
            next.files.put(p.getFileName().toString(), (long) dataRows);
        }
        if (!changes.isEmpty()) {
            next.lastEventTs = changes.stream().mapToLong(c -> c.versionTs).max().orElse(now);
        }
        return PollResult.of(changes, next);
    }
}
