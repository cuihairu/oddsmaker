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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("mTLS keystore：注入 location/password/type + key.password 复用库口令；空 path 不注入")
    void keystoreInjectsForMtls() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SSL", null, null, null,
                new KafkaConsumerAdapter.SslSettings(null, null, null,
                        "/ks/client.p12", "ks-pass", "PKCS12"));
        assertEquals("/ks/client.p12", props.get(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
        assertEquals("ks-pass", props.get(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG));
        // kafka-clients 的私钥口令独立成键：配置面复用库口令（PKCS12 单一口令语义）
        assertEquals("ks-pass", props.get(SslConfigs.SSL_KEY_PASSWORD_CONFIG));
        assertEquals("PKCS12", props.get(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG));
        assertFalse(props.containsKey(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));

        // 空/空白 keystore path：不注入任何 keystore props（单向 TLS 场景）
        Map<String, Object> bare = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(bare, "SSL", null, null, null,
                new KafkaConsumerAdapter.SslSettings("/ts/t.p12", "ts", "PKCS12", null, null, null));
        assertFalse(bare.containsKey(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
        Map<String, Object> blank = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(blank, "SSL", null, null, null,
                new KafkaConsumerAdapter.SslSettings(null, null, null, "  ", "", null));
        assertFalse(blank.containsKey(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
    }

    @Test
    @DisplayName("mTLS 完整组合：truststore + keystore 同时注入（验链 + 出证）")
    void mtlsInjectsTruststoreAndKeystore() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SSL", null, null, null,
                new KafkaConsumerAdapter.SslSettings("/ts/trust.p12", "ts-pass", "PKCS12",
                        "/ks/client.p12", "ks-pass", "PKCS12"));
        assertEquals("/ts/trust.p12", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("/ks/client.p12", props.get(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
    }

    @Test
    @DisplayName("SASL_SSL + keystore：证书链、客户端证书与 SCRAM JAAS 三方叠加")
    void saslSslInjectsKeystoreAndJaas() {
        Map<String, Object> props = new HashMap<>();
        KafkaConsumerAdapter.applySecurityProps(props, "SASL_SSL", "SCRAM-SHA-256",
                "dim_ro", "secret",
                new KafkaConsumerAdapter.SslSettings("/ts/trust.p12", "ts-pass", "PKCS12",
                        "/ks/client.p12", "ks-pass", "PKCS12"));
        assertEquals("SASL_SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("/ts/trust.p12", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("/ks/client.p12", props.get(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
        assertEquals("SCRAM-SHA-256", props.get(SaslConfigs.SASL_MECHANISM));
        assertTrue(String.valueOf(props.get(SaslConfigs.SASL_JAAS_CONFIG))
                .contains("username=\"dim_ro\""));
    }

    @Test
    @DisplayName("truststoreOnly 便捷构造 = keystore 三字段全 null（10 参构造行为不变的根保证）")
    void truststoreOnlyLeavesKeystoreNull() {
        var ssl = KafkaConsumerAdapter.SslSettings.truststoreOnly("/ts/t.p12", "p", "PKCS12");
        assertEquals("/ts/t.p12", ssl.truststorePath());
        assertNull(ssl.keystorePath());
        assertNull(ssl.keystorePassword());
        assertNull(ssl.keystoreType());
    }
}
