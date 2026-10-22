# Environments API

> 🌐 Language: **English** · 中文 → [environments.zh.md](../zh/reference/environments)

Environment management API, used to manage the different deployment environments of a game (dev, staging, prod).

## Endpoint List

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/games/{gameId}/environments` | Create environment |
| GET | `/api/games/{gameId}/environments` | Get environment list |
| GET | `/api/games/{gameId}/environments/{environmentName}` | Get environment details |
| PUT | `/api/games/{gameId}/environments/{environmentName}` | Update environment |
| DELETE | `/api/games/{gameId}/environments/{environmentName}` | Delete environment |

## Environment Types

| Type | Description |
|------|------|
| `DEVELOPMENT` | Development environment |
| `TESTING` | Testing environment |
| `STAGING` | Staging environment |
| `PRODUCTION` | Production environment |
| `LOADTEST` | Load-test environment |

## Create Environment

```http
POST /api/games/{gameId}/environments
Content-Type: application/json
Authorization: Bearer {token}

{
  "name": "prod",
  "type": "PRODUCTION",
  "displayName": "Production Environment",
  "description": "Production environment for live players"
}
```

**Response:**
```json
{
  "id": "env_game_demo_prod",
  "gameId": "game_demo",
  "name": "prod",
  "type": "PRODUCTION",
  "displayName": "Production Environment",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Get Environment List

```http
GET /api/games/{gameId}/environments
Authorization: Bearer {token}
```

**Response:**
```json
[
  {
    "id": "env_game_demo_dev",
    "name": "dev",
    "type": "DEVELOPMENT"
  },
  {
    "id": "env_game_demo_prod",
    "name": "prod",
    "type": "PRODUCTION"
  }
]
```

## Environment Configuration

Environment-level configuration includes the enabled state, sampling switch, and sampling rate (delivered via the Control internal interface; the gateway returns 503 `environment_unavailable` for non-active environments; sampling uses deterministic bucketing by device_id, so events from the same device go in and out together).

Key-level configuration hangs on the API Key and is bound to the environment the Key belongs to: PII policy, props whitelist, rate limiting (`rpm`/`ipRpm`). Data retention is determined by the environment-level `dataRetentionDays`: `RetentionEnforcementService` takes the value as `env.dataRetentionDays ?? game.dataRetentionDays ?? default`, and applies it to the ClickHouse TTL.

## Recommended Environments

| Environment | Purpose |
|------|------|
| `dev` | Development and testing |
| `staging` | Staging verification |
| `prod` | Official launch |

These three environments are created automatically when a game is created.
