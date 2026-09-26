package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 断点存取：JSON 往返、缺失文件返回空断点、原子落盘无 .tmp 残留。 */
class CheckpointStoreTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("save→load 往返保留全部字段")
    void saveLoadRoundTrip() throws Exception {
        CheckpointStore store = new CheckpointStore(dir.resolve("checkpoint.json"));
        Checkpoint cp = new Checkpoint();
        cp.cursor = "n:12345";
        cp.files.put("a.csv", 3L);
        cp.files.put("b.csv", 7L);
        cp.lastEventTs = 1735689605000L;
        cp.pushedCount = 10;
        cp.errorCount = 2;
        cp.lastError = "boom";
        store.save(cp);

        Checkpoint loaded = store.load();
        assertEquals("n:12345", loaded.cursor);
        assertEquals(3L, loaded.files.get("a.csv"));
        assertEquals(7L, loaded.files.get("b.csv"));
        assertEquals(1735689605000L, loaded.lastEventTs);
        assertEquals(10, loaded.pushedCount);
        assertEquals(2, loaded.errorCount);
        assertEquals("boom", loaded.lastError);
    }

    @Test
    @DisplayName("文件缺失返回空断点")
    void missingFileYieldsEmptyCheckpoint() throws Exception {
        Checkpoint cp = new CheckpointStore(dir.resolve("absent.json")).load();
        assertNull(cp.cursor);
        assertTrue(cp.files.isEmpty());
        assertNull(cp.lastEventTs);
        assertEquals(0, cp.pushedCount);
        assertEquals(0, cp.errorCount);
    }

    @Test
    @DisplayName("原子落盘：无 .tmp 残留；覆盖写生效；子目录自动创建")
    void atomicWriteNoTmpLeftover() throws Exception {
        Path path = dir.resolve("nested").resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(path);

        Checkpoint first = new Checkpoint();
        first.cursor = "n:1";
        store.save(first);

        assertFalse(Files.exists(path.resolveSibling("checkpoint.json.tmp")));
        Checkpoint second = new Checkpoint();
        second.cursor = "n:2";
        store.save(second);
        assertEquals("n:2", store.load().cursor);
        assertFalse(Files.exists(path.resolveSibling("checkpoint.json.tmp")));
    }

    @Test
    @DisplayName("copy 深拷贝 files 表，互不影响")
    void copyIsolatesFiles() {
        Checkpoint cp = new Checkpoint();
        cp.files.put("a.csv", 1L);
        Checkpoint copy = cp.copy();
        copy.files.put("b.csv", 2L);
        assertFalse(cp.files.containsKey("b.csv"));
    }
}
