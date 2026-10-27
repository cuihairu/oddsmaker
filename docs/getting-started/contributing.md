# Contributing Guide

Code style
- Java: Google Java Style (2-space indentation); Spring Boot 4; Lombok disabled (for ease of review)
- Kotlin: official Kotlin style; Android SDK depends only on OkHttp
- TypeScript: strict mode; ES2019 (target; module/lib are ES2020); no any; small functions
- C#/Swift: minimize third-party dependencies; keep the SDK lightweight

Commit conventions
- Commit message prefixes: feat/fix/chore/docs/test/build/ci/release, etc.
- Keep changes minimal and reviewable; reference Issues with #123

Testing
- Gateway: unit/integration tests cover new paths; PRs pass GitHub Actions
- Flink: build locally with `./gradlew :jobs:flink:<job>:build` (see each job's build.gradle.kts for the fatJar task); unit tests for key functions

Branch strategy
- main is stable; develop in `feature/<topic>`; release in `release/<ver>`

Security and secrets
- Never commit secrets; use environment variables or a secrets manager

Code review
- Open a PR; at least one reviewer must approve
