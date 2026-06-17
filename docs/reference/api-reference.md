# Pit Gaming Analytics Platform API Reference

## 概述

Pit 是一个专业的多租户游戏分析平台，提供企业级的游戏数据分析和管理功能。本文档描述了控制服务的完整 REST API。

## 认证

大多数 API 端点需要 JWT 认证。在请求头中包含访问令牌：

```
Authorization: Bearer YOUR_JWT_TOKEN
```

## API 端点概览

### 认证管理 (`/api/auth`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/login` | 用户登录 | 无 |
| POST | `/register` | 用户注册 | 无 |
| POST | `/verify-email` | 邮箱验证 | 无 |
| POST | `/forgot-password` | 请求密码重置 | 无 |
| POST | `/reset-password` | 重置密码 | 无 |
| POST | `/refresh-token` | 刷新访问令牌 | 有效JWT |

### 组织管理 (`/api/organizations`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/` | 创建组织 | SUPER_ADMIN |
| GET | `/` | 获取所有组织 | SUPER_ADMIN |
| GET | `/search` | 搜索组织 | SUPER_ADMIN |
| GET | `/{orgId}` | 获取组织详情 | SUPER_ADMIN, ORG_ADMIN |
| PUT | `/{orgId}` | 更新组织 | SUPER_ADMIN, ORG_ADMIN |
| DELETE | `/{orgId}` | 删除组织 | SUPER_ADMIN |
| POST | `/{orgId}/upgrade` | 升级组织层级 | SUPER_ADMIN |
| GET | `/by-tier/{tier}` | 按层级获取组织 | SUPER_ADMIN |
| GET | `/statistics` | 获取组织统计 | SUPER_ADMIN |
| GET | `/check-name` | 检查组织名称可用性 | SUPER_ADMIN, ORG_ADMIN |
| GET | `/upgrade-candidates` | 获取升级候选组织 | SUPER_ADMIN |

### 游戏管理 (`/api/games`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/` | 创建游戏 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| GET | `/` | 获取游戏列表 | 基于权限 |
| GET | `/search` | 搜索游戏 | 基于权限 |
| GET | `/{gameId}` | 获取游戏详情 | 基于权限 |
| PUT | `/{gameId}` | 更新游戏 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| DELETE | `/{gameId}` | 删除游戏 | SUPER_ADMIN, ORG_ADMIN |
| POST | `/{gameId}/publish` | 发布游戏 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| POST | `/{gameId}/unpublish` | 下线游戏 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| GET | `/by-status/{status}` | 按状态获取游戏 | SUPER_ADMIN |
| GET | `/by-genre/{genre}` | 按类型获取游戏 | SUPER_ADMIN |
| GET | `/by-platform/{platform}` | 按平台获取游戏 | SUPER_ADMIN |
| GET | `/statistics` | 获取游戏统计 | SUPER_ADMIN |
| GET | `/statistics/{orgId}` | 获取组织游戏统计 | 基于权限 |

### 用户管理 (`/api/users`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/` | 创建用户 | SUPER_ADMIN, ORG_ADMIN |
| GET | `/` | 获取用户列表 | 基于权限 |
| GET | `/search` | 搜索用户 | SUPER_ADMIN, ORG_ADMIN |
| GET | `/{userId}` | 获取用户详情 | 基于权限 |
| GET | `/me` | 获取当前用户信息 | 已认证用户 |
| PUT | `/{userId}` | 更新用户 | 基于权限 |
| DELETE | `/{userId}` | 删除用户 | SUPER_ADMIN, ORG_ADMIN |
| POST | `/{userId}/roles` | 分配角色 | SUPER_ADMIN, ORG_ADMIN |
| DELETE | `/{userId}/roles/{roleId}` | 移除角色 | SUPER_ADMIN, ORG_ADMIN |
| GET | `/{userId}/roles` | 获取用户角色 | 基于权限 |

