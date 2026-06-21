plugins { id("java") }

dependencies {
  implementation(project(":jobs:flink:events-enrich-job"))
  implementation("org.apache.flink:flink-streaming-java:1.19.0")
  implementation("org.apache.flink:flink-clients:1.19.0")
  implementation("org.apache.flink:flink-connector-kafka:3.2.0-1.19")
  implementation("org.apache.flink:flink-connector-jdbc:3.2.0-1.19")
  implementation("org.apache.avro:avro:1.11.3")
  implementation("io.apicurio:apicurio-registry-serdes-avro-serde:2.6.5.Final")
  implementation("com.clickhouse:clickhouse-jdbc:0.6.5")
}

tasks.withType<JavaCompile> { options.release.set(21) }
