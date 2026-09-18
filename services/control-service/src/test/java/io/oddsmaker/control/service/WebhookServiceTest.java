package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebhookService.sendTestWebhook 单元测试
 * 同步直发 / 绕过过滤 / 不进重试队列 / 不污染配置统计 / 归属校验
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WebhookService webhookService;

    private WebhookConfigEntity config(String gameId) {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "wc_test";
        config.name = "slack-alerts";
        config.gameId = gameId;
        config.webhookUrl = "https://hooks.example.com/t1";
        config.httpMethod = "POST";
        return config;
    }

    @Test
    @DisplayName("sendTestWebhook 成功：返回 success + 投递细节，log 落 SENDING→SUCCESS，不写配置统计")
    void sendTestWebhookSuccess() {
        WebhookConfigEntity config = config("g1");
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config));
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("pong"));

        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g1");

        assertEquals("wc_test", result.get("configId"));
        assertEquals("slack-alerts", result.get("name"));
        assertEquals("https://hooks.example.com/t1", result.get("url"));
        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals("pong", result.get("responseBody"));
        assertEquals("SUCCESS", result.get("deliveryStatus"));
        assertNotNull(result.get("responseTimeMs"));
        assertNotNull(result.get("logId"));

        // log 两次落库（SENDING → SUCCESS；captor 只存引用，首落状态已被 markAsSuccess 覆盖，
        // 以最终状态断言为准），最终成功且不进重试队列
        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(logCaptor.capture());
        WebhookLogEntity finalLog = logCaptor.getAllValues().get(1);
        assertEquals(WebhookLogEntity.DeliveryStatus.SUCCESS, finalLog.deliveryStatus);
        assertNull(finalLog.nextRetryAt);
        assertEquals("webhook_test", finalLog.eventType);
        assertTrue(finalLog.requestBody.contains("webhook_test"));

        // 不污染配置统计（recordSuccess/recordFailure 不触发 → config 不落库）
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook HTTP 失败：返回 failed + 错误信息，log 置 FAILED 不排重试")
    void sendTestWebhookHttpFailure() {
        WebhookConfigEntity config = config("g1");
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config));
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g1");

        assertEquals("failed", result.get("status"));
        assertEquals("Connection refused", result.get("error"));
        assertEquals("ResourceAccessException", result.get("errorType"));
        assertEquals("FAILED", result.get("deliveryStatus"));

        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(logCaptor.capture());
        WebhookLogEntity finalLog = logCaptor.getAllValues().get(1);
        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, finalLog.deliveryStatus);
        assertNull(finalLog.nextRetryAt);
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook configId 不存在：抛 IllegalArgumentException")
    void sendTestWebhookConfigNotFound() {
        when(webhookConfigRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> webhookService.sendTestWebhook("missing", "g1"));
        verify(webhookLogRepo, never()).save(any(WebhookLogEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook gameId 与配置归属不符：抛 IllegalArgumentException")
    void sendTestWebhookGameMismatch() {
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config("g1")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> webhookService.sendTestWebhook("wc_test", "g2"));
        assertTrue(ex.getMessage().contains("does not belong to game"));
        verify(webhookLogRepo, never()).save(any(WebhookLogEntity.class));
    }
}
