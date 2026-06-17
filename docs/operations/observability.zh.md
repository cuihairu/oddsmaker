# 可观测性（Metrics/Traces/Logs）

本地监控栈
- OTel Collector：接收 OTLP（:4317/:4318），Prometheus 导出（:9464）
- Prometheus：抓取 OTel Collector 暴露指标（otelcol_*）
- Grafana：已预置 Prom 数据源与 `Pit Overview` 面板（OTLP 接收量/uptime）
- 启动：`cd infra && docker compose up -d otel-collector prometheus grafana`

采集应用指标（建议）
- Spring Boot（网关/控制面）：
  - 方式一：Java Agent（自动）：下载 OTel Java Agent，启动时添加 `-javaagent:/path/opentelemetry-javaagent.jar` 并配置环境：
    ```
    OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
    OTEL_METRICS_EXPORTER=otlp
    OTEL_TRACES_EXPORTER=otlp
    OTEL_LOGS_EXPORTER=none
    OTEL_SERVICE_NAME=pit-gateway
    ```
    示例（本地）：
    ```bash
    # 假设 agent 放在 var/opentelemetry-javaagent.jar
    JAVA_TOOL_OPTIONS="-javaagent:$(pwd)/var/opentelemetry-javaagent.jar" \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=none \
    OTEL_SERVICE_NAME=pit-gateway \
    ./gradlew :services:gateway-service:bootRun
    ```
  - 方式二：Micrometer OTel Registry（代码集成）：引入 `micrometer-registry-otlp` 并配置 MeterRegistry 指向 Collector
- Flink 作业：可启用 OTel Metrics Reporter，将指标上报至 Collector

Grafana 面板
- 已预置的 `Pit Overview` 显示 OTel Collector 接收的 Spans/Metrics/Logs 速率与 uptime
- 已预置 `Pit JVM` 与 `Pit HTTP Server` 两个面板（Micrometer 与 OTel 指标名均有覆盖），在应用开启 OTel 上报后可直接查看
