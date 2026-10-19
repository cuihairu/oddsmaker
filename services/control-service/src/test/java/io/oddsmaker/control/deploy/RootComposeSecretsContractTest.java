package io.oddsmaker.control.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 根 compose（Production 面）control-service 生产凭据接线钉测试。
 *
 * ProductionSecretsValidator（审计 SEC-001）在 require-production-secrets=true 时
 * fail-closed 校验四个凭据环境变量；根 compose 是唯一声明该开关=true 的编排面，
 * 四个变量必须全部接进容器，缺任何一个生产形态永远起不来。2026-10-20 本地活体
 * 验证揪出 ODDSMAKER_AUTH_JWT_SECRET 漏接（demo 面已接、根面缺失，校验器以
 * 「仍为开发默认值」拒绝启动），本钉测试保证根面接线与校验器清单不掉队：
 * 校验器新增点名变量而根面未接时打红。
 */
class RootComposeSecretsContractTest {

    private static final Path REPO = Paths.get("..", "..").toAbsolutePath().normalize();

    /** 与 ProductionSecretsValidator.validate() 的 check 清单同步（validator 未导出常量，此处钉文件面）。 */
    private static final List<String> VALIDATOR_ENVS = List.of(
            "ODDSMAKER_ADMIN_TOKEN",
            "ODDSMAKER_INTERNAL_TOKEN",
            "ODDSMAKER_AUTH_JWT_SECRET",
            "SPRING_DATASOURCE_PASSWORD");

    private static final String ROOT_COMPOSE = "docker-compose.yml";

    @Test
    void rootComposeWiresAllValidatorSecretsIntoControlService() throws IOException {
        String controlBlock = composeServiceBlock(ROOT_COMPOSE, "control-service", "gateway-service");
        for (String env : VALIDATOR_ENVS) {
            assertTrue(controlBlock.contains(env + ":"),
                    "根 compose control-service 未接 " + env
                            + "（ProductionSecretsValidator fail-closed 将拒绝启动）");
        }
        // fail-closed 插值：缺失即 compose 拒绝起动并在报错里点名（与 demo 面同款式）
        assertTrue(controlBlock.contains("${ODDSMAKER_AUTH_JWT_SECRET:?"),
                "ODDSMAKER_AUTH_JWT_SECRET 必须用 :? fail-closed 插值（.env 缺失时 compose 直接拒绝）");
    }

    @Test
    void envExampleListsAuthJwtSecret() throws IOException {
        // demo 面以 :? 强制要求该变量，.env.example 模板缺项会让照模板填的 .env 过不了插值
        String envExample = Files.readString(REPO.resolve(".env.example"), UTF_8);
        assertTrue(envExample.contains("ODDSMAKER_AUTH_JWT_SECRET="),
                ".env.example 缺 ODDSMAKER_AUTH_JWT_SECRET（demo 面插值必需项）");
    }

    @Test
    void demoComposeRequiresAuthJwtSecret() throws IOException {
        String demoBlock = composeServiceBlock(
                "deploy/demo/docker-compose.yml", "control-service", null);
        assertTrue(demoBlock.contains("ODDSMAKER_AUTH_JWT_SECRET:"),
                "demo compose control-service 未接 ODDSMAKER_AUTH_JWT_SECRET");
    }

    /** 取 compose 文件中指定 service 的定义块（到下一个同级 service 或文件尾）。 */
    private static String composeServiceBlock(String relativePath, String service, String nextService)
            throws IOException {
        String text = Files.readString(REPO.resolve(relativePath), UTF_8);
        int start = text.indexOf("\n  " + service + ":");
        assertTrue(start >= 0, relativePath + " 未找到 service: " + service);
        int end = nextService == null ? text.length() : text.indexOf("\n  " + nextService + ":", start);
        assertTrue(end > start, relativePath + " 未找到后邻 service: " + nextService);
        return text.substring(start, end);
    }
}
