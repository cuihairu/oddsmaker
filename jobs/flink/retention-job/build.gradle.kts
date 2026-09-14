plugins {
  id("java")
}

dependencies {
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
  // local executor 运行日志：依赖树里 slf4j-api 被拉到 2.x（1.7 binding 被静默忽略成 NOP 日志），
  // 必须用 slf4j2 的 provider
  implementation("org.apache.logging.log4j:log4j-api:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-core:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaCompile> { options.release.set(21) }
tasks.named<Test>("test") { useJUnitPlatform() }

// 可执行 fatJar：java -jar 跑 local executor，或 flink run 提交 1.19 集群
val fatJar = tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = "io.oddsmaker.jobs.retention.RetentionJob" }
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
