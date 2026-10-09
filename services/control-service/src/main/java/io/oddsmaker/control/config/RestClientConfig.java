package io.oddsmaker.control.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${oddsmaker.webhook.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${oddsmaker.webhook.read-timeout-ms:5000}")
    private int readTimeoutMs;

    /** 全局默认模板（connect/read 两档 @Value）；webhook_configs.timeout_seconds 的按配置超时由 WebhookRestTemplateProvider 在其上派生。 */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
