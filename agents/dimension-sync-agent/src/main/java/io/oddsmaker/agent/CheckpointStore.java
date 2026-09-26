package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 断点存取：JSON 文件 + 原子落盘（写临时文件后 ATOMIC_MOVE，move 不支持时退回 REPLACE_EXISTING）。
 */
public final class CheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path path;

    public CheckpointStore(Path path) {
        this.path = path;
    }

    /** 读断点；文件不存在返回空断点（首次运行）。 */
    public Checkpoint load() throws IOException {
        if (!Files.exists(path)) {
            return new Checkpoint();
        }
        return MAPPER.readValue(path.toFile(), Checkpoint.class);
    }

    /** 原子落盘：同目录写 .tmp 后 move。 */
    public void save(Checkpoint cp) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cp));
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
