package io.oddsmaker.control.config;

import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 配置/杂项小缺口收口：注解配置类实例化、RestTemplate Bean 构建、
 * 异常构造重载、DTO 容器类实例化。
 */
@DisplayName("配置/杂项小缺口收口")
class ConfigMiscCoverageTest {

    @Test
    @DisplayName("CompanyContextConfig：注解配置类可实例化")
    void companyContextConfig() {
        assertNotNull(new CompanyContextConfig());
    }

    @Test
    @DisplayName("RestClientConfig：restTemplate Bean 构建")
    void restTemplateBean() {
        RestTemplate template = new RestClientConfig().restTemplate(new RestTemplateBuilder());
        assertNotNull(template);
    }

    @Test
    @DisplayName("ResourceNotFoundException：(message, cause) 构造重载")
    void exceptionWithCause() {
        Throwable cause = new IllegalStateException("root");
        ResourceNotFoundException ex = new ResourceNotFoundException("找不到", cause);
        assertEquals("找不到", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Models：DTO 容器类实例化")
    void modelsContainer() {
        assertNotNull(new Models());
        assertNotNull(new Models.ApiKeyResp());
    }
}
