# Oddsmaker Unified Server Image
# 单镜像双服务：Control Service (8085) + Gateway Service (8080)
# 运行时通过 SERVICE 环境变量选择：control | gateway
# 构建上下文必须是仓库根（根 settings.gradle.kts 无条件 include 全部模块，
# 缺任何一个模块的 build 文件 Gradle 配置阶段即失败；COPY 路径也全部相对仓库根）：
#   docker build -f Dockerfile .
#
# 产物用途：
#   - CI nightly-build.yml 推送 ghcr.io/cuihairu/oddsmaker:nightly
#   - release.yaml 推送 ghcr.io/cuihairu/oddsmaker:<semver> / :latest
#   - docker-compose.quickstart.yml 直接 build 或 pull 同名 tag

# ============================================================
# Stage 1: Build server jars（Gradle 8.10.1，与仓库 wrapper 一致）
# ============================================================
FROM gradle:8.10.1-jdk21 AS build
WORKDIR /workspace

# 1) 先拷全部构建文件作缓存层（settings include 的模块一个不能少）
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
COPY agents/dimension-sync-agent/build.gradle.kts agents/dimension-sync-agent/

# 2) 预热依赖（构建文件不变时命中缓存层）
RUN gradle :services:control-service:dependencies :services:gateway-service:dependencies \
         :jobs:flink:events-enrich-job:dependencies :jobs:flink:sessions-job:dependencies \
         :jobs:flink:retention-job:dependencies :jobs:flink:funnels-job:dependencies \
         :jobs:flink:risk-job:dependencies :jobs:flink:identity-merge-job:dependencies \
         :jobs:flink:dimension-sync-job:dependencies :agents:dimension-sync-agent:dependencies \
         --no-daemon || true

# 3) 拷贝源码并构建 control-service 和 gateway-service
COPY libs ./libs
COPY services/control-service ./services/control-service
COPY services/gateway-service ./services/gateway-service

RUN gradle :services:control-service:bootJar :services:gateway-service:bootJar \
         --no-daemon -x test \
    && rm -f services/control-service/build/libs/*-plain.jar \
    && rm -f services/gateway-service/build/libs/*-plain.jar

# ============================================================
# Stage 2: Runtime image (eclipse-temurin:21-jre-alpine)
# ============================================================
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Oddsmaker Team"
LABEL description="Oddsmaker Unified Server - Control Service (8085) + Gateway Service (8080)"
LABEL org.opencontainers.image.source="https://github.com/cuihairu/oddsmaker"
LABEL org.opencontainers.image.vendor="Oddsmaker"

# 默认 JVM 选项（可被运行时 JAVA_OPTS 覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"

# 服务端口（actuator 与服务同端口，无独立 management 口）
ENV CONTROL_PORT=8085
ENV GATEWAY_PORT=8080

# 安装 wget 用于 healthcheck
RUN apk add --no-cache wget

# 创建非 root 用户
RUN addgroup -S oddsmaker && adduser -S oddsmaker -G oddsmaker

# 创建目录并授权
RUN mkdir -p /app/logs /app/config /app/data /app/db/migration /app/lib && \
    chown -R oddsmaker:oddsmaker /app

# 拷贝构建产物
COPY --from=build /workspace/services/control-service/build/libs/*.jar /app/lib/control-service.jar
COPY --from=build /workspace/services/gateway-service/build/libs/*.jar /app/lib/gateway-service.jar

# 拷贝迁移脚本（Flyway 实际从 classpath:db/migration 读取，此处供运维参考）
COPY services/control-service/src/main/resources/db/migration /app/db/migration

# 拷贝默认配置（可被运行时挂载覆盖）
COPY services/control-service/src/main/resources/application.yaml /app/config/control-application.yaml
COPY services/gateway-service/src/main/resources/application.yaml /app/config/gateway-application.yaml

# Healthcheck 脚本（按 SERVICE 选择端口）
COPY docker/healthcheck.sh /app/healthcheck.sh
RUN chmod +x /app/healthcheck.sh

# Entrypoint 脚本（按 SERVICE 分发）
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 授权
RUN chown -R oddsmaker:oddsmaker /app

# 切换非 root
USER oddsmaker

# 暴露双端口（实际由 SERVICE 决定监听哪一个）
EXPOSE 8085 8080

# Healthcheck（按 HEALTHCHECK_PORT 环境变量动态检查）
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=5 \
    CMD /app/healthcheck.sh

# 数据卷
VOLUME ["/app/logs", "/app/data"]

# 启动
ENTRYPOINT ["/app/entrypoint.sh"]