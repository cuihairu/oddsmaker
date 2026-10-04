# Getting Started

Welcome to Oddsmaker, the professional gaming analytics platform for single company operating multiple games.

## Overview

Oddsmaker provides:

- **Multi-Game Architecture**: Manage multiple games with isolated environments
- **Real-time Analytics**: Event pipeline on Kafka + Flink + ClickHouse
- **Risk Control**: Detect and prevent cheating and fraud
- **A/B Testing**: Run experiments with statistical analysis
- **Machine Learning**: Train and deploy ML models for predictions
- **Enterprise Security**: MFA, RBAC, and audit logging

## Prerequisites

Before getting started, ensure you have:

- Java 21
- Gradle 8.10+ (the repo ships a Gradle Wrapper 8.10.1 — prefer `./gradlew`)
- Docker and Docker Compose
- PostgreSQL 16+
- Redis 7+
- Kafka (optional, for real-time processing)

## Quick Start

### Option 1: Docker Compose full stack (Recommended)

The root `docker-compose.quickstart.yml` brings up control + gateway (single image, two services) plus all dependencies (PostgreSQL / Redis / Kafka / Apicurio / ClickHouse). Control listens on 38085, gateway on 38080:

```bash
# Clone the repository
git clone https://github.com/cuihairu/oddsmaker.git
cd oddsmaker

# Optional: adjust ports / passwords / tokens (see .env.example)
cp .env.example .env

# Start control + gateway + all dependencies
docker compose -f docker-compose.quickstart.yml up -d

# Verify services are running
docker compose -f docker-compose.quickstart.yml ps

# Check health
curl http://localhost:38085/actuator/health   # control (management plane)
curl http://localhost:38080/actuator/health   # gateway (ingest)
```

`infra/docker-compose.yml` is dependencies-only (Kafka / ClickHouse / Apicurio / Superset / observability) — it does not include control or gateway.

### Option 2: Local Development

```bash
# Start dependencies
docker compose -f infra/docker-compose.yml up -d kafka clickhouse apicurio

# Build the project
gradle :services:control-service:bootJar

# Run the application
gradle :services:control-service:bootRun
```

## First Steps

### 1. Create a Game

`genre` accepts: ACTION, RPG, STRATEGY, PUZZLE, CASUAL, SIMULATION, SPORTS, RACING, SHOOTER, MMORPG, MOBA, BATTLE_ROYALE, OTHER. `platforms` accepts: WEB, MOBILE, PC, CONSOLE, VR, AR. Creating a game also seeds `dev` / `staging` / `prod` environments automatically.

```bash
curl -X POST http://localhost:38085/api/games \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "name": "My Game",
    "genre": "RPG",
    "platforms": ["MOBILE", "PC"],
    "defaultTimezone": "UTC",
    "defaultCurrency": "USD"
  }'
```

### 2. Create an Environment

```bash
curl -X POST http://localhost:38085/api/games/GAME_ID/environments \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "name": "production",
    "type": "PRODUCTION",
    "displayName": "Production Environment"
  }'
```

### 3. Create an API Key

```bash
curl -X POST http://localhost:38085/api/api-keys \
  -H "Content-Type: application/json" \
  -H "x-admin-token: YOUR_ADMIN_TOKEN" \
  -d '{
    "gameId": "GAME_ID",
    "environmentId": "ENV_ID",
    "name": "android-client",
    "keyType": "client"
  }'
```

### 4. Send Events

Ingest goes to the **gateway**, not control: `/v1/batch` on port 38080 (NDJSON or a JSON array). `environment` is the environment *name* as configured for the game (`dev` / `staging` / `prod` by default), not the environment type:

```bash
curl -X POST http://localhost:38080/v1/batch \
  -H "Content-Type: application/x-ndjson" \
  -H "x-api-key: YOUR_API_KEY" \
  -d '{"event_id": "evt_001", "game_id": "GAME_ID", "environment": "prod", "event_type": "session", "event_name": "session_start", "device_id": "device_123", "user_id": "user_456", "ts_client": 1700000000000}'
```

## Next Steps

- [API Reference](/reference/) - Explore the complete API
- [Operations](/operations/) - Deployment and operations guides

## Getting Help

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues) - Report bugs
- [Discussions](https://github.com/cuihairu/oddsmaker/discussions) - Ask questions
- [Documentation](/reference/) - Browse the docs
