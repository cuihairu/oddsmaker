package io.oddsmaker.control.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Schema diff 导出工具（只读，不写库）：
 * 以 PostgreSQL 方言把实体期望 DDL 导出到 build/schema-expected.sql，
 * 之后与 pg_dump 的实际 schema 做文本对比（对比脚本仓库外执行）。
 *
 * 用途：定位 ddl-auto=validate 与 Flyway 迁移之间的实体↔表差异全量清单
 * （Hibernate validate 一次只报一个错，逐轮重启迭代太慢）。
 *
 * 仅在设置了 E2E_PG_HOST 时运行（连的必须是跑过全部 Flyway 迁移的库，
 * 如 e2e postgres 宿主端口 15432），否则常规 ./gradlew build 不受影响。
 * 绝不写库：database.action=none + flyway 关闭；scripts 仅生成文件。
 */
@EnabledIfEnvironmentVariable(named = "E2E_PG_HOST", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://${E2E_PG_HOST}:15432/oddsmaker",
    "spring.datasource.username=oddsmaker",
    "spring.datasource.password=oddsmaker",
    "spring.flyway.enabled=false",               // 迁移由 e2e 库自己负责，这里绝不执行
    "spring.jpa.hibernate.ddl-auto=none",        // 绝不动库
    "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-source=metadata",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/schema-expected.sql",
    "spring.kafka.listener.auto-startup=false",   // 测试进程不需要消费
})
class SchemaDiffExportTest {

    @Test
    void exportExpectedDdl() throws Exception {
        Path out = Path.of("build/schema-expected.sql");
        // 等待 EMF 初始化完成（scripts 导出发生在 EMF 启动期），文件存在即成功
        for (int i = 0; i < 100 && !Files.exists(out); i++) {
            Thread.sleep(100);
        }
        if (!Files.exists(out)) {
            throw new IllegalStateException("期望 DDL 未生成：" + out.toAbsolutePath());
        }
        System.out.println("EXPECTED_DDL_WRITTEN=" + out.toAbsolutePath());
    }
}
