package io.oddsmaker.control.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SDK 读取的公开实验配置。
 */
public class ExperimentConfigDTO {
    public String id;
    public String salt;
    public JsonNode config;
}
