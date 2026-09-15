# Multi-stage build for Oddsmaker Gateway Service
# 构建上下文必须是仓库根（根 settings.gradle.kts 无条件 include 全部模块，
# 缺任何一个模块的 build 文件 Gradle 配置阶段即失败；COPY 路径也全部相对仓库根）：
#   docker build -f services/gateway-service/Dockerfile .

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
RUN gradle :services:gateway-service:dependencies --no-daemon || true

# gateway 依赖 libs 四个公共模块（project 依赖需其源码参与编译）
COPY libs ./libs
COPY services/gateway-service ./services/gateway-service

RUN gradle :services:gateway-service:bootJar --no-daemon -x test \
    && rm -f services/gateway-service/build/libs/*-plain.jar

# Stage 2: runtime（healthcheck 由 compose 层定义，与 e2e 编排一致）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/gateway-service/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
