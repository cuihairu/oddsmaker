// 独立构建入口（subtree 拆出备用）：cd sdks/server && gradle test
// 在 monorepo 内由根 settings.gradle.kts include("sdks:server") 接管，本文件不参与。
rootProject.name = "oddsmaker-server-sdk"
