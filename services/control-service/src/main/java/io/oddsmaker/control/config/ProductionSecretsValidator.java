package io.oddsmaker.control.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产凭据 fail-closed 校验（外部审计 SEC-001 整改）。
 *
 * 当 oddsmaker.security.require-production-secrets=true 时，启动即校验
 * 管理 Token、服务间 Token、JWT 密钥与数据库口令：空白或仍为已知开发
 * 默认值（quickstart/.env.example 里的那套）直接抛异常中止上下文启动。
 * 报错只点名属性路径与对应环境变量，绝不回显凭据值。
 *
 * 开关缺省为 false：本地开发路径（默认凭据）行为完全不变。
 */
@Component
public class ProductionSecretsValidator {

    /** 已知不安全默认值（小写比对），与 docker-compose.quickstart.yml / .env.example 保持同步。 */
    static final Set<String> DEV_DEFAULTS = Set.of(
            "admin",
            "dev-admin-token",
            "dev-internal-token",
            "dev-secret",
            "oddsmaker",
            "oddsmaker-dev-jwt-secret-0123456789abcdef0123456789abcdef");

    private final Environment env;

    public ProductionSecretsValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validate() {
        if (!env.getProperty("oddsmaker.security.require-production-secrets", Boolean.class, false)) {
            return;
        }
        List<String> problems = new ArrayList<>();
        check(problems, "oddsmaker.admin.token", "ODDSMAKER_ADMIN_TOKEN");
        check(problems, "oddsmaker.internal.token", "ODDSMAKER_INTERNAL_TOKEN");
        check(problems, "oddsmaker.auth.jwt-secret", "ODDSMAKER_AUTH_JWT_SECRET");
        check(problems, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "生产凭据校验失败（oddsmaker.security.require-production-secrets=true），拒绝启动: "
                            + String.join("; ", problems));
        }
    }

    private void check(List<String> problems, String property, String envVar) {
        String value = env.getProperty(property);
        if (value == null || value.isBlank()) {
            problems.add(property + " 为空/未设置（环境变量 " + envVar + "）");
            return;
        }
        if (DEV_DEFAULTS.contains(value.toLowerCase(Locale.ROOT))) {
            problems.add(property + " 仍为开发默认值，必须更换（环境变量 " + envVar + "）");
        }
    }
}
