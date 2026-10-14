plugins {
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  id("java")
}

dependencies {
  implementation(project(":libs:common-model"))
  implementation(project(":libs:common-auth"))
  implementation(project(":libs:common-kafka"))
  implementation(project(":libs:common-otel"))

  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-validation")

  // Prometheus 指标暴露（/actuator/prometheus）
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")

  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

  // Boot 4 起默认 JSON 库换 Jackson 3（tools.jackson），Jackson 2 自动配置拆到独立模块；
  // 本服务全链路（BatchController/AvroPublisher/JsonSchemaValidator）依赖 Jackson 2
  // ObjectMapper bean 的 SNAKE_CASE 策略，故显式引 Jackson 2 兼容模块保持原行为
  implementation("org.springframework.boot:spring-boot-jackson2")
  implementation("org.apache.avro:avro:1.11.3")
  // implementation("com.networknt:json-schema-validator:1.0.91")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  // Boot 4 拆分：@AutoConfigureWebTestClient 从 starter-test 移入 spring-boot-webtestclient
  testImplementation("org.springframework.boot:spring-boot-webtestclient")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.release.set(21) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

springBoot { mainClass.set("io.oddsmaker.gateway.Application") }
