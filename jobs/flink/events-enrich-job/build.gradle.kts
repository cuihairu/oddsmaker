plugins {
  id("java")
}

dependencies {
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
  implementation("com.maxmind.geoip2:geoip2:4.2.0")
  implementation("nl.basjes.parse.useragent:yauaa:7.24.0")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  // local executor 运行日志：yauaa 直依赖 log4j2 API；slf4j(flink) 桥接到 log4j2
  implementation("org.apache.logging.log4j:log4j-api:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-core:2.23.1")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaCompile> { options.release.set(21) }
tasks.named<Test>("test") { useJUnitPlatform() }

// 可执行 fatJar：既可 java -jar 跑 local executor，也可 flink run 提交到 1.19 集群
// （flink 核心依赖也在包内——local 模式需要；提交集群时集群版本一致即可覆盖）
val fatJar = tasks.register<Jar>("fatJar") {
  archiveClassifier.set("all")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = "io.oddsmaker.jobs.enrich.EventsEnrichJob" }
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
