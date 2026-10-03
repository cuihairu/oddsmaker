# Multi-stage build for Oddsmaker (Control + Gateway in single image)
# 构建上下文必须是仓库根（根 settings.gradle.kts 无条件 include 全部模块，
# 缺任何一个模块的 build 文件 Gradle 配置阶段即失败；COPY 路径也全部相对仓库根）：
#   docker build -t ghcr.io/cuihairu/oddsmaker:local .

# ============================================================
# Stage 1: Build both services (Gradle with full monorepo context)
# ============================================================
FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace

# 先拷全部构建文件作缓存层（settings include 的模块一个不能少）
COPY settings.gradle.kts build.gradle.kts ./
COPY libs/common-model/build.gradle.kts libs/common-model/
COPY libs/common-auth/build.gradle.kts libs/common-auth/
COPY libs/common-kafka/build.gradle.kts libs/common-kafka/
COPY libs/common-otel/build.gradle.kts libs/common-otel/
COPY services/gateway-service/build.gradle.kts services/gateway-service/
COPY services/control-service/build.gradle.kts services/control-service/
COPY jobs/flink/events-enrich-job/build.gradle.kts jobs/flink/events-enrich-job/
COPY jobs/flink/sessions-job/build.gradle.kts jobs/flink/sessions-job/
COPY jobs/flink/retention-job/build.gradle.kts jobs/flink/retention-job/
COPY jobs/flink/funnels-job/build.gradle.kts jobs/flink/funnels-job/
COPY jobs/flink/risk-job/build.gradle.kts jobs/flink/risk-job/
COPY jobs/flink/identity-merge-job/build.gradle.kts jobs/flink/identity-merge-job/
COPY jobs/flink/dimension-sync-job/build.gradle.kts jobs/flink/dimension-sync-job/

# 预热依赖（构建文件不变时命中缓存层）
RUN gradle :services:control-service:dependencies :services:gateway-service:dependencies --no-daemon || true

# 两个服务都依赖 libs 四个公共模块（project 依赖需其源码参与编译）
COPY libs ./libs
COPY services/control-service ./services/control-service
COPY services/gateway-service ./services/gateway-service

# 并行构建两个 bootJar，去除 plain jar
RUN gradle :services:control-service:bootJar :services:gateway-service:bootJar --no-daemon -x test \
    && rm -f services/control-service/build/libs/*-plain.jar \
    && rm -f services/gateway-service/build/libs/*-plain.jar

# ============================================================
# Stage 2: Runtime image (single image, SERVICE selects entrypoint)
# ============================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Oddsmaker Team"
LABEL description="Oddsmaker Control & Gateway Services - Gaming Analytics Platform"
LABEL org.opencontainers.image.source="https://github.com/cuihairu/oddsmaker"
LABEL org.opencontainers.image.description="Oddsmaker single-image dual-service (control|gateway)"
LABEL org.opencontainers.image.licenses="MIT"

# 端口：control 8085（actuator 同端口），gateway 8080
# 运行时由 SERVICE 环境变量决定启动哪个服务与监听哪个端口
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"
ENV SERVER_PORT_CONTROL="8085"
ENV SERVER_PORT_GATEWAY="8080"

# Install wget for health check
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -S oddsmaker && adduser -S oddsmaker -G oddsmaker

# Create directories
RUN mkdir -p /app/logs /app/config /app/data /app/db/migration && \
    chown -R oddsmaker:oddsmaker /app

# Copy both built jars
COPY --from=build /workspace/services/control-service/build/libs/*.jar /app/control-service.jar
COPY --from=build /workspace/services/gateway-service/build/libs/*.jar /app/gateway-service.jar

# Copy migration files (Flyway 实际从 classpath:db/migration 读取，此处供运维参考)
COPY services/control-service/src/main/resources/db/migration /app/db/migration

# Copy default configurations (可被运行时挂载覆盖)
COPY services/control-service/src/main/resources/application.yaml /app/config/control-application.yaml
COPY services/gateway-service/src/main/resources/application.yaml /app/config/gateway-application.yaml

# Copy healthcheck script
COPY docker/healthcheck.sh /app/healthcheck.sh
RUN chmod +x /app/healthcheck.sh

# Set ownership
RUN chown -R oddsmaker:oddsmaker /app

# Switch to non-root user
USER oddsmaker

# Expose both ports (实际只监听 SERVICE 对应的一个)
EXPOSE 8080 8085

# Health check uses the script which selects endpoint based on SERVICE
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=5 \
    CMD /app/healthcheck.sh

# Volume for logs and data
VOLUME ["/app/logs", "/app/data"]

# Entry point: select service by SERVICE env (control | gateway)
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/${SERVICE:-control}-service.jar --spring.config.additional-location=file:/app/config/"]