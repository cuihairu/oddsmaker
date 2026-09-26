plugins { id("java-library") }

dependencies {
  api("org.apache.kafka:kafka-clients:3.7.0")
  // Apicurio Registry SerDes（兼容 Confluent 风格）
  api("io.apicurio:apicurio-registry-serdes-avro-serde:2.6.5.Final")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
