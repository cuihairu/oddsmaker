plugins {
  id("java")
}

dependencies {
  testImplementation("org.mockito:mockito-core:5.15.2")
  implementation(project(":jobs:flink:events-enrich-job"))
  implementation("org.apache.flink:flink-streaming-java:1.19.0")
  implementation("org.apache.flink:flink-clients:1.19.0")
  // connector 的 POM 把 flink-connector-base 标为 provided（集群提供），
  // local executor 模式必须显式引入
  implementation("org.apache.flink:flink-connector-base:1.19.0")
  implementation("org.apache.flink:flink-connector-kafka:3.2.0-1.19")
  implementation("org.apache.flink:flink-connector-jdbc:3.2.0-1.19")
  implementation("org.apache.avro:avro:1.11.3")
  implementation("io.apicurio:apicurio-registry-serdes-avro-serde:2.6.5.Final")
  implementation("com.clickhouse:clickhouse-jdbc:0.6.5")
  // B5 特征分支：risk_features 落 PostgreSQL control 库
  implementation("org.postgresql:postgresql:42.7.3")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  // local executor 运行日志：依赖树里 slf4j-api 被拉到 2.x（1.7 binding 被静默忽略成 NOP 日志），
  // 必须用 slf4j2 的 provider
  implementation("org.apache.logging.log4j:log4j-api:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-core:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaCompile> { options.release.set(21) }
tasks.named<Test>("test") {
  useJUnitPlatform()
  // Kryo（POJO 字段回退序列化）在 JDK21 反射 java.* 内部类需要 open；
  // 集群模式 flink 启动脚本自带同款 --add-opens，本地 test JVM 需显式补齐（JDK21 + Flink 1.19）
  jvmArgs(
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
  )
  // §5.4 真 PG 端到端（RiskFeaturePgE2eTest / RiskScorePgE2eTest，@EnabledIfSystemProperty）：
  // 本地门禁 -Drisk.pg.e2e=true 显式开启；CI 无 PG 自跳过。url/user/pass 仅在显式传入时
  // 透传（缺省保留测试内默认值）；risk.ch.e2e.* 为 B6 risk_events/risk_scores ClickHouse 出口参数。
  for (key in listOf(
      "risk.pg.e2e", "risk.pg.e2e.url", "risk.pg.e2e.user", "risk.pg.e2e.pass",
      "risk.ch.e2e.url", "risk.ch.e2e.user", "risk.ch.e2e.pass"
  )) {
    val v = System.getProperty(key)
    if (v != null && v.isNotEmpty()) systemProperty(key, v)
  }
}

// 可执行 fatJar：java -jar 跑 local executor，或 flink run 提交 1.19 集群
val fatJar = tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = "io.oddsmaker.jobs.risk.RiskJob" }
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({
    configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
  }) {
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/*/module-info.class", "module-info.class")
  }
}
tasks.named("assemble") { dependsOn(fatJar) }
