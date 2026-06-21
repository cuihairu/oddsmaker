# Getting Started

Welcome to Oddsmaker, the professional gaming analytics platform for single company operating multiple games.

## Overview

Oddsmaker provides:

- **Multi-Game Architecture**: Manage multiple games with isolated environments
- **Real-time Analytics**: Process millions of events with Kafka + Flink + ClickHouse
- **Risk Control**: Detect and prevent cheating and fraud
- **A/B Testing**: Run experiments with statistical analysis
- **Machine Learning**: Deploy ML models for predictions
- **Enterprise Security**: MFA, SSO, RBAC, and audit logging

## Prerequisites

Before getting started, ensure you have:

- Java 21
- Gradle 8.10+ or 9.x installed locally
- Docker and Docker Compose
- PostgreSQL 16+
- Redis 7+
- Kafka (optional, for real-time processing)

## Quick Start

### Option 1: Docker Compose (Recommended)

```bash
# Clone the repository
git clone https://github.com/cuihairu/oddsmaker.git
cd oddsmaker

# Start local infrastructure
docker-compose -f infra/docker-compose.yml up -d

# Verify services are running
docker-compose ps

# Check health
curl http://localhost:8085/actuator/health
```

### Option 2: Local Development

```bash
# Start dependencies
docker-compose -f infra/docker-compose.yml up -d kafka clickhouse apicurio

# Build the project
gradle :services:control-service:bootJar

# Run the application
gradle :services:control-service:bootRun
```

## First Steps

### 1. Create a Game

```bash
curl -X POST http://localhost:8085/api/games \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "name": "My Game",
    "genre": "rpg",
    "platforms": ["android", "ios"],
    "timezone": "UTC",
    "defaultCurrency": "USD"
  }'
```

### 2. Create an Environment

```bash
curl -X POST http://localhost:8085/api/games/GAME_ID/environments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "name": "production",
    "type": "PRODUCTION",
    "displayName": "Production Environment"
  }'
```

### 3. Create an API Key

```bash
curl -X POST http://localhost:8085/api/api-keys \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "gameId": "GAME_ID",
    "environmentId": "ENV_ID",
    "name": "android-client",
    "keyType": "client"
  }'
```

### 4. Send Events

```bash
curl -X POST http://localhost:8085/v1/batch \
  -H "Content-Type: application/json" \
  -H "x-api-key: YOUR_API_KEY" \
  -d '[
    {
      "event_id": "evt_001",
      "game_id": "GAME_ID",
      "environment": "production",
      "event_type": "session",
      "event_name": "session:start",
      "device_id": "device_123",
      "user_id": "user_456",
      "ts_client": 1700000000000
    }
  ]'
```

## Next Steps

- [API Reference](/reference/) - Explore the complete API
- [Operations](/operations/) - Deployment and operations guides

## Getting Help

- [GitHub Issues](https://github.com/cuihairu/oddsmaker/issues) - Report bugs
- [Discussions](https://github.com/cuihairu/oddsmaker/discussions) - Ask questions
- [Documentation](/reference/) - Browse the docs
