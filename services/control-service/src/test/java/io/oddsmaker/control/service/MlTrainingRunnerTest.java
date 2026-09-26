package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 训练进程执行器测试：真实子进程的退出码 / 输出捕获 / 超时强杀。 */
@DisplayName("ml 训练进程执行器测试")
class MlTrainingRunnerTest {

    private final MlTrainingRunner runner = new MlTrainingRunner();

    @Test
    @DisplayName("正常退出：退出码 0 + 输出捕获")
    void normalExit(@TempDir Path dir) throws Exception {
        MlTrainingRunner.Result r = runner.run(
                List.of("/bin/sh", "-c", "echo train-ok"), dir, 30);
        assertEquals(0, r.exitCode());
        assertTrue(r.outputTail().contains("train-ok"));
    }

    @Test
    @DisplayName("非零退出 + stderr 合并捕获")
    void nonZeroExit(@TempDir Path dir) throws Exception {
        MlTrainingRunner.Result r = runner.run(
                List.of("/bin/sh", "-c", "echo boom >&2; exit 3"), dir, 30);
        assertEquals(3, r.exitCode());
        assertTrue(r.outputTail().contains("boom"));
    }

    @Test
    @DisplayName("超时强杀：退出码 -1")
    void timeoutKills(@TempDir Path dir) throws Exception {
        MlTrainingRunner.Result r = runner.run(
                List.of("/bin/sleep", "30"), dir, 1);
        assertEquals(-1, r.exitCode());
        assertTrue(r.outputTail().contains("超时"));
    }

    @Test
    @DisplayName("超长输出只保留尾部 2000 字符并标注省略")
    void longOutputTruncatedToTail(@TempDir Path dir) throws Exception {
        MlTrainingRunner.Result r = runner.run(
                List.of("/bin/sh", "-c", "seq 1 2000"), dir, 30);

        assertEquals(0, r.exitCode());
        assertTrue(r.outputTail().contains("chars omitted"));
        assertTrue(r.outputTail().length() < 2300);
        assertTrue(r.outputTail().endsWith("2000\n"));
    }

    @Test
    @DisplayName("工作目录不存在：抛 IOException")
    void missingWorkDir() {
        try {
            runner.run(List.of("/bin/true"), Path.of("/nonexistent-ml-dir-"
                    + System.nanoTime()), 30);
            throw new AssertionError("期望 IOException");
        } catch (IOException expected) {
            // 预期路径
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("unexpected interrupt", e);
        }
    }
}
