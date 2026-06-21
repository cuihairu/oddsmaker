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

  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("org.apache.avro:avro:1.11.3")
  // implementation("com.networknt:json-schema-validator:1.0.91")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.release.set(21) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

springBoot { mainClass.set("io.oddsmaker.gateway.Application") }
