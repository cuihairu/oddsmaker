# API 参考

> 🌐 Language: **中文** · English → [index.md](index.md)

欢迎使用 Oddsmaker API 参考。本部分提供所有 API 端点的详细文档。

## Base URL

两个服务对外暴露 HTTP API：

| 服务 | Base URL |
|------|----------|
| 控制平面 | `http://localhost:8085/api` |
| Gateway（事件摄入） | `http://localhost:8080/v1` |

生产部署时，请替换为你的实际域名。

## 认证

共有三种凭证，各自的作用域不同：

| 凭证 | 放置位置 | 使用方 |
|------|------|----------|
| 会话 token（来自登录） | `Authorization: Bearer <token>` | 从控制台发起的 control 平面 API 调用 |
| 管理员 token（`x-admin-token`） | `x-admin-token: <token>` | 无 Bearer token 时任何 `/api/*` 端点的兜底凭证（授予 `ROLE_ADMIN`；`/api/config/` 与 `/api/auth/` 豁免） |
| API key | `x-api-key: <key>` | gateway 上的事件摄入（`/v1/batch`）；server key 还会用 HMAC 对请求体签名并放入 `x-signature` |

登录流程、角色模型与 HMAC 请求签名参见 [Authentication](./authentication)。

## API 版本控制

- `/v1/` —— gateway 摄入路由（`/v1/batch`、`/v1/inspector`），稳定。
- `/api/` —— control 服务路由，不带版本号；变更保持向后兼容。

## 常见响应码

| 状态码 | 说明 |
|------|-------------|
| 200 | 成功 |
| 201 | 已创建 |
| 400 | 错误请求 |
| 401 | 未授权 |
| 403 | 禁止访问 |
| 404 | 未找到 |
| 409 | 冲突 |
| 429 | 请求过多 |
| 500 | 内部服务器错误 |

## 响应格式

control 服务的响应有两种形态：

信封式端点（大多数 control 服务路由，经 `ApiResponse`）返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": "2026-10-04 12:00:00"
}
```

`timestamp` 格式为 `yyyy-MM-dd HH:mm:ss`；`traceId` 是保留字段，目前从不填充，因此永远不会出现在响应中（null 字段在序列化时被排除）。

列表端点完全不用信封，而是返回一个扁平的顶层对象：

```json
{ "items": [], "total": 100 }
```

错误路径会把 HTTP 状态（如 `403`）填入 `code`，原因填入 `message`。gateway 的响应则是扁平的：认证失败返回 `{"error": "<reason>"}`，限流返回 `{"code": "too_many_requests", "message": "rate limited", "request_id": "…"}` 并附带 `Retry-After: 60`，批量摄入返回 `{accepted, rejected, sampled_out, duplicates, next_hint_ms}`。

## 分页

列表端点接受 `page` / `size`（page 从 0 开始）并返回 `{items, total}`——计数就是顶层 `total`；没有 `pageInfo`：

```bash
GET /api/games?page=0&size=20
```

## 速率限制

gateway 上的摄入按 key 与客户端 IP 限流：

- **按 API key**：每分钟 600 次请求
- **按 IP**：每分钟 300 次请求

超限请求会收到 HTTP 429，带 `Retry-After: 60` 与上面的 JSON 响应体。没有 `X-RateLimit-*` 响应头。限流路由仅存在于 gateway；control 服务的列表端点是分页而非限流。

## API 分节

### 核心 API

- [Games API](/reference/games.zh) - 游戏管理
- [Environments API](/reference/environments.zh) - 环境管理
- [Authentication](/reference/authentication.zh) - API key 与请求认证

### 分析 API

- [Experiments API](/reference/experiments.zh) - A/B 测试
- [Analytics API](/reference/analytics.zh) - 分析与报表

### 风控管理 API

- [Risk API](/reference/risk.zh) - 风控管理

### 高级 API

- [ML Models API](/reference/ml-models.zh) - 机器学习模型
- [Gaming Scenarios](/reference/gaming-scenarios.zh) - 玩法分析场景

## SDK

SDK 位于本仓库的 `sdks/` 目录下：

- `sdks/web` — JavaScript/TypeScript
- `sdks/android` — Android (Kotlin)
- `sdks/ios` — iOS (Swift)
- `sdks/unity` — Unity (C#)
- `sdks/server` — Java 服务端 SDK（HMAC 签名的摄入）

每个目录都有自己的 README，包含接入步骤。

## OpenAPI 规范

OpenAPI 规范可通过以下地址访问：

```
http://localhost:8085/v3/api-docs
```

Swagger UI 可通过以下地址访问：

```
http://localhost:8085/swagger-ui.html
```

## 支持

请通过 [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues) 报告 API 问题。