### API密钥管理 (`/api/api-keys`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/` | 创建API密钥 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| GET | `/` | 获取API密钥列表 | 基于权限 |
| GET | `/{keyId}` | 获取API密钥详情 | 基于权限 |
| PUT | `/{keyId}` | 更新API密钥 | 基于权限 |
| DELETE | `/{keyId}` | 删除API密钥 | 基于权限 |
| POST | `/{keyId}/regenerate` | 重新生成API密钥 | 基于权限 |
| POST | `/{keyId}/toggle` | 启用/禁用API密钥 | 基于权限 |
| GET | `/{keyId}/statistics` | 获取API密钥统计 | 基于权限 |
| POST | `/validate` | 验证API密钥 | 无 |

### 环境管理 (`/api/environments`)

| 方法 | 端点 | 描述 | 权限要求 |
|------|------|------|----------|
| POST | `/` | 创建环境 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |
| GET | `/` | 获取环境列表 | 基于权限 |
| GET | `/{envId}` | 获取环境详情 | 基于权限 |
| PUT | `/{envId}` | 更新环境 | 基于权限 |
| DELETE | `/{envId}` | 删除环境 | SUPER_ADMIN, ORG_ADMIN |
| POST | `/{envId}/activate` | 激活环境 | 基于权限 |
| POST | `/{envId}/deactivate` | 停用环境 | 基于权限 |
| POST | `/{envId}/reset` | 重置环境 | 基于权限 |
| POST | `/{envId}/clone` | 克隆环境 | 基于权限 |
| GET | `/{envId}/statistics` | 获取环境统计 | 基于权限 |
| GET | `/templates` | 获取环境配置模板 | SUPER_ADMIN, ORG_ADMIN, GAME_ADMIN |

## 权限等级

### 全局角色

- **SUPER_ADMIN**: 系统超级管理员，拥有所有权限
- **ORG_ADMIN**: 组织管理员，管理组织内所有资源
- **GAME_ADMIN**: 游戏管理员，管理特定游戏资源
- **ANALYST**: 分析师，拥有数据查看和分析权限
- **VIEWER**: 查看者，只读权限
- **DEVELOPER**: 开发者，开发环境访问权限
- **QA**: 测试人员，测试环境访问权限
- **MARKETING**: 市场人员，营销数据访问权限

### 权限范围

- **GLOBAL**: 全局权限
- **ORGANIZATION**: 组织级权限
- **GAME**: 游戏级权限
- **ENVIRONMENT**: 环境级权限

## 响应格式

所有 API 响应都遵循统一的格式：

### 成功响应

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    // 响应数据
  },
  "timestamp": "2024-01-01 12:00:00",
  "traceId": "abc123def456"
}
```

### 分页响应

```json
{
  "code": 200,
  "message": "Success",
  "data": [
    // 数据数组
  ],
  "pageInfo": {
    "total": 100,
    "page": 0,
    "size": 20,
    "totalPages": 5
  },
  "timestamp": "2024-01-01 12:00:00",
  "traceId": "abc123def456"
}
```

### 错误响应

```json
{
  "code": 400,
  "message": "Validation failed",
  "data": {
    "email": "Invalid email format",
    "password": "Password must be at least 8 characters"
  },
  "timestamp": "2024-01-01 12:00:00",
  "traceId": "abc123def456"
}
```

## 错误代码

| 代码 | 描述 |
|------|------|
| 200 | 成功 |
| 201 | 创建成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 权限不足 |
| 404 | 资源未找到 |
| 409 | 资源冲突 |
| 423 | 账户被锁定 |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |

## 限流规则

基于组织层级的API调用限制：

- **FREE 层级**: 1,000 次/小时
- **PRO 层级**: 10,000 次/小时
- **ENTERPRISE 层级**: 无限制

## 支持与联系

- **文档**: https://docs.pit.example.com
- **GitHub**: https://github.com/yourusername/pit
- **支持邮箱**: support@pit.example.com
- **技术支持**: dev@pit.example.com