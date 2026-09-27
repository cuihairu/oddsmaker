plugins { id("java-library") }

dependencies {
  // POJO 本体保持零依赖（被所有模块引用）；JSON 仅测试作用域：契约用 Jackson 往返断言，
  // 版本与其余模块的显式声明对齐（2.17.2）
  testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
