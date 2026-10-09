package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.WebhookConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * WebhookRestTemplateProvider 单元测试：timeout_seconds → read 超时解析（钳制 1..300s）、
 * null 回退全局默认模板、按 read 毫秒缓存实例复用。
 *
 * 当前 Spring 版本的 SimpleClientHttpRequestFactory 只有 setter 无 getter，
 * 超时值以反射读私有字段为准；钳制边界另以「实例同一性」交叉验证（同 read 毫秒同实例）。
 */
class WebhookRestTemplateProviderTest {

    private final RestTemplate defaultTemplate = new RestTemplate();
    private final WebhookRestTemplateProvider provider = new WebhookRestTemplateProvider(defaultTemplate, 3000);

    private static int intField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int readTimeoutMs(RestTemplate template) {
        return intField(template.getRequestFactory(), "readTimeout");
    }

    private static WebhookConfigEntity configWith(Integer timeoutSeconds) {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.timeoutSeconds = timeoutSeconds;
        return config;
    }

    @Test
    @DisplayName("timeoutSeconds 为 null（或 config 为 null）→ 回退全局默认模板")
    void nullTimeoutFallsBackToDefault() {
        assertSame(defaultTemplate, provider.forTimeout(null));
        assertSame(defaultTemplate, provider.forConfig(null));
        assertSame(defaultTemplate, provider.forConfig(configWith(null)));
    }

    @Test
    @DisplayName("timeoutSeconds 正常值 → read 超时 = 秒 ×1000，connect 取全局配置")
    void normalTimeoutBuildsDerivedTemplate() {
        RestTemplate template = provider.forTimeout(30);

        assertEquals(30000, readTimeoutMs(template));
        assertEquals(3000, intField(template.getRequestFactory(), "connectTimeout"));
        // forConfig 与 forTimeout 同路
        assertSame(template, provider.forConfig(configWith(30)));
    }

    @Test
    @DisplayName("timeoutSeconds 越界按 1..300 钳制：0/-5→1s，500→300s（实例同一性交叉验证）")
    void outOfRangeClamped() {
        assertEquals(1000, readTimeoutMs(provider.forTimeout(0)));
        assertEquals(1000, readTimeoutMs(provider.forTimeout(-5)));
        assertEquals(300000, readTimeoutMs(provider.forTimeout(500)));
        // 同 read 毫秒 → 同实例：0/-5/1 同桶，300/500 同桶，与 2s/299s 互异
        assertSame(provider.forTimeout(-5), provider.forTimeout(1));
        assertSame(provider.forTimeout(500), provider.forTimeout(300));
        assertNotSame(provider.forTimeout(1), provider.forTimeout(2));
        assertNotSame(provider.forTimeout(300), provider.forTimeout(299));
    }

    @Test
    @DisplayName("同 read 毫秒复用同一实例，不同毫秒各建实例")
    void cachedByReadMs() {
        RestTemplate a = provider.forTimeout(10);
        RestTemplate b = provider.forTimeout(10);
        RestTemplate c = provider.forTimeout(20);

        assertSame(a, b);
        assertNotSame(a, c);
    }
}
