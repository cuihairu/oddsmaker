package io.oddsmaker.jobs.enrich;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * events 表写入侧的投递语义钉（审计 DATA-001 验收「对无法提供严格一次性保证的链路，
 * 文档明确说明实际语义及残余风险」）。
 *
 * <p>端到端链路 Kafka events_raw → events-enrich-job → ClickHouse events 是 at-least-once：
 * KafkaSource 按 checkpoint 提交 offset，JdbcSink 无事务、无 ClickHouse 侧去重令牌。
 * 去重只在作业 keyed state 作用域内成立，而仓内未配置外部 state backend / HA 管理器，
 * compose local executor 形态下容器重启 checkpoint 不可恢复 → 去重 state 全空 → 重投事件
 * 重复入表。这两条钉保证「存储层不收敛重复」这一事实与其文档声明同步：任何一方变更，
 * 测试都会打红，避免残余风险被悄悄当作已消除。
 */
class ClickhouseEventsDeliverySemanticsTest {

    /** 仓库根（gradle Test 默认 workingDirectory = jobs/flink/events-enrich-job）。 */
    private static final Path REPO = Paths.get("..", "..", "..").toAbsolutePath().normalize();

    @Test
    @DisplayName("CH events 表是纯 MergeTree 且无去重令牌：存储层不收敛重复行")
    void eventsTableHasNoDedupToken() throws IOException {
        String ddl = eventsTableDdl();
        assertTrue(ddl.contains("ENGINE = MergeTree"),
                "events 表应为 MergeTree 家族；当前 DDL：\n" + ddl);
        assertFalse(ddl.contains("ReplacingMergeTree") || ddl.contains("dedup_token")
                        || ddl.contains("dedupToken"),
                "events 表不得带 ReplacingMergeTree / dedup_token：一旦改成分区去重引擎，"
                        + "docs/zh/reference/environment-and-storage.md 的残余窗口声明与"
                        + "EventsEnrichJobTest.dedupScopeIsJobStateOnly 的行为钉都需同步改写");
    }

    @Test
    @DisplayName("投递语义文档与 schema 对账：声明 at-least-once、去重作用域与残余窗口")
    void semanticsDocDeclaresTheResidualWindow() throws IOException {
        String doc = Files.readString(REPO.resolve("docs/zh/reference/environment-and-storage.md"));
        assertTrue(doc.contains("at-least-once"),
                "投递语义须写明链路是 at-least-once（审计 DATA-001 验收：无法保证严格一次性时明确说明）");
        assertTrue(doc.contains("keyed state"),
                "须写明去重只在 Flink keyed state 作用域内成立（state 作用域是残余窗口的边界）");
        assertTrue(doc.contains("MergeTree"),
                "须写明存储层为 MergeTree 且无去重令牌（重复行不会被存储层收敛）");
        assertTrue(doc.contains("残余"),
                "须保留残余风险的显式表述，不得在文档里把重复窗口写成已消除");
    }

    /** events 表定义块（CREATE ... 到该语句分号）。 */
    private static String eventsTableDdl() throws IOException {
        String sql = Files.readString(REPO.resolve("schema/sql/clickhouse/schema.sql"));
        String marker = "CREATE TABLE IF NOT EXISTS events\n";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "schema.sql 应包含 events 表定义");
        int end = sql.indexOf(';', start);
        assertTrue(end > start, "events 表定义应有终止分号");
        return sql.substring(start, end);
    }
}
