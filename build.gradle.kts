plugins {
  id("org.springframework.boot") version "4.1.1" apply false
  id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
  group = "io.oddsmaker"
  version = "0.1.0"

  repositories {
    // aliyun 仅本地 CN 网络用（GitHub runner 对其 504，2026-10-19 实证）；CI 直连官方源
    if (System.getenv("CI") == null) {
      maven("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
    maven("https://packages.confluent.io/maven")
  }
}

subprojects {
  apply(plugin = "java")
  tasks.withType<JavaCompile> { options.release.set(21) }

  dependencies {
    // Gradle 9 不再自动把 JUnit Platform launcher 注入 test runtime classpath，
    // 缺它则所有 Test 任务启动即失败（"Failed to load JUnit Platform"），故统一在此声明
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
  }

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
