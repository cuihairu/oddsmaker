package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * clickhouse-backup.sh 与 Altinity clickhouse-backup 2.8.x 真实 CLI 的契约钉。
 *
 * <p>2026-10-19 DR-001 ClickHouse 恢复演练首次真实执行该脚本（clickhouse/clickhouse-server:24.8
 * + clickhouse-backup 2.8.1 二进制），发现脚本全部调用面与 2.8.x 不符——入库以来从未被执行过：
 * <ul>
 *   <li>连接信息没有 CLI 参数：--host/--port/--user/--password 一律
 *       {@code flag provided but not defined}，只认 config/env（clickhouse.host/port/
 *       <b>username</b>/password——键名是 username，CLICKHOUSE_USER 不能直透）；</li>
 *   <li>{@code create} 没有 --diff-from（增量只存在于远程上传面：
 *       {@code upload <name> --diff-from=<上次 full>}）；</li>
 *   <li>{@code upload} 没有 --storage-class（存储类别走 s3.storage_class，env S3_STORAGE_CLASS）；</li>
 *   <li>工具不读任何本地备份目录配置：备份固定落 CH 数据目录下的 {@code backup/}
 *       （docker 镜像即 /var/lib/clickhouse/backup），脚本 BACKUP_DIR 默认须对齐，
 *       否则留存清理 find 扫的是工具从不写入的目录（静默无清理）。</li>
 * </ul>
 *
 * <p>部署形态约束（演练实证）：工具经硬链接 CH 数据 parts 生成备份，必须与 CH 数据卷同路径
 * 挂载运行（CH 容器内或共享数据卷的 sidecar），不是纯网络客户端——用 --network container:*
 * 只共享网络命名空间会 FATAL「disk default path /var/lib/clickhouse/ is not exists locally」。
 */
class ClickhouseBackupCliContractTest {

    /** 仓库根（gradle Test 默认 workingDirectory = services/control-service）。 */
    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    private static String script() throws IOException {
        return Files.readString(REPO.resolve("deploy/backup/clickhouse-backup.sh"));
    }

    @Test
    @DisplayName("连接信息不经 CLI 参数传递（2.8.x 一律 flag provided but not defined）")
    void noConnectionCliFlagsAnywhere() throws IOException {
        String sh = script();
        List<String> banned = List.of("--host ", "--port ", "--user ", "--password ", "CH_FLAGS");
        for (String flag : banned) {
            assertFalse(sh.contains(flag),
                    "clickhouse-backup 2.8.x 无连接类 CLI 参数，脚本不得再拼 " + flag.trim()
                            + "（实测 flag provided but not defined），连接应走环境变量");
        }
    }

    @Test
    @DisplayName("连接走工具环境变量名：CLICKHOUSE_USER 桥接为 CLICKHOUSE_USERNAME（键名 username）")
    void connectionGoesThroughToolEnvNames() throws IOException {
        String sh = script();
        assertTrue(sh.contains("export CLICKHOUSE_HOST="),
                "脚本应把 CLICKHOUSE_HOST 直透给工具（clickhouse.host）");
        assertTrue(sh.contains("export CLICKHOUSE_PORT="),
                "脚本应把 CLICKHOUSE_PORT 直透给工具（clickhouse.port）");
        assertTrue(sh.contains("export CLICKHOUSE_USERNAME="),
                "2.8.x config 键是 username 非 user：脚本消费 CLICKHOUSE_USER 必须桥接导出 CLICKHOUSE_USERNAME，"
                        + "否则静默落回 default 用户");
        assertTrue(sh.contains("export CLICKHOUSE_PASSWORD="),
                "脚本应把 CLICKHOUSE_PASSWORD 直透给工具（clickhouse.password）");
    }

    @Test
    @DisplayName("--diff-from 只允许出现在 upload 调用行（create 无此参数）")
    void diffFromOnlyOnUploadFace() throws IOException {
        for (String line : script().lines().toList()) {
            if (line.contains("--diff-from")) {
                assertTrue(line.toLowerCase().contains("upload"),
                        "--diff-from 只能出现在 upload 调用行（create 无此参数，实测 flag provided but not defined）："
                                + line.trim());
            }
        }
    }

    @Test
    @DisplayName("upload 不携带 --storage-class CLI 参数（存储类别走 S3_STORAGE_CLASS 环境变量）")
    void noStorageClassCliFlag() throws IOException {
        assertFalse(script().contains("--storage-class"),
                "2.8.x upload 无 --storage-class 参数；存储类别经 s3.storage_class（env S3_STORAGE_CLASS）注入");
    }

    @Test
    @DisplayName("BACKUP_DIR 默认对齐工具真实落点 <CH 数据目录>/backup（否则留存清理扫空目录）")
    void backupDirDefaultsToToolRealLocation() throws IOException {
        String sh = script();
        assertTrue(sh.contains("BACKUP_DIR=\"${BACKUP_DIR:-/var/lib/clickhouse/backup}\""),
                "工具不读备份目录配置，本地备份固定落 CH 数据目录 backup/ 下；"
                        + "默认值必须对齐 /var/lib/clickhouse/backup，当前：\n"
                        + sh.lines().filter(l -> l.contains("BACKUP_DIR=")).toList());
    }

    @Test
    @DisplayName("配置 S3_BUCKET 时显式 export REMOTE_STORAGE=s3（工具默认 remote_storage: none，不设则 upload 报错）")
    void offsiteWiresRemoteStorageEnvWhenBucketConfigured() throws IOException {
        assertTrue(script().contains("REMOTE_STORAGE=s3"),
                "工具默认 general.remote_storage=none，配了 S3_BUCKET 不设 REMOTE_STORAGE=s3 时 upload 直接失败");
    }

