package io.oddsmaker.control.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CompanyContextProperties.class)
public class CompanyContextConfig {
}
