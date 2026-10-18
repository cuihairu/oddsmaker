package io.oddsmaker.control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产凭据 fail-closed 校验测试：开关开启时空白/开发默认凭据必须中止启动，
 * 报错点名属性与环境变量但不回显值；开关缺省（开发路径）不做任何拦截。
 */
@DisplayName("生产凭据校验")
class ProductionSecretsValidatorTest {

    private static final String FLAG = "oddsmaker.security.require-production-secrets";
    private static final String DEV_JWT = "oddsmaker-dev-jwt-secret-0123456789abcdef0123456789abcdef";

    private static MockEnvironment env(boolean flagOn) {
        MockEnvironment env = new MockEnvironment();
        if (flagOn) {
            env.setProperty(FLAG, "true");
        }
        return env;
    }

    @Test
    @DisplayName("开关缺省：开发默认值原样放行，行为不变")
    void flagOffLeavesDevDefaultsAlone() {
        MockEnvironment env = env(false);
        env.setProperty("oddsmaker.admin.token", "admin");
        env.setProperty("oddsmaker.internal.token", "dev-internal-token");
        env.setProperty("oddsmaker.auth.jwt-secret", DEV_JWT);
        env.setProperty("spring.datasource.password", "oddsmaker");
        assertDoesNotThrow(() -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("开关开启：凭据空白（未设置）即拒绝启动并点名属性")
    void flagOnMissingTokenFailsStartup() {
        MockEnvironment env = env(true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
        assertTrue(ex.getMessage().contains("oddsmaker.admin.token"));
        assertTrue(ex.getMessage().contains("ODDSMAKER_ADMIN_TOKEN"));
    }

    @Test
    @DisplayName("开关开启：admin token 仍为开发默认值即拒绝")
    void flagOnDevAdminTokenFails() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "admin");
        env.setProperty("oddsmaker.internal.token", "prod-internal-x");
        env.setProperty("oddsmaker.auth.jwt-secret", "x".repeat(40));
        env.setProperty("spring.datasource.password", "prod-db-y");
        assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("开关开启：internal token 仍为开发默认值即拒绝")
    void flagOnDevInternalTokenFails() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "prod-admin-x");
        env.setProperty("oddsmaker.internal.token", "dev-internal-token");
        env.setProperty("oddsmaker.auth.jwt-secret", "x".repeat(40));
        env.setProperty("spring.datasource.password", "prod-db-y");
        assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("开关开启：JWT 密钥仍为开发默认值即拒绝")
    void flagOnDevJwtSecretFails() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "prod-admin-x");
        env.setProperty("oddsmaker.internal.token", "prod-internal-x");
        env.setProperty("oddsmaker.auth.jwt-secret", DEV_JWT);
        env.setProperty("spring.datasource.password", "prod-db-y");
        assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("开关开启：数据库口令仍为开发默认值即拒绝")
    void flagOnDevDatasourcePasswordFails() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "prod-admin-x");
        env.setProperty("oddsmaker.internal.token", "prod-internal-x");
        env.setProperty("oddsmaker.auth.jwt-secret", "x".repeat(40));
        env.setProperty("spring.datasource.password", "oddsmaker");
        assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("黑名单大小写不敏感：Dev-Admin-Token 同样拒绝")
    void denyListIsCaseInsensitive() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "DEV-ADMIN-TOKEN");
        env.setProperty("oddsmaker.internal.token", "prod-internal-x");
        env.setProperty("oddsmaker.auth.jwt-secret", "x".repeat(40));
        env.setProperty("spring.datasource.password", "prod-db-y");
        assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("开关开启：四项凭据全部为强随机值才放行")
    void flagOnStrongSecretsPass() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "b7f2c9a1e55d4f80");
        env.setProperty("oddsmaker.internal.token", "3f8a71c2d09b4e66");
        env.setProperty("oddsmaker.auth.jwt-secret", "9c1f4e7b2a6d8035f1c9b4e7a2d60358");
        env.setProperty("spring.datasource.password", "5e8b1d94a7c2f603");
        assertDoesNotThrow(() -> new ProductionSecretsValidator(env).validate());
    }

    @Test
    @DisplayName("报错信息只点名属性，绝不回显凭据值")
    void failureMessageNeverEchoesSecretValues() {
        MockEnvironment env = env(true);
        env.setProperty("oddsmaker.admin.token", "dev-admin-token");
        env.setProperty("oddsmaker.internal.token", "dev-internal-token");
        env.setProperty("oddsmaker.auth.jwt-secret", DEV_JWT);
        env.setProperty("spring.datasource.password", "oddsmaker");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProductionSecretsValidator(env).validate());
        assertTrue(ex.getMessage().contains("oddsmaker.admin.token"));
        // 数据库开发口令 oddsmaker 与属性命名空间同串，无法子串断言，只校验其余三项不回显
        for (String secret : new String[]{"dev-admin-token", "dev-internal-token", DEV_JWT}) {
            assertTrue(!ex.getMessage().contains(secret), "报错不得回显凭据值: " + secret);
        }
    }

    // ===== @PostConstruct 装配档：开关经真实 Spring 生命周期触发 =====

    @org.springframework.boot.test.context.TestConfiguration
    static class ValidatorConfig {
        @Bean
        ProductionSecretsValidator validator(org.springframework.core.env.Environment env) {
            return new ProductionSecretsValidator(env);
        }
    }

    @Test
    @DisplayName("PostConstruct 装配：flag=true + 开发默认值 → 上下文启动失败")
    void postConstructAbortsContextStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValidatorConfig.class)
                .withPropertyValues(
                        "oddsmaker.security.require-production-secrets=true",
                        "oddsmaker.admin.token=admin",
                        "oddsmaker.internal.token=prod-internal-x",
                        "oddsmaker.auth.jwt-secret=" + "x".repeat(40),
                        "spring.datasource.password=prod-db-y")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertTrue(root.getMessage().contains("oddsmaker.admin.token"),
                            "启动失败根因应点名不合规属性: " + root.getMessage());
                });
    }

    @Test
    @DisplayName("PostConstruct 装配：flag=true + 强随机凭据 → 上下文正常启动")
    void postConstructStartsWithStrongSecrets() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValidatorConfig.class)
                .withPropertyValues(
                        "oddsmaker.security.require-production-secrets=true",
                        "oddsmaker.admin.token=b7f2c9a1e55d4f80",
                        "oddsmaker.internal.token=3f8a71c2d09b4e66",
                        "oddsmaker.auth.jwt-secret=9c1f4e7b2a6d8035f1c9b4e7a2d60358",
                        "spring.datasource.password=5e8b1d94a7c2f603")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
