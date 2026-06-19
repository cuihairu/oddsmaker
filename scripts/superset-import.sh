#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

BUNDLE="bi/superset/oddsmaker_dashboard_bundle.zip"
# Always rebuild bundle to pick up latest datasets/charts/dashboards
(cd bi/superset && ./build_bundle.sh)

cd infra
CID=$(docker compose ps -q superset || true)
if [ -z "$CID" ]; then echo "Superset container not found; ensure 'docker compose up -d superset'"; exit 1; fi

# Initialize superset if needed (idempotent)
echo "Initializing Superset (admin/admin) if needed..." >&2
docker compose exec -T superset bash -lc "superset fab create-admin --username admin --firstname Admin --lastname User --email admin@oddsmaker.local --password admin || true"
docker compose exec -T superset bash -lc "superset db upgrade || true"
docker compose exec -T superset bash -lc "superset init || true"

# Copy bundle and import
echo "Copying bundle and importing..." >&2
docker cp "../$BUNDLE" "$CID":/app/import.zip
# Try import-entities if available, fall back to import-dashboards
docker compose exec -T superset bash -lc "superset import-entities --path /app/import.zip || superset import-dashboards -p /app/import.zip"
echo "Imported bundle into Superset." >&2
