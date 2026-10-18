package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle 仓库链的 CI 门控钉：aliyun 镜像只允许出现在 CI 例外块里，CI 直连官方源。
 *
 * <p>背景（2026-10-19 CI 三连红实证）：GitHub 托管 runner（Azure US）访问
 * maven.aliyun.com 持续返回 504 Gateway Time-out，同一时刻 CN 本机同 URL 秒回 200
 * ——aliyun 对海外 IP 限流。此前 CI 一直靠 gradle-home 缓存热着掩盖（不触发网络解析），
 * GitHub cache 服务一抖（restore 400 / save 503）被迫冷解析即裸奔：插件标记
 * io.spring.dependency-management 在三源链上解析失败，Nightly 各平台 buildscript
 * classpath 全 504。aliyun 镜像是给本地 CN 网络加速用的，CI runner 在海外没有理由经它。
 *
 * <p>决策（详见 docs/决策记录.md）：GitHub Actions 全体 workflow 自动置 CI=true，
 * 以 System.getenv("CI") 门控——本地（CI 未设）保留 aliyun 优先不变；CI 直连
 * gradlePluginPortal / mavenCentral（全球 CDN，对 runner 快且稳）。Deploy Demo
 * （Aliyun 机器自托管）不跑 gradle，不受影响。
 */
class GradleMirrorCiGateTest {

    /** 仓库根（gradle Test 默认 workingDirectory = services/control-service）。 */
    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    /** CI 例外块的正文：if (System.getenv("CI") == null) { ... }（块内只有镜像行，无嵌套花括号）。 */
    private static final Pattern CI_GATE = Pattern.compile(
            "if \\(System\\.getenv\\(\"CI\"\\) == null\\) \\{([^}]*)\\}");

    private static String file(String name) throws IOException {
        return Files.readString(REPO.resolve(name));
    }

    private static String gatedBlocks(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher m = CI_GATE.matcher(text);
        while (m.find()) {
            sb.append(m.group(1)).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("aliyun 镜像行只允许出现在 CI 例外块内（CI 直连官方源，不经 CN 镜像）")
    void mirrorLinesOnlyInsideCiGate() throws IOException {
        for (String name : List.of("settings.gradle.kts", "build.gradle.kts")) {
            String text = file(name);
            String gated = gatedBlocks(text);
            for (String line : text.lines().toList()) {
                // 只盯真实仓库声明行（maven("…")），放过说明注释里出现的 URL
                if (line.contains("maven.aliyun.com") && line.contains("maven(")) {
                    assertTrue(gated.contains(line.trim()),
                            name + " 的 aliyun 镜像行必须包在 if (System.getenv(\"CI\") == null) { ... } 内"
                                    + "（2026-10-19 实证：GitHub runner 访问 aliyun 持续 504，CI 冷解析即挂）："
                                    + line.trim());
                }
            }
        }
    }

    @Test
    @DisplayName("两条链在 CI 路径上都有官方源兜底（pluginManagement 有 portal+central，构建链有 central）")
    void ciPathKeepsOfficialSources() throws IOException {
        String settings = file("settings.gradle.kts");
        assertTrue(settings.contains("gradlePluginPortal()"),
                "pluginManagement 去掉 aliyun 后必须仍有 gradlePluginPortal()，插件标记才可解析");
        assertTrue(settings.contains("mavenCentral()"),
                "pluginManagement 应保留 mavenCentral() 作插件标记的第二官方源");
        assertTrue(file("build.gradle.kts").contains("mavenCentral()"),
                "构建依赖链去掉 aliyun 后必须仍有 mavenCentral()（confluent 仓只补 Kafka 系构件）");
    }
}
