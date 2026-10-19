package io.oddsmaker.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件健康指示器门控钉测试（可选邮件通道不拖垮聚合健康）。
 *
 * 背景（2026-10-20 本地活体验证揪出）：根/quickstart compose 的可选邮件通道用
 * `${SPRING_MAIL_HOST:-}` 传空串占位，容器内 MailSender 仍被装配（Jakarta Mail 把
 * 空 host 回落 localhost:587），control 声明了 spring-boot-starter-mail，
 * MailHealthIndicator 随之注册并对聚合健康投票 DOWN——不配置 SMTP 的部署（默认
 * 形态）容器健康检查直接打挂（容器日志每 15s 一条 "Mail health check failed:
 * localhost:587 Connection refused"，control 永远 unhealthy，gateway 因依赖无法起）。
 *
 * 邮件按设计是可选通道（未配置时投递自动跳过、告警链路不受影响，见
 * AlertEmailService），其连通性不应拖垮聚合健康：application.yaml 以
 * management.health.mail.enabled=false 关闭该指示器。
 *
 * 注：H2 测试 context 下 spring-boot-mail 自动装配与真实容器的触发路径存在差异
 * （测试里 sender/indicator 均不出现，无法直接做「存在→摘除」红绿），故本测试钉
 * 门控属性本身（关闭生效、指示器不注册），空串路径的行为证据由容器活体验证提供
 * （2026-10-20，审计 OBS-001 同轮）。
 */
@DisplayName("邮件健康指示器门控（可选邮件通道不拖垮聚合健康）")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:mailhealth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-smoke",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.kafka.listener.auto-startup=false",
        "management.health.kafka.enabled=false",
        // 邮件 host 视为已配置：钉「已配置邮件 + 门控开启」时指示器也不许注册
        "spring.mail.host=smtp-unreachable.local",
})
class MailHealthGatingBootTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment env;

    @Test
    @DisplayName("management.health.mail.enabled 必须显式关闭（默认 false 档不许回摆）")
    void mailHealthGatingEnabledFalse() {
        assertThat(env.getProperty("management.health.mail.enabled", Boolean.class))
                .as("邮件是可选通道（未配置/未连通不阻告警链路），连通性不应投票聚合健康；"
                        + "compose 可选空串占位会让空 host 回落 localhost:587，指示器 DOWN 打挂容器探活")
                .isFalse();
    }

    @Test
    @DisplayName("邮件 host 已配置且门控开启时 mailHealthIndicator 不注册")
    void mailHealthIndicatorNotRegisteredWhenGated() {
        assertThat(context.containsBean("mailHealthIndicator"))
                .as("management.health.mail.enabled=false 应阻止 mailHealthIndicator 注册")
                .isFalse();
    }
}
