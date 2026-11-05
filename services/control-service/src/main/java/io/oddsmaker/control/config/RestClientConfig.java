package io.oddsmaker.control.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
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

    /**
     * 全局默认模板（connect/read 两档 @Value）；webhook_configs.timeout_seconds 的按配置超时由
     * WebhookRestTemplateProvider 在其上派生。
     *
     * <p>禁止自动跟随重定向：DimensionPullService 等对外 HTTP 面只校验初始 endpoint 的 SSRF 白名单，
     * 若客户端自动跟随 3xx，公网 endpoint 可经 Location 重定向到 127.0.0.1/169.254.169.254 等被禁地址，
     * 绕过新增的 SSRF 校验（见 UrlSecurityValidator）。重定向响应按 3xx 交由调用方处理（非 2xx 即报错）。
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .redirects(HttpRedirects.DONT_FOLLOW)
                .build();
    }
}
