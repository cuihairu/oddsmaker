# Observability (Metrics/Traces/Logs)

Local monitoring stack
- OTel Collector: receives OTLP (:4317/:4318), exports via Prometheus (:9464)
- Prometheus: scrapes metrics exposed by the OTel Collector (otelcol_*)
- Grafana: preconfigured with a Prometheus data source and the `Oddsmaker Overview` dashboard (OTLP ingestion rate/uptime)
- Start: `cd infra && docker compose up -d otel-collector prometheus grafana`

Collecting application metrics (recommended)
- Spring Boot (gateway/control plane):
  - Option 1: Java Agent (automatic): download the OTel Java Agent, add `-javaagent:/path/opentelemetry-javaagent.jar` at startup and configure the environment:
    ```
    OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
    OTEL_METRICS_EXPORTER=otlp
    OTEL_TRACES_EXPORTER=otlp
    OTEL_LOGS_EXPORTER=none
    OTEL_SERVICE_NAME=oddsmaker-gateway
    ```
    Example (local):
    ```bash
    # 假设 agent 放在 var/opentelemetry-javaagent.jar
    JAVA_TOOL_OPTIONS="-javaagent:$(pwd)/var/opentelemetry-javaagent.jar" \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=none \
    OTEL_SERVICE_NAME=oddsmaker-gateway \
    ./gradlew :services:gateway-service:bootRun
    ```
  - Option 2: Micrometer Prometheus Registry (current implementation): the gateway/control plane expose metrics via Micrometer→Prometheus; the OTLP path requires adding the `micrometer-registry-otlp` dependency (not yet introduced)
- Flink jobs: you can enable the OTel Metrics Reporter to report metrics to the Collector — this requires the reporter/agent dependency and is not currently integrated

Grafana dashboards
- The preconfigured `Oddsmaker Overview` shows the rate of Spans/Metrics/Logs received by the OTel Collector and uptime
- Preconfigured `Oddsmaker JVM` and `Oddsmaker HTTP Server` dashboards (covering both Micrometer and OTel metric names); viewable once the application has OTel reporting enabled
