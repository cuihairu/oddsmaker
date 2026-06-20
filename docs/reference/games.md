# Games API

游戏管理 API，用于创建和管理游戏产品。

## 端点列表

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/games` | 创建游戏 |
| GET | `/api/games` | 获取游戏列表 |
| GET | `/api/games/{gameId}` | 获取游戏详情 |
| PUT | `/api/games/{gameId}` | 更新游戏 |
| DELETE | `/api/games/{gameId}` | 删除游戏 |
| POST | `/api/games/{gameId}/publish` | 发布游戏 |
| POST | `/api/games/{gameId}/unpublish` | 取消发布 |

## 游戏类型

| 类型 | 说明 |
|------|------|
| `RPG` | 角色扮演 |
| `STRATEGY` | 策略 |
| `ACTION` | 动作 |
| `PUZZLE` | 解谜 |
| `CASUAL` | 休闲 |
| `SIMULATION` | 模拟 |
| `SPORTS` | 体育 |
| `SHOOTER` | 射击 |
| `MMORPG` | 大型多人在线角色扮演 |
| `MOBA` | 多人在线战斗竞技 |
| `BATTLE_ROYALE` | 大逃杀 |

## 游戏状态

| 状态 | 说明 |
|------|------|
| `DEVELOPMENT` | 开发中 |
| `TESTING` | 测试中 |
| `LIVE` | 已上线 |
| `MAINTENANCE` | 维护中 |
| `DISCONTINUED` | 已停服 |

## 创建游戏

```http
POST /api/games
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "My Game",
  "genre": "RPG",
  "platforms": ["ANDROID", "IOS"],
  "timezone": "Asia/Shanghai",
  "defaultCurrency": "CNY"
}
```

**响应:**
```json
{
  "id": "game_abc123",
  "name": "My Game",
  "genre": "RPG",
  "status": "DEVELOPMENT",
  "platforms": ["ANDROID", "IOS"],
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## 获取游戏列表

```http
GET /api/games?page=0&size=20&sort=name
Authorization: Bearer {token}
```

## 获取游戏详情

```http
GET /api/games/{gameId}
Authorization: Bearer {token}
```

## 更新游戏

```http
PUT /api/games/{gameId}
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "Updated Game Name",
  "description": "Game description"
}
```

## 删除游戏

```http
DELETE /api/games/{gameId}
Authorization: Bearer {token}
```

**注意:** 只有非 LIVE 状态的游戏才能删除。
