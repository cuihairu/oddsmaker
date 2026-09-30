plugins {
  id("java")
  id("application")
}

// 维度同步 Agent（oddsmaker-agent）：面向游戏方内网独立部署的维度数据同步器。
// 设计上刻意不依赖本仓库任何模块（无 common-model / 无 Flink / 无 Spring），
// 仅 Jackson + JDK HttpClient + JDBC 驱动（runtime），可整体目录拆出为独立仓库。

dependencies {
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  // kafka 源：仅 KafkaConsumerAdapter 触达（真实 broker 路径）；离线逻辑由假端口单测覆盖
  implementation("org.apache.kafka:kafka-clients:3.7.1")
  // 驱动仅运行期按 jdbc url 加载；单测不触达真实 DB，不上测classpath
  runtimeOnly("com.mysql:mysql-connector-j:8.4.0")
  runtimeOnly("org.postgresql:postgresql:42.7.4")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  // 适配器 assign/poll 离线单测需 mock kafka-clients KafkaConsumer；
  // 版本与 control-service 解析结果对齐（spring-boot BOM 管理的 5.11.0）
  testImplementation("org.mockito:mockito-core:5.11.0")
}

application {
  mainClass.set("io.oddsmaker.agent.AgentMain")
}

tasks.withType<JavaCompile> { options.release.set(21) }
