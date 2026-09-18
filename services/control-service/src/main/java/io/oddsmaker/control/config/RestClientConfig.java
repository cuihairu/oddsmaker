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

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // TODO: webhook_configs.timeout_seconds 按配置超时需 per-request requestFactory，暂用全局默认
        return builder.setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
