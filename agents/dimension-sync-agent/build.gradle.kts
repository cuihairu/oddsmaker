plugins {
  id("java")
  id("application")
}

// 维度同步 Agent（oddsmaker-agent）：面向游戏方内网独立部署的维度数据同步器。
// 设计上刻意不依赖本仓库任何模块（无 common-model / 无 Flink / 无 Spring），
// 仅 Jackson + JDK HttpClient + JDBC 驱动（runtime），可整体目录拆出为独立仓库。

dependencies {
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  // 驱动仅运行期按 jdbc url 加载；单测不触达真实 DB，不上测classpath
  runtimeOnly("com.mysql:mysql-connector-j:8.4.0")
  runtimeOnly("org.postgresql:postgresql:42.7.4")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
  mainClass.set("io.oddsmaker.agent.AgentMain")
}

tasks.withType<JavaCompile> { options.release.set(21) }
