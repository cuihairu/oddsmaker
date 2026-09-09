plugins {
  id("org.springframework.boot") version "3.3.3" apply false
  id("io.spring.dependency-management") version "1.1.6" apply false
  // core plugins like 'java' need not be declared here in Gradle 9+
}

allprojects {
  group = "io.oddsmaker"
  version = "0.1.0"

  repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven("https://packages.confluent.io/maven")
  }
}

subprojects {
  apply(plugin = "java")
  tasks.withType<JavaCompile> { options.release.set(21) }

  // 测试覆盖率：test 后自动生成 JaCoCo 报告（build/reports/jacoco/test/）
  apply(plugin = "jacoco")
  tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport> {
    dependsOn(tasks.withType<Test>())
    reports {
      xml.required.set(true)
      html.required.set(true)
      csv.required.set(true)
    }
  }
  tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport")
  }
}
