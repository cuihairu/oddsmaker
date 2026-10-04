plugins { id("java-library") }

// 零依赖 monorepo 模块（subtree 拆出备用）：运行时仅 JDK（java.net.http / javax.crypto /
// java.util.zip），不依赖仓库内 libs/*，也不引第三方运行时；测试仅 JUnit。
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