    // ===== 沙箱行为测试：桩 clickhouse-backup 记录调用面 =====

    /** 沙箱 PATH 只留桩 bin，脚本用到的宿主 coreutils/awk 逐个链入（同 BackupScriptTest 手法）。 */
    private static void linkSystemTools(Path binDir) throws IOException {
        for (String tool : List.of("date", "awk", "sort", "tail", "mkdir", "find", "rm")) {
            for (String dir : List.of("/usr/bin", "/bin")) {
                Path candidate = Path.of(dir, tool);
                if (Files.isExecutable(candidate)) {
                    try {
                        Files.createSymbolicLink(binDir.resolve(tool), candidate);
                    } catch (java.nio.file.FileAlreadyExistsException ignored) { }
                    break;
                }
            }
        }
    }

    private record Run(int exitCode, String stdout, String trace) { }

    /**
     * 桩：list local 打一行 full_<DATE> 表（真实文本格式：名称在首列）；
     * create/upload/list 按首参数分流记录到 trace。
     */
    private static String stubBinary(Path trace) {
        String t = trace.toAbsolutePath().toString();
        return "#!/bin/bash\n"
                + "case \"$1\" in\n"
                + "  list) [ \"$2\" = local ] && echo \"full_20261001   2026-10-01 00:00:00   local   all:1KiB   regular\" ;;\n"
                + "  create) echo \"create $2\" >> " + t + " ;;\n"
                + "  upload) { echo -n \"upload $2\"; [ -n \"$3\" ] && echo -n \" $3\"; echo; } >> " + t + " ;;\n"
                + "esac\n"
                + "exit 0\n";
    }

    private static Run runScript(String type, Path binDir, Map<String, String> extraEnv) throws Exception {
        Path script = REPO.resolve("deploy/backup/clickhouse-backup.sh");
        assertTrue(Files.exists(script), "clickhouse-backup.sh missing: " + script);

        Map<String, String> env = new HashMap<>();
        env.put("PATH", binDir.toAbsolutePath().toString());
        env.put("BACKUP_DIR", binDir.getParent().resolve("backup").toAbsolutePath().toString());
        env.put("RETENTION_DAYS", "90");
        env.putAll(extraEnv);

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString(), type));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("ch-contract-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "clickhouse-backup.sh timed out");
        String text = Files.readString(out);
        String tracePath = extraEnv.get("__TRACE");
        String trace = tracePath == null ? "" : Files.readString(Path.of(tracePath));
        return new Run(p.exitValue(), text, trace);
    }

    @Test
    @DisplayName("incremental：有上次 full 时经 upload --diff-from 构建增量链，create 不带 --diff-from")
    void incrementalUploadsDiffFromLastFull() throws Exception {
        Path root = Files.createTempDirectory("ch-contract-incr");
        Path bin = Files.createDirectories(root.resolve("bin"));
        linkSystemTools(bin);
        Path trace = root.resolve("trace.log");
        Files.writeString(trace, "", StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("clickhouse-backup"), stubBinary(trace),
                StandardCharsets.UTF_8);
        bin.resolve("clickhouse-backup").toFile().setExecutable(true);

        Run run = runScript("incremental", bin, Map.of(
                "S3_BUCKET", "oddsmaker-backups",
                "__TRACE", trace.toAbsolutePath().toString()));

        assertEquals(0, run.exitCode(), "桩全成功应 exit 0；实际输出：\n" + run.stdout());
        assertTrue(run.trace().contains("create incremental_"),
                "增量应先 create 本地档；trace:\n" + run.trace());
        String createLine = run.trace().lines().filter(l -> l.startsWith("create ")).findFirst().orElse("");
        assertFalse(createLine.contains("--diff-from"),
                "create 面不得带 --diff-from（2.8.x 无此参数）；create 行：" + createLine);
        assertTrue(run.trace().matches("(?s).*upload incremental_\\S+ --diff-from=full_20261001\\s*"),
                "增量应 upload incremental_* --diff-from=<上次 full>；trace:\n" + run.trace());
    }

    @Test
    @DisplayName("incremental：未配 S3_BUCKET 时退化全量并显式说明（2.8.x 无本地-only 增量）")
    void incrementalWithoutRemoteFallsBackToFullWithExplicitLog() throws Exception {
        Path root = Files.createTempDirectory("ch-contract-fallback");
        Path bin = Files.createDirectories(root.resolve("bin"));
        linkSystemTools(bin);
        Path trace = root.resolve("trace.log");
        Files.writeString(trace, "", StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("clickhouse-backup"), stubBinary(trace),
                StandardCharsets.UTF_8);
        bin.resolve("clickhouse-backup").toFile().setExecutable(true);

        Run run = runScript("incremental", bin, Map.of("__TRACE", trace.toAbsolutePath().toString()));

        assertEquals(0, run.exitCode(), "无远程时增量退化全量不算失败；实际输出：\n" + run.stdout());
        assertTrue(run.trace().contains("create full_"),
                "无远程增量应退化为 create full_*；trace:\n" + run.trace());
        assertFalse(run.trace().contains("upload"), "未配 S3_BUCKET 不应尝试上传；trace:\n" + run.trace());
        assertTrue(run.stdout().toLowerCase().contains("fallback") || run.stdout().toLowerCase().contains("退"),
                "退化路径须在日志里喊出来，不得静默冒充增量；实际输出：\n" + run.stdout());
    }
}
