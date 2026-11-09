package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 配置测试（LAY-013 拆分）：requireCertMaterial 证书材料校验——SASL_SSL 下 keystore 通过/非法 type 拒绝、truststore 与 keystore 空白串视为未配置、SASL_PLAINTEXT 下配置证书材料为漂移拒绝。
 */
class AgentConfigKafkaTlsTest extends AgentConfigTestBase {

    @Test
    @DisplayName("requireCertMaterial：keystore path 非空且 protocol 为 SASL_SSL、type 合法时通过")
    void requireCertMaterialAllowsKeystoreUnderSaslSsl() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_SSL";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "p";
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "PKCS12";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：keystore type 非法（非 JKS/PKCS12）在 SASL_SSL 下被拒绝")
    void requireCertMaterialRejectsBadKeystoreTypeUnderSaslSsl() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_SSL";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "p";
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "PEM";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-keystore-type"), ex.getMessage());
    }

    @Test
    @DisplayName("requireCertMaterial：truststore path 空白串视为未配置（早期返回分支）")
    void requireCertMaterialTreatsBlankTruststoreAsAbsent() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SSL";
        c.kafkaSslTruststorePath = "  ";
        c.kafkaSslTruststoreType = "JKS";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：keystore path 空白串视为未配置（早期返回分支）")
    void requireCertMaterialTreatsBlankKeystoreAsAbsent() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SSL";
        c.kafkaSslKeystorePath = "  ";
        c.kafkaSslKeystoreType = "JKS";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：SASL_PLAINTEXT 下配置 truststore = 配置漂移被拒绝")
    void requireCertMaterialRejectsTruststoreUnderSaslPlaintext() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "p";
        c.kafkaSslTruststorePath = "/ts/client.p12";
        c.kafkaSslTruststoreType = "JKS";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-truststore-path"), ex.getMessage());
    }

    @Test
    @DisplayName("requireCertMaterial：SASL_PLAINTEXT 下配置 keystore = 配置漂移被拒绝")
    void requireCertMaterialRejectsKeystoreUnderSaslPlaintext() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "p";
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "JKS";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-keystore-path"), ex.getMessage());
    }

}
