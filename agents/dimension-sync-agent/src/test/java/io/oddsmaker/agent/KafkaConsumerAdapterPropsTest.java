package io.oddsmaker.agent;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KafkaConsumerAdapter 鉴权 props 构建（纯离线，不建 consumer 不触网）。
 * SASL/SCRAM 真实 broker 回路见 KafkaSourceSaslBrokerE2ETest；
 * TLS(SSL) 证书链真实 broker 回路见 KafkaSourceSslBrokerE2ETest。
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

    @Test
    @DisplayName("SSL 证书模式：配了 truststore 注入 location/password/type 三键；不配则无 ssl.* props")
    void sslInjectsTruststoreWhenConfigured() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SSL", null, null, null,
                "/etc/oddsmaker/client.p12", "ts-pass", "PKCS12");
        assertEquals("SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("/etc/oddsmaker/client.p12", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("ts-pass", props.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
        assertEquals("PKCS12", props.get(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
        assertFalse(props.containsKey(SaslConfigs.SASL_JAAS_CONFIG));

        // 缺省（自配 null 或空串）不注入任何 ssl.* —— 走 JVM 默认信任库（公有 CA 场景）
        Map<String, Object> bare = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(bare, "SSL", null, null, null, null, null, null);
        assertEquals("SSL", bare.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertFalse(bare.containsKey(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));

        Map<String, Object> blank = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(blank, "SSL", null, null, null, "  ", "", null);
        assertFalse(blank.containsKey(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
    }

    @Test
    @DisplayName("SASL_SSL：truststore 与 SCRAM JAAS 同时注入（证书链 + 账号凭证叠加）")
    void saslSslInjectsTruststoreAndJaas() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SASL_SSL", "SCRAM-SHA-256",
                "dim_ro", "secret", "/ts/trust.p12", "ts-pass", "PKCS12");
        assertEquals("SASL_SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("/ts/trust.p12", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("PKCS12", props.get(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
        assertEquals("SCRAM-SHA-256", props.get(SaslConfigs.SASL_MECHANISM));
        assertTrue(String.valueOf(props.get(SaslConfigs.SASL_JAAS_CONFIG))
                .contains("username=\"dim_ro\""));
    }

    @Test
    @DisplayName("truststore password null 归一为空串（kafka-clients 要求非 null）")
    void nullTruststorePasswordNormalizedToEmpty() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SSL", null, null, null,
                "/ts/trust.jks", null, "JKS");
        assertEquals("", props.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
    }
}
