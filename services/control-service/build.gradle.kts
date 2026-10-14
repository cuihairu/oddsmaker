plugins {
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  id("java")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-actuator")

  // RestTemplateBuilder 自 Boot 4 起从 spring-boot-webmvc 拆到 spring-boot-restclient，
  // 包名同步由 org.springframework.boot.web.client 迁到 org.springframework.boot.restclient
  implementation("org.springframework.boot:spring-boot-restclient")

  // Boot 4 起默认 JSON 库换 Jackson 3（tools.jackson），Jackson 2 自动配置拆到独立模块；
  // 控制面 40+ 处注入 com.fasterxml.jackson.databind.ObjectMapper，故显式引兼容模块保持原行为
  implementation("org.springframework.boot:spring-boot-jackson2")

  // RestTemplate 的 PATCH 支持（Flink cancel 是 PATCH /jobs/{id}?mode=cancel）：
  // classpath 有 httpclient5 时 RestTemplateBuilder 自动切换 HttpComponents requestFactory
  implementation("org.apache.httpcomponents.client5:httpclient5")

  // Prometheus 指标暴露（application.yaml 已声明 prometheus 端点，缺此依赖端点 404）
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")

  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

  // Kafka
  implementation("org.springframework.kafka:spring-kafka")

  // ClickHouse（风控处置归档 risk_actions + 风控大屏指标查询）
  implementation("com.clickhouse:clickhouse-jdbc:0.6.5")

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

  // Swagger/OpenAPI documentation（springdoc 3.x 起适配 Boot 4 / Spring Framework 7）
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

  // Password encoding
  implementation("org.springframework.security:spring-security-crypto")

  // Development database (H2) - only for development
  runtimeOnly("com.h2database:h2")

  // Testing
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  // Boot 4 把测试切片从 spring-boot-test-autoconfigure 拆到各模块 *-test，WebMvcTest 随之迁包
  testImplementation("org.springframework.boot:spring-boot-webmvc-test")
  testImplementation("org.springframework.security:spring-security-test")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot { mainClass.set("io.oddsmaker.control.Application") }

tasks.named<Test>("test") {
  // §5.4 真 PG/CH 端到端（RiskDecisionPgE2eTest，@EnabledIfSystemProperty）：本地门禁
  // -Drisk.pg.e2e=true 显式开启；CI 无 PG 自跳过。url/user/pass 仅在显式传入时透传
  // （缺省保留测试内默认值）；risk.ch.e2e.* 为 risk_actions 归档的 ClickHouse 参数。
  for (key in listOf(
    "risk.pg.e2e", "risk.pg.e2e.url", "risk.pg.e2e.user", "risk.pg.e2e.pass",
    "risk.ch.e2e.url", "risk.ch.e2e.user", "risk.ch.e2e.pass"
  )) {
    val v = System.getProperty(key)
    if (v != null && v.isNotEmpty()) systemProperty(key, v)
  }
}
