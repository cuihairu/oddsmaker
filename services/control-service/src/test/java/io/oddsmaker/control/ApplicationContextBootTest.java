package io.oddsmaker.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量 context 启动烟测：守「缺自动配置模块」这类只在真启动时才炸的装配缺陷。
 *
 * 背景：Boot 4 把自动配置从单体 spring-boot-autoconfigure 拆成按技术分模块，
 * 而本服务是直接声明客户端库（spring-kafka / flyway-core）而非对应 starter，
 * 少声明一个模块的后果单元/切片测试完全看不见（2026-10-14 Deploy Demo 实测）：
 *   1. 缺 spring-boot-kafka   → 无 kafkaListenerContainerFactory，三个 @KafkaListener
 *      组件让整个 context 起不来（"No bean named 'kafkaListenerContainerFactory'"）。
 *   2. 缺 spring-boot-flyway  → Flyway 静默不执行，迁移永不落库；老库因 schema 已在
 *      而不报错，新库/新迁移直接 validate 失败（本仓真机复现：missing table [ad_analysis]）。
 *
 * 依赖面刻意降到最小：H2 内存库 + 关 ddl-auto 校验 + 关 Kafka 消费启动；
 * Flyway 只跑 migration-smoke 下的探针迁移（真实迁移集由 E2E_PG 档在真 PG 上验证）。
 */
@DisplayName("控制面全量 context 启动烟测")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ctxboot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-smoke",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.kafka.listener.auto-startup=false",
        "management.health.kafka.enabled=false",
})
class ApplicationContextBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("全量 context 可装配，且 Kafka 监听容器工厂在位")
    void contextLoadsWithKafkaListenerFactory() {
        assertThat(context.containsBean("kafkaListenerContainerFactory"))
                .as("kafkaListenerContainerFactory 缺失 = 漏声明 spring-boot-kafka 模块（Boot 4 拆包）")
                .isTrue();
        assertThat(context.containsBean("riskEventConsumer")).isTrue();
    }

    @Test
    @DisplayName("Flyway 自动配置生效，迁移真的执行")
    void flywayMigrationsRun() {
        assertThat(context.containsBean("flyway")).as("flyway 缺失 = 漏声明 spring-boot-flyway 模块").isTrue();
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM smoke_probe", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
