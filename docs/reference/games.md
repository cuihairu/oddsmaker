# Games API

> 🌐 Language: **English** · 中文 → [games.zh.md](games.zh.md)

Game management API for creating and managing game products.

## Endpoint List

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/games` | Create a game |
| GET | `/api/games` | Get the game list |
| GET | `/api/games/{gameId}` | Get game details |
| PUT | `/api/games/{gameId}` | Update a game |
| DELETE | `/api/games/{gameId}` | Delete a game |
| POST | `/api/games/{gameId}/publish` | Publish a game |
| POST | `/api/games/{gameId}/unpublish` | Unpublish a game |

## Game Genres

| Genre | Description |
|------|------|
| `RPG` | Role-playing |
| `STRATEGY` | Strategy |
| `ACTION` | Action |
| `PUZZLE` | Puzzle |
| `CASUAL` | Casual |
| `SIMULATION` | Simulation |
| `SPORTS` | Sports |
| `RACING` | Racing |
| `SHOOTER` | Shooter |
| `MMORPG` | Massively multiplayer online role-playing |
| `MOBA` | Multiplayer online battle arena |
| `BATTLE_ROYALE` | Battle royale |
| `OTHER` | Other |

## Game Statuses

| Status | Description |
|------|------|
| `DEVELOPMENT` | In development |
| `TESTING` | In testing |
| `LIVE` | Live |
| `PUBLISHED` | Published (default status for seed games since V0.4.1) |
| `MAINTENANCE` | Under maintenance |
| `DISCONTINUED` | Discontinued |

## Create a Game

`platforms` values are `WEB` / `MOBILE` / `PC` / `CONSOLE` / `VR` / `AR` (no ANDROID/IOS). The timezone field is named `defaultTimezone` (IANA identifier). On successful creation, three environments — `dev` / `staging` / `prod` — are created automatically.

```http
POST /api/games
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "My Game",
  "genre": "RPG",
  "platforms": ["MOBILE", "PC"],
  "defaultTimezone": "Asia/Shanghai",
  "defaultCurrency": "CNY"
}
```

**Response:**
```json
{
  "id": "game_abc123",
  "name": "My Game",
  "genre": "RPG",
  "status": "DEVELOPMENT",
  "platforms": ["MOBILE", "PC"],
  "defaultTimezone": "Asia/Shanghai",
  "serverEventsEnabled": false,
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Get the Game List

```http
GET /api/games?page=0&size=20&sort=name
Authorization: Bearer {token}
```

## Get Game Details

```http
GET /api/games/{gameId}
Authorization: Bearer {token}
```

## Update a Game

```http
PUT /api/games/{gameId}
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "Updated Game Name",
  "description": "Game description"
}
```

## Delete a Game

```http
DELETE /api/games/{gameId}
Authorization: Bearer {token}
```

Only games not in LIVE status can be deleted.
