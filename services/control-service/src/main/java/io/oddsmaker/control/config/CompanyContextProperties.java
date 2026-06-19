package io.oddsmaker.control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单公司部署上下文。
 */
@ConfigurationProperties(prefix = "oddsmaker.company")
public class CompanyContextProperties {

    private String name = "Oddsmaker Studio";
    private String deploymentId = "local";
    private String defaultTimezone = "Asia/Shanghai";
    private String dataRegion = "cn";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getDefaultTimezone() {
        return defaultTimezone;
    }

    public void setDefaultTimezone(String defaultTimezone) {
        this.defaultTimezone = defaultTimezone;
    }

    public String getDataRegion() {
        return dataRegion;
    }

    public void setDataRegion(String dataRegion) {
        this.dataRegion = dataRegion;
    }
}
