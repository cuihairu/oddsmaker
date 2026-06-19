package io.oddsmaker.control.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * OpenAPI/Swagger 配置
 * 提供完整的API文档和交互界面
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:Oddsmaker Control Service}")
    private String applicationName;

    @Value("${spring.application.version:1.0.0}")
    private String applicationVersion;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final CompanyContextProperties companyContext;

    public OpenApiConfig(CompanyContextProperties companyContext) {
        this.companyContext = companyContext;
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(apiInfo())
            .servers(Arrays.asList(
                new Server().url("http://localhost:8085" + contextPath).description("Local Control Service")
            ))
            .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
            .components(new Components()
                .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .in(SecurityScheme.In.HEADER)
                    .name("Authorization")
                    .description("JWT authorization header using the Bearer scheme. Example: \"Authorization: Bearer {token}\"")
                )
            );
    }

    private Info apiInfo() {
        return new Info()
            .title("Oddsmaker Gaming Analytics Platform API")
            .description("""
                # Oddsmaker Gaming Analytics Platform Control Service API

                Professional gaming analytics platform for a single company operating multiple games
                and environments, with comprehensive user management and enterprise-grade security.

                ## Features

                ### 🎮 Multi-Game Architecture
                - **Game Management**: Complete game lifecycle management with multi-platform support
                - **Environment Management**: logical dev/staging/prod lifecycle with per-environment policy control
                - **Storage Profiles**: route environments onto shared, prod-isolated, or dedicated data backends
                - **Policy Management**: Per-game and per-environment API keys, PII policies, and risk rules

                ### 👥 User Management & Security
                - **JWT Authentication**: Stateless authentication with refresh tokens
                - **RBAC Permissions**: game / environment scoped permissions for a single-company deployment
                - **Email Verification**: Secure user registration with email verification
                - **Password Security**: BCrypt encryption with account lockout protection

                ### 🔑 API Key Management
                - **Scoped API Keys**: Environment-specific API keys with fine-grained permissions
                - **Usage Analytics**: Real-time API key usage statistics and monitoring
                - **Key Rotation**: Secure API key regeneration and lifecycle management

                ### 📊 Gaming Analytics Ready
                - **GameAnalytics Compatible**: Support for 13 game genres and 6 platforms
                - **Event Schema**: Professional gaming event schema with validation
                - **Real-time Processing**: Kafka + Flink + ClickHouse data pipeline

                ## Authentication

                Most endpoints require authentication using JWT tokens. Include the JWT token in the Authorization header:
                ```
                Authorization: Bearer YOUR_JWT_TOKEN
                ```

                ## Permission Levels

                - **SUPER_ADMIN**: Full deployment access
                - **OPERATOR**: Company-wide operations access
                - **GAME_ADMIN**: Game-level management
                - **ANALYST**: Analytics and reporting access
                - **VIEWER**: Read-only access
                - **DEVELOPER**: Technical configuration access
                - **QA / MARKETING / FINANCE**: domain-specific scoped roles

                ## Rate Limiting

                API endpoints are rate-limited by API key scope, game, environment, and IP policy.

                ## Support

                For technical support and documentation, visit:
                - **Company**: %s
                - **Deployment**: %s
                - **Repository**: configure your own GitHub remote and docs endpoint for your deployment
                """.formatted(companyContext.getName(), companyContext.getDeploymentId())))
            .version(applicationVersion)
            .contact(new Contact()
                .name(companyContext.getName())
                .email("admin@localhost")
                .url("http://localhost:8085"))
            .license(new License()
                .name("MIT License")
                .url("https://opensource.org/licenses/MIT"));
    }
}
