package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份脚本契约测试：把 k8s CronJob ConfigMap 内嵌的 backup.sh 与
 * deploy/backup/postgres-backup.sh 在沙箱里真跑一遍（桩 pg_dump/pg_restore，无 aws CLI）。
 *
 * <p>守护的回归：脚本以 {@code set -e} 运行，而 k8s 侧 CronJob 镜像
 * {@code postgres:16-alpine} 内无 aws CLI（实测 command -v aws 为空）。若上传分支不先判存在，
 * {@code aws: not found} 会让 set -e 直接中止——S3 离站副本从未产生，且其后的
 * {@code find -mtime -delete} 留存清理同样不执行，备份 PVC 无界增长直到写满。
 * 本测试要求（审计 DR-001 后契约）：缺 aws / 上传失败时本地备份与留存清理照常完成，
 * 但结尾以 exit 2 显式失败——离站缺口不得只留在日志里静默成功；上传成功才 exit 0。
 *
 * <p>沙箱 PATH 刻意只留桩 bin（宿主 coreutils 由 {@code linkSystemTools} 按需符号链接，
 * 见 {@link #SANDBOX_TOOLS}）。GH-hosted runner 预装 aws CLI，早年把整段宿主目录挂进 PATH
 * 的做法会让「镜像内无 aws」这条前提在 CI 上静默失效——脚本真的去传 S3，无凭证失败被
 * set -e 带走，留存清理连带跳过（已发生过，见 a0c1b9f 之后的 CI 红）。
 */
class BackupScriptTest {

    /** 仓库根（gradle Test 默认 workingDirectory = services/control-service）。 */
    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    /** 桩：pg_dump 写出目标文件（解析 --file=），pg_restore --list 恒成功。 */
    private static void writeStub(Path binDir, String name, String body) throws IOException {
        Path stub = binDir.resolve(name);
        Files.writeString(stub, body, StandardCharsets.UTF_8);
        stub.toFile().setExecutable(true);
    }

    /** 宿主工具可搜索目录，仅用于「找」符号链接源，本身不进入沙箱 PATH。 */
    private static final String HOST_TOOL_DIRS = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /**
     * 沙箱内必须的宿主 coreutils。GH-hosted runner 预装 aws CLI，若像早年那样把整段
     * 宿主目录挂进 PATH，「镜像内无 aws」这条前提在 CI 上会失效（脚本真的去传 S3，
     * 无凭证失败被 set -e 带走）。故 PATH 只留桩 bin，把用到的工具逐个链进来。
     */
    private static final List<String> SANDBOX_TOOLS =
            List.of("date", "tee", "mkdir", "find", "du", "cut", "rm", "tar", "gzip",
                    // clickhouse-backup.sh 的 list 管线（list local | awk | sort | tail）新增依赖
                    "awk", "sort", "tail");

    /** 把宿主 coreutils 符号链接进桩 bin；宿主缺工具时立即报清晰错误，不做静默降级。 */
    private static void linkSystemTools(Path binDir) throws IOException {
        for (String tool : SANDBOX_TOOLS) {
            Path src = findHostTool(tool);
            try {
                Files.createSymbolicLink(binDir.resolve(tool), src);
            } catch (FileAlreadyExistsException ignored) { }
        }
    }

    private static Path findHostTool(String tool) throws IOException {
        for (String dir : HOST_TOOL_DIRS.split(":")) {
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("sandbox tool not found in " + HOST_TOOL_DIRS + ": " + tool);
    }

    /**
     * 造 PATH：桩 bin 独享（pg_dump/pg_restore 被桩接管，date/mkdir/find 由 linkSystemTools
     * 链入）。刻意不挂宿主目录——复现 postgres:16-alpine 的能力边界（该镜像无 aws），
     * 且在预装 aws 的 runner 上保持同样语义。
     */
    private static Map<String, String> sandboxEnv(Path binDir, Path backupDir) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", binDir.toAbsolutePath().toString());
        env.put("BACKUP_DIR", backupDir.toAbsolutePath().toString());
        env.put("DB_HOST", "oddsmaker-postgres");
        env.put("DB_PORT", "5432");
        env.put("DB_NAME", "oddsmaker");
        env.put("DB_USER", "oddsmaker");
        env.put("DB_PASSWORD", "pw");
        env.put("S3_BUCKET", "oddsmaker-backups");
        return env;
    }

    /** 沙箱 PATH 下命令是否可解析（前提校验：aws 不应可解析）。 */
    private static boolean commandExists(Path binDir, String name) {
        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-c",
                "PATH=" + binDir.toAbsolutePath() + " command -v " + name));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(30, TimeUnit.SECONDS);
            return p.exitValue() == 0 && !out.isEmpty();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Run(int exitCode, String stdout, String stderr) { }

    private static Run runScript(Path script, Path binDir, Path backupDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString()));
        pb.environment().clear();
        pb.environment().putAll(sandboxEnv(binDir, backupDir));
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("backup-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "backup script timed out");
        String text = Files.readString(out);
        return new Run(p.exitValue(), text, "");
    }

    private static Path freshDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /** 造一份超过留存期的旧备份，供清理断言用。 */
    private static Path agedBackup(Path backupDir, String name, int ageDays) throws IOException {
        Path f = backupDir.resolve(name);
        Files.writeString(f, "stale");
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)));
        return f;
    }

    // ===== k8s CronJob 内嵌 backup.sh =====

    private static String embeddedCronJobScript() throws IOException {
        Path yaml = REPO.resolve("deploy/k8s/backup-cronjob.yaml");
        assertTrue(Files.exists(yaml), "backup-cronjob.yaml missing: " + yaml);
        for (Object doc : new Yaml().loadAll(Files.readString(yaml))) {
            if (!(doc instanceof Map<?, ?> m)) continue;
            if (!"ConfigMap".equals(m.get("kind"))) continue;
            Map<?, ?> meta = (Map<?, ?>) m.get("metadata");
            if (meta == null || !"oddsmaker-backup-script".equals(meta.get("name"))) continue;
            Map<?, ?> data = (Map<?, ?>) m.get("data");
            Object sh = data == null ? null : data.get("backup.sh");
            if (sh != null) return sh.toString();
        }
        throw new AssertionError("ConfigMap oddsmaker-backup-script / data['backup.sh'] not found");
    }

    @Test
    @DisplayName("CronJob backup.sh: 镜像无 aws CLI 时留存清理照常执行，但结尾以 exit 2 报离站失败")
    void cronJobScriptSurvivesMissingAwsCli() throws Exception {
        Path root = freshDir("cronjob-backup");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\nexit 0\n");

        Path stale = agedBackup(backupDir, "postgres_20200101_000000.sql.gz", 40);
        Path script = root.resolve("backup.sh");
        Files.writeString(script, embeddedCronJobScript(), StandardCharsets.UTF_8);
        assertFalse(commandExists(bin, "aws"),
                "沙箱前提失效：桩 bin 内出现 aws，本用例就测不出「镜像无 aws」这条降级路径");

        Run run = runScript(script, bin, backupDir);

        assertEquals(2, run.exitCode(),
                "缺 aws 时离站副本缺失必须以 exit 2 显式失败（exit 0=静默成功不可接受），"
                        + "同时备份已落盘、留存清理照常；实际输出：\n" + run.stdout());
        assertFalse(Files.exists(stale),
                "超期备份未被清理——set -e 在上传分支中止导致留存清理永不执行，备份 PVC 会无界增长");
        try (var list = Files.list(backupDir)) {
            assertTrue(list.anyMatch(p -> p.getFileName().toString().matches("postgres_\\d{8}_\\d{6}\\.sql\\.gz")),
                    "本次备份未落盘；实际输出：\n" + run.stdout());
        }
        assertTrue(run.stdout().toLowerCase().contains("aws"),
                "配置了 S3_BUCKET 但镜像无 aws CLI 时应就地把离站副本缺口喊出来，避免静默丢离站备份；实际输出：\n"
                        + run.stdout());
    }

    @Test
    @DisplayName("CronJob backup.sh: BACKUP_DIR 可被环境变量覆盖（沙箱可测，默认仍为 /backup）")
    void cronJobScriptHonoursBackupDirEnv() throws IOException {
        String sh = embeddedCronJobScript();
        assertTrue(sh.contains("BACKUP_DIR=\"${BACKUP_DIR:-/backup}\""),
                "BACKUP_DIR 需形如 BACKUP_DIR=\"${BACKUP_DIR:-/backup}\" 以便沙箱测试；当前：\n"
                        + sh.lines().filter(l -> l.contains("BACKUP_DIR")).toList());
    }

    @Test
    @DisplayName("CronJob backup.sh: 离站上传成功时 exit 0（本地成功+离站成功才是成功）")
    void cronJobScriptExitsZeroWhenOffsiteSucceeds() throws Exception {
        Path root = freshDir("cronjob-backup-ok");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\nexit 0\n");
        Path trace = root.resolve("aws-trace.log");
        Files.writeString(trace, "", StandardCharsets.UTF_8);
        writeStub(bin, "aws", "#!/bin/bash\necho \"aws $@\" >> " + trace + "\nexit 0\n");

        Path script = root.resolve("backup.sh");
        Files.writeString(script, embeddedCronJobScript(), StandardCharsets.UTF_8);
        Run run = runScript(script, bin, backupDir);

        assertEquals(0, run.exitCode(),
                "S3 上传成功应 exit 0；实际输出：\n" + run.stdout());
        assertTrue(Files.readString(trace).contains("s3://oddsmaker-backups/postgres/"),
                "aws 桩未被调用或参数不对——离站上传应真实执行；trace:\n" + Files.readString(trace));
    }

    @Test
    @DisplayName("CronJob backup.sh: 离站上传失败时留存清理照常 + exit 2 非静默失败")
    void cronJobScriptFailsWhenUploadFails() throws Exception {
        Path root = freshDir("cronjob-backup-uploadfail");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\nexit 0\n");
        writeStub(bin, "aws", "#!/bin/bash\necho 'upload failed: no credentials' >&2\nexit 1\n");

        Path stale = agedBackup(backupDir, "postgres_20200101_000000.sql.gz", 40);
        Path script = root.resolve("backup.sh");
        Files.writeString(script, embeddedCronJobScript(), StandardCharsets.UTF_8);
        Run run = runScript(script, bin, backupDir);

        assertEquals(2, run.exitCode(),
                "上传失败必须 exit 2（不得静默成功、也不得把留存清理连带掐断）；实际输出：\n" + run.stdout());
        assertFalse(Files.exists(stale), "上传失败不得中止留存清理（备份 PVC 无界增长回归）");
    }

    // ===== deploy/backup/postgres-backup.sh =====

    @Test
    @DisplayName("postgres-backup.sh: 缺 aws CLI 且日志文件不可写时仍退出 0 并清理超期备份")
    void standaloneScriptSurvivesMissingAwsAndUnwritableLog() throws Exception {
        Path root = freshDir("standalone-backup");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\nexit 0\n");

        Path stale = agedBackup(backupDir, "full_20200101_000000.sql.gz", 40);
        Path script = REPO.resolve("deploy/backup/postgres-backup.sh");
        assertTrue(Files.exists(script), "postgres-backup.sh missing: " + script);

        Map<String, String> env = new HashMap<>(sandboxEnv(bin, backupDir));
        // 日志落可写临时路径：脚本默认 /var/log/postgres-backup.log 在非 root 容器内不可写，
        // set -e + tee -a 会在第一条 log 就中止，连 pg_dump 都跑不到。
        env.put("LOG_FILE", root.resolve("backup.log").toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString()));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("standalone-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "postgres-backup.sh timed out");
        String text = Files.readString(out);

        assertEquals(2, p.exitValue(),
                "缺 aws 时离站副本缺失必须非 0 退出（exit 2），不得静默成功；实际输出：\n" + text);
        assertFalse(Files.exists(stale), "超期备份未被清理——上传分支中止吞掉了留存清理");
    }

    @Test
    @DisplayName("postgres-backup.sh: pg_restore 校验失败时以非 0 退出（校验形同虚设不可接受）")
    void standaloneScriptFailsWhenVerificationFails() throws Exception {
        Path root = freshDir("standalone-verify");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\necho 'corrupt archive' >&2\nexit 1\n");

        Path script = REPO.resolve("deploy/backup/postgres-backup.sh");
        Map<String, String> env = new HashMap<>(sandboxEnv(bin, backupDir));
        env.put("LOG_FILE", root.resolve("backup.log").toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString()));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("standalone-verify-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "postgres-backup.sh timed out");

        assertFalse(p.exitValue() == 0, "校验失败必须非 0 退出，否则损坏备份会被当作成功并进入留存/上传链路");
    }

    @Test
    @DisplayName("postgres-backup.sh: 离站上传成功时 exit 0")
    void standaloneScriptExitsZeroWhenOffsiteSucceeds() throws Exception {
        Path root = freshDir("standalone-backup-ok");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        writeStub(bin, "pg_dump", "#!/bin/bash\nfor a in \"$@\"; do case \"$a\" in --file=*) : > \"${a#--file=}\";; esac; done\nexit 0\n");
        writeStub(bin, "pg_restore", "#!/bin/bash\nexit 0\n");
        writeStub(bin, "aws", "#!/bin/bash\nexit 0\n");

        Path script = REPO.resolve("deploy/backup/postgres-backup.sh");
        Map<String, String> env = new HashMap<>(sandboxEnv(bin, backupDir));
        env.put("LOG_FILE", root.resolve("backup.log").toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString()));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("standalone-ok-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "postgres-backup.sh timed out");

        assertEquals(0, p.exitValue(),
                "本地校验通过且 S3 上传成功应 exit 0；实际输出：\n" + Files.readString(out));
    }

    /** 造一个 mtime 落在 N 天前的目录。 */
    private static Path agedDir(Path parent, String name, int ageDays) throws IOException {
        Path d = Files.createDirectories(parent.resolve(name));
        Files.setLastModifiedTime(d, FileTime.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)));
        return d;
    }

    // ===== CronJob 清单层面的死配置 =====

    @Test
    @DisplayName("clickhouse-backup.sh: 留存清理不被上传分支牵连，且表达式按名分组正确")
    void clickHouseRetentionRunsAndPredicateGroupsCorrectly() throws Exception {
        Path root = freshDir("ch-backup");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        // clickhouse-backup 桩：脚本以 `$CH_FLAGS create "$BACKUP_NAME"` 调用，flags 数量不定，
        // 故只认恰好等于子命令词的参数（flag 值 localhost/9000/oddsmaker 不会与之相撞）。
        Path trace = root.resolve("trace.log");
        Files.writeString(trace, "", StandardCharsets.UTF_8);
        writeStub(bin, "clickhouse-backup", "#!/bin/bash\n"
                + "sub=\"\"\n"
                + "for a in \"$@\"; do case \"$a\" in create|upload|list) sub=\"$a\"; break ;; esac; done\n"
                + "case \"$sub\" in\n"
                + "  create) echo \"create $1\" >> " + trace + " ;;\n"
                + "  upload) echo \"upload\" >> " + trace + " ;;\n"
                + "  list) echo \"\" ;;\n"
                + "esac\n"
                + "exit 0\n");

        Path stale = agedDir(backupDir, "full_20200101_000000", 120);
        Path fresh = agedDir(backupDir, "full_20990101_000000", 1);
        // 同名干扰项：retention 表达式若不加括号，会被 -o 优先级串味误删。
        Path bystander = Files.createDirectories(backupDir.resolve("unrelated_old"));

        Path script = REPO.resolve("deploy/backup/clickhouse-backup.sh");
        assertTrue(Files.exists(script), "clickhouse-backup.sh missing: " + script);

        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toAbsolutePath().toString());
        env.put("BACKUP_DIR", backupDir.toAbsolutePath().toString());
        env.put("RETENTION_DAYS", "90");
        env.put("CLICKHOUSE_HOST", "localhost");
        env.put("S3_BUCKET", "oddsmaker-backups");

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString(), "full"));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("ch-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "clickhouse-backup.sh timed out");
        String text = Files.readString(out);

        assertEquals(0, p.exitValue(), "上传失败不应中断作业（留存清理在其后）；实际输出：\n" + text);
        assertFalse(Files.exists(stale), "超期备份目录未被清理——留存清理被上传分支掐断");
        assertTrue(Files.exists(fresh), "未超期备份目录被误删");
        assertTrue(Files.exists(bystander), "非备份目录被误删——-name 谓词缺括号导致 -o 串味");
        String traced = Files.readString(trace);
        assertTrue(traced.contains("create"), "备份本体未执行；桩未命中 create 子命令");
        assertTrue(traced.contains("upload"), "配了 S3_BUCKET 应仍尝试上传（离站副本是既有能力，不因本轮加固而移除）");
    }

    @Test
    @DisplayName("clickhouse-backup.sh: 离站上传失败时留存清理照常 + exit 2 非静默失败")
    void clickHouseScriptFailsWhenOffsiteUploadFails() throws Exception {
        Path root = freshDir("ch-backup-uploadfail");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path backupDir = Files.createDirectories(root.resolve("backup"));
        linkSystemTools(bin);
        // clickhouse-backup 桩：create 成功、upload 失败（离站缺口路径）
        writeStub(bin, "clickhouse-backup", "#!/bin/bash\n"
                + "sub=\"\"\n"
                + "for a in \"$@\"; do case \"$a\" in create|upload|list) sub=\"$a\"; break ;; esac; done\n"
                + "case \"$sub\" in\n"
                + "  upload) echo 'upload failed:NoSuchUpload' >&2; exit 1 ;;\n"
                + "esac\n"
                + "exit 0\n");

        Path stale = agedDir(backupDir, "full_20200101_000000", 120);
        Path fresh = agedDir(backupDir, "full_20990101_000000", 1);

        Path script = REPO.resolve("deploy/backup/clickhouse-backup.sh");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toAbsolutePath().toString());
        env.put("BACKUP_DIR", backupDir.toAbsolutePath().toString());
        env.put("RETENTION_DAYS", "90");
        env.put("CLICKHOUSE_HOST", "localhost");
        env.put("S3_BUCKET", "oddsmaker-backups");

        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", script.toAbsolutePath().toString(), "full"));
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("ch-fail-out", ".log");
        pb.redirectOutput(out.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "clickhouse-backup.sh timed out");
        String text = Files.readString(out);

        assertEquals(2, p.exitValue(),
                "上传失败必须 exit 2 非静默失败（exit 0=离站缺口只留在日志里）；实际输出：\n" + text);
        assertFalse(Files.exists(stale), "上传失败不得中止留存清理（备份目录无界增长回归）");
        assertTrue(Files.exists(fresh), "未超期备份目录被误删");
    }

    @Test
    @DisplayName("CronJob 清单: 不留未接线的 BACKUP_TYPE 死环境变量（脚本不读它，易被误读为全量/增量有别）")
    void cronJobHasNoDeadBackupTypeEnv() throws IOException {
        Path yaml = REPO.resolve("deploy/k8s/backup-cronjob.yaml");
        List<String> offenders = new ArrayList<>();
        for (Object doc : new Yaml().loadAll(Files.readString(yaml))) {
            if (!(doc instanceof Map<?, ?> m)) continue;
            if (!"CronJob".equals(m.get("kind"))) continue;
            Map<?, ?> spec = (Map<?, ?>) m.get("spec");
            if (spec == null) continue;
            Object jt = spec.get("jobTemplate");
            if (!(jt instanceof Map<?, ?> job)) continue;
            Object ps = job.get("spec");
            if (!(ps instanceof Map<?, ?> pod)) continue;
            Object tmpl = pod.get("template");
            if (!(tmpl instanceof Map<?, ?> t)) continue;
            Object sp = t.get("spec");
            if (!(sp instanceof Map<?, ?> s)) continue;
            Object containers = s.get("containers");
            if (!(containers instanceof List<?> containerList)) continue;
            for (Object c : containerList) {
                Map<?, ?> container = (Map<?, ?>) c;
                Object envList = container.get("env");
                if (!(envList instanceof List<?> entries)) continue;
                for (Object e : entries) {
                    Map<?, ?> entry = (Map<?, ?>) e;
                    if ("BACKUP_TYPE".equals(entry.get("name"))) {
                        Map<?, ?> meta = (Map<?, ?>) m.get("metadata");
                        offenders.add(String.valueOf(meta == null ? "?" : meta.get("name")));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下 CronJob 仍在注入 BACKUP_TYPE，但 backup.sh 根本不读它（pg_dump 恒为全量逻辑备份）：" + offenders);
    }
}