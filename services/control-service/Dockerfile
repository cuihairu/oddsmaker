# Multi-stage build for Oddsmaker Control Service
# 构建上下文必须是仓库根（根 settings.gradle.kts 无条件 include 全部模块，
# 缺任何一个模块的 build 文件 Gradle 配置阶段即失败；COPY 路径也全部相对仓库根）：
#   docker build -f services/control-service/Dockerfile .

# Stage 1: Build the application
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
RUN gradle :services:control-service:dependencies --no-daemon || true

# control 依赖 libs 四个公共模块（project 依赖需其源码参与编译）
COPY libs ./libs
COPY services/control-service ./services/control-service

RUN gradle :services:control-service:bootJar --no-daemon -x test \
    && rm -f services/control-service/build/libs/*-plain.jar

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Oddsmaker Team"
LABEL description="Oddsmaker Control Service - Gaming Analytics Platform"

# 端口与 application.yaml 唯一真源对齐：服务 8085，actuator 同端口（无独立 management 口）
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"
ENV SERVER_PORT="8085"

# Install wget for health check
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -S oddsmaker && adduser -S oddsmaker -G oddsmaker

# Create directories
RUN mkdir -p /app/logs /app/config /app/data /app/db/migration && \
    chown -R oddsmaker:oddsmaker /app

# Copy the built jar
COPY --from=build /workspace/services/control-service/build/libs/*.jar /app/app.jar

# Copy migration files（Flyway 实际从 classpath:db/migration 读取，此处供运维参考）
COPY services/control-service/src/main/resources/db/migration /app/db/migration

# Copy default configuration（可被运行时挂载覆盖）
COPY services/control-service/src/main/resources/application.yaml /app/config/application.yaml

# Set ownership
RUN chown -R oddsmaker:oddsmaker /app

# Switch to non-root user
USER oddsmaker

# Expose ports
EXPOSE ${SERVER_PORT}

# Health check using Spring Boot Actuator（actuator 与服务同端口 8085，无 8086）
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=5 \
    CMD wget --no-verbose --tries=1 -qO- http://localhost:${SERVER_PORT}/actuator/health | grep -q '"status":"UP"' || exit 1

# Volume for logs and data
VOLUME ["/app/logs", "/app/data"]

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar --spring.config.additional-location=file:/app/config/"]
