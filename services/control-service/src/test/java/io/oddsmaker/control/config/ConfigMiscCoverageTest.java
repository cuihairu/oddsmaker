package io.oddsmaker.control.config;

import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.exception.ResourceNotFoundException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    @DisplayName("RestClientConfig：生产 Bean 禁止自动跟随重定向（DONT_FOLLOW）")
    void restTemplateBeanDoesNotFollowRedirects() throws Exception {
        HttpServer initial = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int initialPort = initial.getAddress().getPort();
        int targetPort = redirectTarget.getAddress().getPort();
        redirectTarget.createContext("/internal", exchange -> {
            byte[] body = "redirected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        initial.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + targetPort + "/internal");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        initial.start();
        redirectTarget.start();
        try {
            RestClientConfig config = new RestClientConfig();
            ReflectionTestUtils.setField(config, "connectTimeoutMs", 3000);
            ReflectionTestUtils.setField(config, "readTimeoutMs", 5000);
            RestTemplate template = config.restTemplate(new RestTemplateBuilder());

            ResponseEntity<String> response = template.getForEntity(
                    "http://127.0.0.1:" + initialPort + "/redirect", String.class);

            assertEquals(HttpStatus.FOUND, response.getStatusCode());
            assertNull(response.getBody());
        } finally {
            initial.stop(0);
            redirectTarget.stop(0);
        }
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
