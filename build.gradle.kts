plugins {
  id("org.springframework.boot") version "3.3.3" apply false
  id("io.spring.dependency-management") version "1.1.6" apply false
  // core plugins like 'java' need not be declared here in Gradle 9+
}

allprojects {
  group = "io.oddsmaker"
  version = "0.1.0"

  repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven")
  }
}

subprojects {
  apply(plugin = "java")
  tasks.withType<JavaCompile> { options.release.set(21) }
  tasks.withType<Test> { useJUnitPlatform() }
}
