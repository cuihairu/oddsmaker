package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV 源：文件序扫描、文件粒度断点（处理过的不重读）、缺 resource_id 行跳过。 */
class CsvSourceTest {

    @TempDir
    Path dir;

    private AgentConfig cfg() {
        AgentConfig cfg = new AgentConfig();
        cfg.csvDir = dir.toString();
        cfg.dimType = "item";
        return cfg;
    }

    @Test
    @DisplayName("按文件名序拉取全部待处理 csv，控制列映射正确")
    void pollsAllPendingCsvFilesInNameOrder() throws Exception {
        Files.writeString(dir.resolve("a_items.csv"), """
                dim_type,item_code,name,rarity,op,version_ts
                item,sword_01,铁剑,sr,upsert,1735689605000
                """);
        Files.writeString(dir.resolve("b_levels.csv"), """
                dim_type,level_id,name,difficulty,version_ts
                level,lv_1,新手村,1,1000000000000
                """);

        CsvSource s = new CsvSource(cfg());
        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(2, r.changes().size());
        DimensionChange item = r.changes().get(0);
        assertEquals("sword_01", item.resourceId);
        assertEquals("item", item.dimType);
        assertEquals("sr", item.attributes.get("rarity"));
        assertEquals(1735689605000L, item.versionTs);
        DimensionChange level = r.changes().get(1);
        assertEquals("lv_1", level.resourceId);
        assertEquals("level", level.dimType);
        assertEquals("1", level.attributes.get("difficulty"));
        assertEquals(1000000000000L, level.versionTs);
        assertTrue(r.next().files.containsKey("a_items.csv"));
        assertTrue(r.next().files.containsKey("b_levels.csv"));
        // lastEventTs = 全部变更的 max version_ts（跨文件取最新）
        assertEquals(1735689605000L, r.next().lastEventTs);
    }

    @Test
    @DisplayName("文件粒度断点：checkpoint.files 里已有的文件不重读")
    void processedFilesAreNotReread() throws Exception {
        Files.writeString(dir.resolve("a.csv"), "item_code,name\nsword_01,铁剑\n");
        CsvSource s = new CsvSource(cfg());
        DimensionSource.PollResult first = s.poll(new Checkpoint());
        assertEquals(1, first.changes().size());

        DimensionSource.PollResult second = s.poll(first.next());
        assertTrue(second.changes().isEmpty());
        assertEquals(1L, second.next().files.get("a.csv"));

        // 新文件出现 → 只拉新文件
        Files.writeString(dir.resolve("b.csv"), "item_code,name\nshield_01,木盾\n");
        DimensionSource.PollResult third = s.poll(second.next());
        assertEquals(1, third.changes().size());
        assertEquals("shield_01", third.changes().get(0).resourceId);
    }

    @Test
    @DisplayName("缺 resource_id 的行跳过，不阻塞其余行")
    void rowsWithoutResourceIdAreSkipped() throws Exception {
        Files.writeString(dir.resolve("a.csv"), """
                item_code,name
                sword_01,铁剑
                ,无名行
                """);
        DimensionSource.PollResult r = new CsvSource(cfg()).poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        assertEquals(1L, r.next().files.get("a.csv")); // 数据行计数，跳过行不算
    }

    @Test
    @DisplayName("非 csv 文件与 version_ts ISO 单元格")
    void ignoresNonCsvAndParsesIsoVersionTs() throws Exception {
        Files.writeString(dir.resolve("notes.txt"), "item_code\nnot_csv\n");
        Files.writeString(dir.resolve("a.csv"), """
                item_code,version_ts
                sword_01,2026-01-01T00:00:05Z
                """);
        DimensionSource.PollResult r = new CsvSource(cfg()).poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:05Z").toEpochMilli(),
                r.changes().get(0).versionTs);
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:05Z").toEpochMilli(),
                r.next().lastEventTs);
        assertFalse(r.next().files.containsKey("notes.txt"));
    }

    @Test
    @DisplayName("空目录：无变更，next 与 current 独立")
    void emptyDirYieldsNoChanges() throws Exception {
        DimensionSource.PollResult r = new CsvSource(cfg()).poll(new Checkpoint());
        assertTrue(r.changes().isEmpty());
        assertTrue(r.next().files.isEmpty());
        assertEquals("csv", new CsvSource(cfg()).type());
        assertTrue(new CsvSource(cfg()).name().startsWith("csv:"));
    }

    @Test
    @DisplayName("引号字段（含逗号/换行）正确解析为属性")
    void quotedCells() throws Exception {
        Files.writeString(dir.resolve("a.csv"), "item_code,name,description\nsword_01,\"铁剑, 长\",\"第一行\n第二行\"\n");
        List<DimensionChange> changes = new CsvSource(cfg()).poll(new Checkpoint()).changes();
        assertEquals(1, changes.size());
        assertEquals("铁剑, 长", changes.get(0).attributes.get("name"));
        assertEquals("第一行\n第二行", changes.get(0).attributes.get("description"));
    }
}
