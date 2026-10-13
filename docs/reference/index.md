# API Reference

Welcome to the Oddsmaker API Reference. This section provides detailed documentation for all API endpoints.

## Base URL

Two services expose HTTP APIs:

| Service | Base URL |
|------|----------|
| Control plane | `http://localhost:8085/api` |
| Gateway (event ingestion) | `http://localhost:8080/v1` |

For production deployments, replace with your actual domains.

## Authentication

Three credentials exist, each with its own scope:

| Credential | Where it goes | Used by |
|------|------|----------|
| Session token (from login) | `Authorization: Bearer <token>` | Control API calls from the console |
| Admin token (`x-admin-token`) | `x-admin-token: <token>` | Fallback credential for any `/api/*` endpoint when no Bearer token is present (grants `ROLE_ADMIN`; `/api/config/` and `/api/auth/` are exempt) |
| API key | `x-api-key: <key>` | Event ingest on the gateway (`/v1/batch`); server keys additionally sign the body with HMAC `x-signature` |

See [Authentication](./authentication) for the login flow, role model, and HMAC request signing.

## API Versioning

- `/v1/` — gateway ingest routes (`/v1/batch`, `/v1/inspector`), stable.
- `/api/` — control-service routes, unversioned; changes are backward compatible.

## Common Response Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 201 | Created |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 409 | Conflict |
| 429 | Too Many Requests |
| 500 | Internal Server Error |

## Response Formats

Control-service responses use two shapes:

Envelope endpoints (most control-service routes, via `ApiResponse`) return:

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": "2026-10-04 12:00:00"
}
```

`timestamp` is formatted `yyyy-MM-dd HH:mm:ss`; `traceId` is a reserved field that is never currently populated, so it never appears in responses (null fields are excluded from serialization).

List endpoints skip the envelope entirely and return a flat top-level object instead:

```json
{ "items": [], "total": 100 }
```

Error paths fill `code` with the HTTP status (e.g. `403`) and `message` with the reason. Gateway responses are flat instead: auth failures return `{"error": "<reason>"}`, rate limiting returns `{"code": "too_many_requests", "message": "rate limited", "request_id": "…"}` with `Retry-After: 60`, and batch ingest returns `{accepted, rejected, sampled_out, duplicates, next_hint_ms}`.

## Pagination

List endpoints take `page` / `size` (0-based page) and return `{items, total}` — the count is the top-level `total`; there is no `pageInfo`:

```bash
GET /api/games?page=0&size=20
```

## Rate Limiting

Ingest on the gateway is rate-limited per key and per client IP:

- **Per API key**: 600 requests/minute
- **Per IP**: 300 requests/minute

Over-limit requests get HTTP 429 with `Retry-After: 60` and the JSON body above. There are no `X-RateLimit-*` response headers. The rate-limited routes are gateway-only; control-service list endpoints are paginated rather than rate-limited.

## API Sections

### Core APIs

- [Games API](/reference/games) - Game management
- [Environments API](/reference/environments) - Environment management
- [Authentication](/reference/authentication) - API key and request authentication

### Analytics APIs

- [Experiments API](/reference/experiments) - A/B testing
- [Analytics API](/reference/analytics) - Analytics and reporting

### Risk Management APIs

- [Risk API](/reference/risk) - Risk management

### Advanced APIs

- [ML Models API](/reference/ml-models) - Machine learning models
- [Gaming Scenarios](/reference/gaming-scenarios) - Gameplay analytics scenarios

## SDKs

SDKs live in this repository under `sdks/`:

- `sdks/web` — JavaScript/TypeScript
- `sdks/android` — Android (Kotlin)
- `sdks/ios` — iOS (Swift)
- `sdks/unity` — Unity (C#)
- `sdks/server` — Java server SDK (HMAC-signed ingest)

Each directory has its own README with integration steps.

## OpenAPI Specification

The OpenAPI specification is available at:

```
http://localhost:8085/v3/api-docs
```

Swagger UI is available at:

```
http://localhost:8085/swagger-ui.html
```

## Support

Report API problems through [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues).
