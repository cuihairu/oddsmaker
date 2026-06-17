# 贡献指南

代码风格
- Java：Google Java Style（缩进2空格）；Spring Boot 3；Lombok 禁用（便于审查）
- Kotlin：Kotlin 官方风格；Android SDK 仅依赖 OkHttp
- TypeScript：严格模式；ES2020；无 any；小函数
- C#/Swift：尽量无第三方依赖，保持 SDK 轻量

提交规范
- Commit 信息前缀：feat/fix/chore/docs/test/build/ci/release 等
- 最小变更可审；关联 Issue 使用 #123

测试
- 网关：单元/集成测试覆盖新增路径；PR 通过 GitHub Actions
- Flink：在本地 withIntegration=skip 组装；关键函数单测

分支策略
- main 稳定；feature/<topic> 开发；release/<ver> 发布

安全与秘密
- 不提交密钥；使用环境变量或密钥管理

代码审查
- 提交 PR，至少一名 reviewer 通过
