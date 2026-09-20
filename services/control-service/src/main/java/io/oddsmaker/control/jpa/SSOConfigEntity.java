package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * SSO配置实体
 * 管理SAML/OAuth单点登录配置
 */
@Entity
@Table(name = "sso_configs")
public class SSOConfigEntity {

    /**
     * SSO协议类型
     */
    public enum SSOProtocol {
        SAML2,          // SAML 2.0
        OAUTH2,         // OAuth 2.0
        OIDC,           // OpenID Connect
        CAS             // CAS协议
    }

    /**
     * SSO状态
     */
    public enum SSOStatus {
        DISABLED,       // 已禁用
        ACTIVE,         // 活跃
        TESTING,        // 测试中
        ERROR           // 配置错误
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "name", nullable = false, length = 100)
    public String name;  // SSO配置名称

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "sso_protocol", nullable = false)
    @Enumerated(EnumType.STRING)
    public SSOProtocol ssoProtocol;  // SSO协议

    @Column(name = "sso_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public SSOStatus ssoStatus = SSOStatus.DISABLED;

    @Column(name = "is_default", columnDefinition = "BOOLEAN")
    public Boolean isDefault = false;  // 是否为默认SSO

    // SAML配置
    @Column(name = "saml_idp_entity_id", length = 500)
    public String samlIdpEntityId;  // IdP实体ID

    @Column(name = "saml_idp_sso_url", length = 500)
    public String samlIdpSsoUrl;  // IdP SSO URL

    @Column(name = "saml_idp_slo_url", length = 500)
    public String samlIdpSloUrl;  // IdP SLO URL

    @Column(name = "saml_idp_cert", columnDefinition = "TEXT")
    public String samlIdpCert;  // IdP证书

    @Column(name = "saml_sp_entity_id", length = 500)
    public String samlSpEntityId;  // SP实体ID

    @Column(name = "saml_acs_url", length = 500)
    public String samlAcsUrl;  // Assertion Consumer Service URL

    @Column(name = "saml_slo_url", length = 500)
    public String samlSloUrl;  // Single Logout URL

    @Column(name = "saml_sp_cert", columnDefinition = "TEXT")
    public String samlSpCert;  // SP证书

    @Column(name = "saml_sp_key", columnDefinition = "TEXT")
    public String samlSpKey;  // SP私钥

    @Column(name = "saml_name_id_format", length = 100)
    public String samlNameIdFormat = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";  // NameID格式

    // OAuth/OIDC配置
    @Column(name = "oauth_provider", length = 50)
    public String oauthProvider;  // OAuth提供商（google, github, okta, etc.）

    @Column(name = "oauth_client_id", length = 255)
    public String oauthClientId;  // OAuth客户端ID

    @Column(name = "oauth_client_secret", columnDefinition = "TEXT")
    public String oauthClientSecret;  // OAuth客户端密钥

    @Column(name = "oauth_authorization_url", length = 500)
    public String oauthAuthorizationUrl;  // 授权端点

    @Column(name = "oauth_token_url", length = 500)
    public String oauthTokenUrl;  // Token端点

    @Column(name = "oauth_user_info_url", length = 500)
    public String oauthUserInfoUrl;  // 用户信息端点

    @Column(name = "oauth_scopes", length = 500)
    public String oauthScopes;  // OAuth scopes

    @Column(name = "oauth_callback_url", length = 500)
    public String oauthCallbackUrl;  // 回调URL

    @Column(name = "oauth_response_type", length = 20)
    public String oauthResponseType = "code";  // 响应类型

    @Column(name = "oauth_grant_type", length = 50)
    public String oauthGrantType = "authorization_code";  // 授权类型

    // 通用配置
    @Column(name = "attribute_mapping", columnDefinition = "TEXT")
    public String attributeMapping;  // JSON格式的属性映射

    @Column(name = "role_mapping", columnDefinition = "TEXT")
    public String roleMapping;  // JSON格式的角色映射

    @Column(name = "allowed_domains", columnDefinition = "TEXT")
    public String allowedDomains;  // JSON格式的允许域名列表

    @Column(name = "auto_provision", columnDefinition = "BOOLEAN")
    public Boolean autoProvision = false;  // 自动创建用户

    @Column(name = "auto_provision_role", length = 100)
    public String autoProvisionRole;  // 自动创建用户的默认角色

    @Column(name = "force_authn", columnDefinition = "BOOLEAN")
    public Boolean forceAuthn = false;  // 强制重新认证

    @Column(name = "sign_requests", columnDefinition = "BOOLEAN")
    public Boolean signRequests = true;  // 签名请求

    @Column(name = "encrypt_assertions", columnDefinition = "BOOLEAN")
    public Boolean encryptAssertions = true;  // 加密断言

    @Column(name = "last_tested_at")
    public LocalDateTime lastTestedAt;  // 最后测试时间

    @Column(name = "last_test_result", columnDefinition = "TEXT")
    public String lastTestResult;  // 最后测试结果

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误消息

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return ssoStatus == SSOStatus.ACTIVE;
    }

    public boolean isDisabled() {
        return ssoStatus == SSOStatus.DISABLED;
    }

    public boolean isTesting() {
        return ssoStatus == SSOStatus.TESTING;
    }

    public boolean isSAML() {
        return ssoProtocol == SSOProtocol.SAML2;
    }

    public boolean isOAuth() {
        return ssoProtocol == SSOProtocol.OAUTH2 || ssoProtocol == SSOProtocol.OIDC;
    }

    public boolean isOIDC() {
        return ssoProtocol == SSOProtocol.OIDC;
    }

    public void activate() {
        this.ssoStatus = SSOStatus.ACTIVE;
        this.errorMessage = null;
    }

    public void disable() {
        this.ssoStatus = SSOStatus.DISABLED;
    }

    public boolean isAutoProvisionEnabled() {
        return autoProvision != null && autoProvision;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (ssoStatus == null) {
            ssoStatus = SSOStatus.DISABLED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
