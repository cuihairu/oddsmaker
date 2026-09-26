package io.oddsmaker.agent;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KafkaConsumerAdapter 鉴权 props 构建（纯离线，不建 consumer 不触网）。
 * SASL/SCRAM 真实 broker 回路见 KafkaSourceSaslBrokerE2ETest。
 */
class KafkaConsumerAdapterPropsTest {

    @Test
    @DisplayName("PLAINTEXT / null / SSL：只置 security.protocol，不注入 SASL props")
    void plaintextAndSslDoNotInjectSasl() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, null, null, null, null);
        assertEquals("PLAINTEXT", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertFalse(props.containsKey(SaslConfigs.SASL_MECHANISM));
        assertFalse(props.containsKey(SaslConfigs.SASL_JAAS_CONFIG));

        Map<String, Object> blank = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(blank, "  ", null, null, null);
        assertEquals("PLAINTEXT", blank.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));

        Map<String, Object> ssl = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(ssl, "SSL", null, null, null);
        assertEquals("SSL", ssl.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertFalse(ssl.containsKey(SaslConfigs.SASL_JAAS_CONFIG));
    }

    @Test
    @DisplayName("SASL_PLAINTEXT + SCRAM：注入机制与 ScramLoginModule JAAS 行")
    void saslScramInjectsJaasLine() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", "b:9092");
        KafkaConsumerAdapter.applySecurityProps(props, "SASL_PLAINTEXT", "SCRAM-SHA-256",
                "dim_ro", "secret");
        assertEquals("SASL_PLAINTEXT", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SCRAM-SHA-256", props.get(SaslConfigs.SASL_MECHANISM));
        String jaas = String.valueOf(props.get(SaslConfigs.SASL_JAAS_CONFIG));
        assertTrue(jaas.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required "),
                "JAAS 行应为 ScramLoginModule: " + jaas);
        assertTrue(jaas.contains("username=\"dim_ro\""));
        assertTrue(jaas.contains("password=\"secret\""));
        assertTrue(jaas.endsWith(";"), "JAAS 语句必须以分号结尾");
        // 既有 props 不被覆盖
        assertEquals("b:9092", props.get("bootstrap.servers"));
    }

    @Test
    @DisplayName("JAAS 转义：凭证中的引号与反斜杠按 JAAS 语法转义（防注入）")
    void credentialsEscapedForJaas() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SASL_SSL", "SCRAM-SHA-512",
                "u\"x", "p\\w\"");
        String jaas = String.valueOf(props.get(SaslConfigs.SASL_JAAS_CONFIG));
        assertTrue(jaas.contains("username=\"u\\\"x\""));
        assertTrue(jaas.contains("password=\"p\\\\w\\\"\""));
        assertFalse(jaas.contains("u\"x\"") && !jaas.contains("\\\""),
                "未转义的引号不得出现在 JAAS 行里");
    }
}
