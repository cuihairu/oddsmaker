pluginManagement {
  repositories {
    // aliyun 镜像只服务本地 CN 网络：2026-10-19 实证 GitHub 托管 runner（Azure US）访问
    // maven.aliyun.com 持续 504，插件标记冷解析即挂（CN 本机同 URL 秒回 200）。
    // GitHub Actions 自动置 CI=true，故 CI 直连官方源；GradleMirrorCiGateTest 钉此门控。
    if (System.getenv("CI") == null) {
      maven("https://maven.aliyun.com/repository/public")
      maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "oddsmaker"

include(
  "libs:common-model",
  "libs:common-auth",
  "libs:common-kafka",
  "libs:common-otel",
  "sdks:server",
  "services:gateway-service",
  "services:control-service",
  "jobs:flink:events-enrich-job",
  "jobs:flink:sessions-job"
  ,"jobs:flink:retention-job"
  ,"jobs:flink:funnels-job"
  ,"jobs:flink:risk-job"
  ,"jobs:flink:identity-merge-job"
  ,"jobs:flink:dimension-sync-job"
  ,"agents:dimension-sync-agent"
)
