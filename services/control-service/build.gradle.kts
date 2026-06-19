plugins {
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  id("java")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-mail")

  // JWT authentication
  implementation("io.jsonwebtoken:jjwt-api:0.12.6")
  implementation("io.jsonwebtoken:jjwt-impl:0.12.6")
  implementation("io.jsonwebtoken:jjwt-jackson:0.12.6")

  // Database migrations
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")

  // PostgreSQL driver
  runtimeOnly("org.postgresql:postgresql")

  // JSON Schema validation for experiment config - removed for now
  // implementation("com.networknt:json-schema-validator:1.0.91")

  // Swagger/OpenAPI documentation
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

  // Password encoding
  implementation("org.springframework.security:spring-security-crypto")

  // Development database (H2) - only for development
  runtimeOnly("com.h2database:h2")

  // Testing
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
}

springBoot { mainClass.set("io.oddsmaker.control.Application") }
