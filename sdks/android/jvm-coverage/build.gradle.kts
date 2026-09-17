// 独立 JVM 工程:直测 oddsmaker-android 源码(无 Android SDK 环境)。
// 源码以 srcDir 链接引用 ../oddsmaker-android/src/main/java,不复制;android.* API 用 stub 替身。
plugins {
  kotlin("jvm") version "1.9.24"
  jacoco
}

repositories { mavenCentral() }

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  // org.json 正确坐标是 org.json:json;其 POM 依赖 kotlin-stdlib 2.0(与 1.9.24 编译器冲突),SDK 只用其 Java 类 → 排除
  implementation("org.json:json:20240303") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  }
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

sourceSets.main {
  kotlin.srcDir("../oddsmaker-android/src/main/java")
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    csv.required.set(true)
    html.required.set(false)
  }
}
