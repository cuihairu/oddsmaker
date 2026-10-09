package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.WebhookConfigEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook 按配置超时的 RestTemplate 供给（消费 webhook_configs.timeout_seconds，
 * 此前列不参与投递、恒用全局默认）。
 *
 * 语义：timeoutSeconds 非空 → read 超时 = 钳制(1..300)s × 1000（写侧已校验，读侧再钳制
 * 防 seed 直写越界），connect 超时恒全局；null → 全局默认模板。
 * RestTemplate 线程安全，按 read 毫秒缓存实例复用（同超时不重复建厂）。
 */
@Component
public class WebhookRestTemplateProvider {

    private final RestTemplate defaultTemplate;
    private final int connectTimeoutMs;
    private final ConcurrentHashMap<Integer, RestTemplate> byReadMs = new ConcurrentHashMap<>();

    public WebhookRestTemplateProvider(RestTemplate defaultTemplate,
            @Value("${oddsmaker.webhook.connect-timeout-ms:3000}") int connectTimeoutMs) {
        this.defaultTemplate = defaultTemplate;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public RestTemplate forConfig(WebhookConfigEntity config) {
        return forTimeout(config == null ? null : config.timeoutSeconds);
    }

    /** package-private：供测试直测解析与缓存语义。 */
    RestTemplate forTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return defaultTemplate;
        }
        int readMs = Math.max(1, Math.min(300, timeoutSeconds)) * 1000;
        return byReadMs.computeIfAbsent(readMs, this::build);
    }

    private RestTemplate build(int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }
}
