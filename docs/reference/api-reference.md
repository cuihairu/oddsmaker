# Oddsmaker Control Service API Reference

Oddsmaker is a single-company, multi-game analytics and risk-control platform. The control service manages games, environments, API keys, tracking plans, PII policies, risk rules, users, role bindings, and audit logs.

Oddsmaker does not model `Organization` or `Tenant` as target business resources. The core boundary is `game_id + environment`.

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

### Environments

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/environments` | Create an environment |
| GET | `/api/games/{gameId}/environments` | List environments |
| GET | `/api/games/{gameId}/environments/{environment}` | Get environment config |
| PUT | `/api/games/{gameId}/environments/{environment}` | Update environment config |

Supported environments: `dev`, `staging`, `prod`.

### API Keys

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/api-keys` | Create an API key bound to `game_id + environment` |
| GET | `/api/api-keys?gameId=&environment=` | List API keys |
| GET | `/api/api-keys/{keyId}` | Get API key details |
| PUT | `/api/api-keys/{keyId}` | Update limits or policy bindings |
| DELETE | `/api/api-keys/{keyId}` | Delete an API key |
| POST | `/api/api-keys/{keyId}/rotate` | Rotate an API key |
| POST | `/api/api-keys/{keyId}/disable` | Disable an API key |

Key types:

- `client`: public write-only key for client SDKs.
- `server`: server-to-server key with one-time secret material for HMAC.
- `admin`: internal service key.

### Tracking Plans

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/games/{gameId}/environments/{environment}/tracking-plans` | Create a tracking plan draft |
| GET | `/api/games/{gameId}/environments/{environment}/tracking-plans/current` | Get the active plan |
| POST | `/api/tracking-plans/{planId}/publish` | Publish a plan |
| POST | `/api/tracking-plans/{planId}/rollback` | Roll back a plan |

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
| GET | `/api/risk-rules?gameId=&environment=` | List risk rules |
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
| GET | `/api/audit-logs?gameId=&environment=&actor=&action=&from=&to=` | Search audit logs |

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
