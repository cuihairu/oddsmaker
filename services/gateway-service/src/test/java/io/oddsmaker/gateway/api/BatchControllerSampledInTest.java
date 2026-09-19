package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * sampledIn 的 SHA-256 不可用降级分支：与端到端 CoverageTest 分离——
 * mockStatic 是线程局部的，真实 HTTP 调用跑在 Netty 线程上不生效，
 * 必须在测试线程内反射直调私有方法才能覆盖 catch 路径。
 * 依赖构造器注入但 sampledIn 只触碰 MessageDigest，全 null 依赖即可实例化。
 */
@DisplayName("批量入口采样 SHA-256 不可用降级")
class BatchControllerSampledInTest {

    @Test
    @DisplayName("MessageDigest.getInstance 抛异常时采样放行（fail-open）")
    void sampledInFailsOpenWhenSha256Unavailable() {
        BatchController controller = new BatchController(
                null, null, null, null, null, null, null, null, null);
        try (MockedStatic<MessageDigest> md = mockStatic(MessageDigest.class)) {
            md.when(() -> MessageDigest.getInstance(anyString()))
                    .thenThrow(new NoSuchAlgorithmException("test-only"));
            Event event = new Event();
            Boolean sampledIn = ReflectionTestUtils.invokeMethod(
                    controller, "sampledIn", event, 0.5);
            assertTrue(sampledIn);
        }
    }

    @Test
    @DisplayName("toJsonSilently：序列化异常静默返回 null 不抛出")
    void toJsonSilentlySwallowsSerializationFailure() {
        BatchController controller = new BatchController(
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null, null, null, null, null, null);
        // new Object() 无任何可序列化属性，默认 FAIL_ON_EMPTY_BEANS 抛 InvalidDefinitionException → catch → null
        Object result = ReflectionTestUtils.invokeMethod(controller, "toJsonSilently", new Object());
        assertNull(result);
    }
}
