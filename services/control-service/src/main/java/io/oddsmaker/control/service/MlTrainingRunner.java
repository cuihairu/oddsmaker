package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 训练进程执行器：跑 ml/（oddsmaker-ml）训练命令行子进程。
 * 独立成组件便于单测（调度器 mock 本类，本类用真实进程自测）。
 */
@Service
public class MlTrainingRunner {

    private static final int OUTPUT_TAIL_CHARS = 2000;

    /** 执行结果：退出码（0 成功；-1 超时被杀）+ 输出尾部（审计与日志用）。 */
    public record Result(int exitCode, String outputTail) {}

    /**
     * 在 workDir 下执行 command，合并 stdout/stderr 捕获输出，超时强杀。
     * 除进程启动 IO 错误外不以异常表达失败——失败统一用非零退出码。
     */
    public Result run(List<String> command, Path workDir, long timeoutSeconds)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程被杀或流关闭，读到哪里算哪里
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(5));
            return new Result(-1, tail(output, " [超时 " + timeoutSeconds + "s 强制终止]"));
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        return new Result(process.exitValue(), tail(output, ""));
    }

    private static String tail(StringBuilder output, String suffix) {
        String s = output.toString();
        if (s.length() > OUTPUT_TAIL_CHARS) {
            s = "…(" + (s.length() - OUTPUT_TAIL_CHARS) + " chars omitted)" + s.substring(s.length() - OUTPUT_TAIL_CHARS);
        }
        return s + suffix;
    }
}
