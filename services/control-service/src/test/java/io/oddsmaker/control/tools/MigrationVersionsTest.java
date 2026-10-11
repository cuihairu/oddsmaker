package io.oddsmaker.control.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移版本号唯一性防回归：同版本两个迁移会让 flywayInitializer 启动即失败
 * （"Found more than one migration with version X"）。本仓单测档 flyway 关闭
 * （ddl-auto=create-drop）、全量 gradle 测试不启动 Flyway，该类失败只在真实 boot
 * （demo deploy 健康门）暴露——2026-10-11 实例：V0.9.20 被 B7 占用后 HTTP Pull 批
 * 又落了一个 V0.9.20，demo 部署回滚才抓出。此处读迁移目录做静态校验，把它拉回单测面。
 */
@DisplayName("Flyway 迁移版本唯一性")
class MigrationVersionsTest {

    private static final Pattern VERSION = Pattern.compile("^V([\\d.]+)__.*\\.sql$");

    @Test
    void versionNumbersAreUnique() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "迁移目录不存在（以模块目录为工作目录）: " + dir.toAbsolutePath());
        assertFalse(findByGlob(dir).isEmpty(), "迁移目录里没有 .sql 文件");
        List<String> dupes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            Map<String, List<String>> byVersion = new LinkedHashMap<>();
            files.map(p -> p.getFileName().toString())
                    .filter(name -> VERSION.matcher(name).matches())
                    .sorted()
                    .forEach(name -> byVersion
                            .computeIfAbsent(VERSION.matcher(name).replaceAll("$1"), k -> new ArrayList<>())
                            .add(name));
            byVersion.forEach((version, names) -> {
                if (names.size() > 1) {
                    dupes.add("V" + version + " -> " + names);
                }
            });
        }
        assertTrue(dupes.isEmpty(), "重复 Flyway 版本号会导致启动失败: " + dupes);
    }

    /** 兜底：目录里至少要有迁移文件（防止目录被移走后上面静默通过）。 */
    private static List<String> findByGlob(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).toList();
        }
    }
}
