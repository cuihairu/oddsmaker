package io.oddsmaker.control.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * A/B 实验控制面 DTO。
 */
public class ExperimentDTO {

    public String id;

    @NotBlank(message = "游戏ID不能为空")
    public String gameId;

    public String environmentId;

    /**
     * 环境名称兼容字段，例如 dev/staging/prod。
     */
    public String environment;

    @NotBlank(message = "实验名称不能为空")
    @Size(max = 200, message = "实验名称不能超过200个字符")
    public String name;

    /**
     * draft, running, paused
     */
    public String status;

    public String salt;
    public JsonNode config;
    public Instant createdAt;
    public Instant updatedAt;
}
