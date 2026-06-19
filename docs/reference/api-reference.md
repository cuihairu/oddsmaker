# Oddsmaker Control Service API Reference

Oddsmaker is a single-company, multi-game analytics and risk-control platform. The control service manages games, environments, storage profiles, API keys, tracking plans, PII policies, risk rules, users, role bindings, and audit logs.

Oddsmaker does not model `Organization` or `Tenant` as target business resources. The core business boundary is `game_id + environment`, while physical routing is controlled by `storage_profile`.

## Authentication

Most endpoints require an admin session or bearer token:

```http
Authorization: Bearer YOUR_TOKEN
```

## Resources

### Games

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games` | Create a game |
| GET | `/api/games` | List games |
| GET | `/api/games/{gameId}` | Get game details |
| PUT | `/api/games/{gameId}` | Update a game |
| DELETE | `/api/games/{gameId}` | Delete or archive a game |
| POST | `/api/games/{gameId}/publish` | Move a game to live status |
| POST | `/api/games/{gameId}/unpublish` | Move a live game to maintenance |

### Environments

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/environments` | Create an environment |
| GET | `/api/games/{gameId}/environments` | List environments |
| GET | `/api/games/{gameId}/environments/{environmentName}` | Get environment config |
| PUT | `/api/games/{gameId}/environments/{environmentName}` | Update environment config |
| DELETE | `/api/games/{gameId}/environments/{environmentName}` | Delete an environment |

Recommended environments: `dev`, `staging`, `prod`.

An environment is a logical lifecycle stage, not a synonym for a dedicated database.

### Storage Profiles

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/storage-profiles` | Create a storage routing profile |
| GET | `/api/storage-profiles` | List storage profiles |
| GET | `/api/storage-profiles/{profileId}` | Get storage profile details |
| PUT | `/api/storage-profiles/{profileId}` | Update routing backends or isolation strategy |
| DELETE | `/api/storage-profiles/{profileId}` | Delete a storage profile |

### API Keys

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/api-keys` | Create an API key bound to `gameId + environmentId` |
| GET | `/api/api-keys?gameId=&environmentId=` | List API keys |
| GET | `/api/api-keys/{keyId}` | Get API key details |
| PUT | `/api/api-keys/{keyId}` | Update limits or policy bindings |
| DELETE | `/api/api-keys/{keyId}` | Delete an API key |
| POST | `/api/keys` | Legacy compatible create endpoint |
| GET | `/api/keys?gameId=&environmentId=` | Legacy compatible query endpoint |
| GET | `/api/keys/{keyId}` | Legacy compatible detail endpoint |
| PUT | `/api/keys/{keyId}/policy` | Legacy compatible policy update endpoint |
| DELETE | `/api/keys/{keyId}` | Legacy compatible delete endpoint |

Key types:

- `client`: public write-only key for client SDKs.
- `server`: server-to-server key with one-time secret material for HMAC.
- `admin`: internal service key.

### Tracking Plans

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/environments/{environmentName}/tracking-plans` | Planned draft endpoint |
| GET | `/api/games/{gameId}/environments/{environmentName}/tracking-plans/current` | Planned current plan endpoint |
| POST | `/api/tracking-plans/{planId}/publish` | Publish a plan |
| POST | `/api/tracking-plans/{planId}/rollback` | Roll back a plan |

### Experiments

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/experiments` | Create an experiment |
| GET | `/api/experiments?gameId=&environmentId=&environment=&status=` | List experiments |
| GET | `/api/experiments/{id}` | Get experiment details |
| PUT | `/api/experiments/{id}` | Update an experiment |
| DELETE | `/api/experiments/{id}` | Delete an experiment |
| POST | `/api/experiments/{id}/publish` | Start an experiment |
| POST | `/api/experiments/{id}/pause` | Pause an experiment |

**Public endpoint** (no authentication):
| GET | `/api/config/{gameId}/{environment}` | Get running experiment configs for SDK |

**Fields**:
- `environmentId`: internal environment ID (alternative to `environment`)
- `environment`: logical environment name like `dev`/`staging`/`prod` (alternative to `environmentId`)

### PII Policies

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/pii-policies` | Create a PII policy |
| GET | `/api/pii-policies/{policyId}` | Get policy details |
| PUT | `/api/pii-policies/{policyId}` | Update a policy |

### Risk Rules

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/risk-rules` | Create a risk rule |
| GET | `/api/risk-rules?gameId=&environmentId=` | Planned list endpoint |
| GET | `/api/risk-rules/{ruleId}` | Get risk rule details |
| PUT | `/api/risk-rules/{ruleId}` | Update a rule |
| DELETE | `/api/risk-rules/{ruleId}` | Delete a rule |
| POST | `/api/risk-rules/{ruleId}/publish` | Publish a rule |
| POST | `/api/risk-rules/{ruleId}/disable` | Disable a rule |

### Users And Role Bindings

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/users` | List users |
| POST | `/api/users` | Create a user |
| GET | `/api/users/{userId}` | Get user details |
| PUT | `/api/users/{userId}` | Update a user |
| POST | `/api/users/{userId}/role-bindings` | Add a role binding |
| DELETE | `/api/users/{userId}/role-bindings/{bindingId}` | Remove a role binding |

Scopes:

- `global`
- `game`
- `environment`

Roles:

- `owner`
- `operator`
- `analyst`
- `developer`
- `risk_admin`
- `viewer`

### Audit Logs

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/audit-logs?gameId=&environmentId=&actor=&action=&from=&to=` | Planned search endpoint |

## Response Format

```json
{
  "code": 200,
  "message": "Success",
  "data": {},
  "timestamp": "2026-06-18T12:00:00Z",
  "traceId": "abc123"
}
```

## Error Codes

| Code | Description |
|---|---|
| 400 | Bad request |
| 401 | Unauthenticated |
| 403 | Permission denied |
| 404 | Resource not found |
| 409 | Conflict |
| 429 | Rate limited |
| 500 | Internal error |
