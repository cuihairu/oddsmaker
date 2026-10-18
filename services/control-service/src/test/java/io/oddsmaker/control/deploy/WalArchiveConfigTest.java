package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAL 归档参数名钉测试。wal_archive_timeout 不是 PostgreSQL GUC（真名 archive_timeout），
 * 错名参数会让 postgres 启动直接 FATAL「unrecognized configuration parameter」
 * （2026-10-19 PITR 演练实证：镜像生产 compose 同款 -c 参数起容器，秒退 crash 循环）。
 * 钉住根 compose 与 k8s 清单两处启动参数的四条 GUC 名，并全 deploy 面禁绝错名回归。
 */
class WalArchiveConfigTest {

    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    private static final List<String> REQUIRED_GUCS = List.of(
            "archive_mode=on",
            "wal_level=replica",
            "max_wal_senders=3",
            "archive_timeout=30s");

    private static String read(String relative) throws IOException {
        return Files.readString(REPO.resolve(relative));
    }

    @Test
    void rootComposeCarriesValidArchiveGucs() throws IOException {
        String compose = read("docker-compose.yml");
        REQUIRED_GUCS.forEach(guc -> assertTrue(compose.contains("- " + guc),
                "docker-compose.yml postgres 启动参数缺 " + guc));
    }

    @Test
    void k8sPostgresCarriesValidArchiveGucs() throws IOException {
        String manifest = read("deploy/k8s/postgres.yaml");
        REQUIRED_GUCS.forEach(guc -> assertTrue(manifest.contains("- " + guc),
                "deploy/k8s/postgres.yaml args 缺 " + guc));
    }

    @Test
    void bogusWalArchiveTimeoutNowhereInDeploySurface() throws IOException {
        try (Stream<Path> files = Files.walk(REPO.resolve("deploy"))) {
            List<Path> hits = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".md");
                    })
                    .filter(WalArchiveConfigTest::containsBogus)
                    .toList();
            assertTrue(hits.isEmpty(), "wal_archive_timeout 不是合法 GUC，出现在: " + hits);
        }
        assertFalse(read("docker-compose.yml").contains("wal_archive_timeout"),
                "docker-compose.yml 不应含错名 wal_archive_timeout");
    }

    @Test
    void composeAndK8sCarryIdenticalArchiveGucSets() throws IOException {
        // backup-strategy.md §PostgreSQL 声称 k8s args 与根 compose command 等价——钉住不漂移
        assertEquals(gucArgs(read("docker-compose.yml")), gucArgs(read("deploy/k8s/postgres.yaml")),
                "根 compose 与 k8s 清单的 postgres -c 参数集应一致");
    }

    /** 提取「- postgres / - -c / - key=value …」连续参数块里的 GUC 赋值（跳过 -c 标记）。 */
    private static List<String> gucArgs(String yaml) {
        List<String> lines = yaml.lines().map(String::trim).toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("- postgres".equals(lines.get(i))) {
                start = i + 1;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(start > 0, "未找到 '- postgres' 启动参数块");
        return lines.subList(start, lines.size()).stream()
                .takeWhile(l -> l.startsWith("- "))
                .map(l -> l.substring(2))
                .filter(v -> !"-c".equals(v))
                .sorted()
                .toList();
    }

    private static boolean containsBogus(Path p) {
        try {
            return Files.readString(p).contains("wal_archive_timeout");
        } catch (IOException e) {
            return false;
        }
    }
}
