package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    /** Jackson 2：内部注入的 ObjectMapper（Avro/schema 校验链路）保持 SNAKE_CASE。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCase() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * Boot 4 起 HTTP 序列化默认换 Jackson 3（tools.jackson），Jackson 2 的 customizer
     * 不再生效于编解码器；API 契约（snake_case 字段）由本 customizer 延续。
     */
    @Bean
    public JsonMapperBuilderCustomizer snakeCaseForHttp() {
        return builder -> builder.propertyNamingStrategy(
                tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    }
}
