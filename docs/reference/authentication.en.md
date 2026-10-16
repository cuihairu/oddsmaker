# Authentication

> 🌐 Language: **English** · 中文 → [authentication.md](authentication.md)

The authentication, token, and permission model of the control service.

## Authentication Methods

| Method | How it is carried | Use case |
|------|------|----------|
| Session Token | `Authorization: Bearer {token}` | Console/control-plane API calls |
| Admin Token | `x-admin-token: {token}` | Bootstrap-style admin endpoints (creating accounts, issuing Keys, etc.) |
| API Key | `x-api-key: {key}` | Gateway event reporting (`/v1/batch`) |
| HMAC signature | `x-signature: t={seconds}, s={hex}` | Server SDK scenario: a SERVER-type key signs the request body on top of the API Key |

## Login

`POST /api/auth/login`, the request body uses `username` (not email):

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your_password"
}
```

The success response has only two fields; there is no refresh token and no expiresIn:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "user_123",
    "username": "admin",
    "roles": ["ADMIN"]
  }
}
```

Failure behavior: a nonexistent account and a wrong password both return 401 (without revealing whether the account exists); a disabled or locked account returns 403. Login, login failure, and logout all write audit logs.

## Logout

`POST /api/auth/logout`. JWT is stateless and the server does not store sessions; logout is done by the client deleting its local token, and the server only adds a logout audit entry when it can identify the identity. There is no `/api/auth/refresh` endpoint — log in again after the token expires.

## Using the Token

```http
GET /api/games
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

## API Key Authentication

Event reporting goes through the gateway without Bearer:

```http
POST /v1/batch
x-api-key: pk_your_api_key_here
Content-Type: application/x-ndjson

{...}
```

Keys come in three tiers: `client` (client SDK), `server` (server side, must be paired with the HMAC `x-signature`, see below), and `admin` (read-only administration). A client key carrying a signature header is rejected (401 `signature_not_supported`).

### HMAC Request Signing (server key)

Every request of a SERVER-type key must carry a signature header; format and validation window:

```
x-signature: t=1760000000, s=5256a8...（hex）
s = hex(hmac_sha256(secret, t + "." + raw_body))
```

If the timestamp `t` differs from the server time by more than ±300 seconds, it returns 401; a duplicate submission of the same signature is blocked by replay protection (401 `replay_detected`); after a secret rotation, old signatures also return 401.

## Permission Scope

Roles are assigned via `/api/users/{userId}/role-assignments`, supporting three scope levels: global / game / environment. There are 8 built-in roles:

| Role | Permissions |
|------|------|
| `operator` | Company-level operations administrator, all permissions |
| `game_admin` | Management within a single game |
| `analyst` | View analytics data, create reports |
| `marketing` | User analytics, A/B experiments |
| `finance` | Revenue and monetization data |
| `developer` | SDK management, API Key |
| `viewer` | Read-only |
| `qa` | Test environment operations |

There are no `owner` and `risk_admin` roles; risk-control operation permissions hang on operator/game_admin. For authorization details (permission-point list, scope revocation), see the "Role Management" page of the control plane.
