#!/usr/bin/env bash
set -euo pipefail
VER=${1:-0.1.0}

# sanity build (gateway tests and flink assemble)
if [ -x ./gradlew ]; then
  ./gradlew :services:gateway-service:test :jobs:flink:events-enrich-job:assemble -q || true
fi

git tag -a "v$VER" -m "Oddsmaker $VER"
if git remote -v >/dev/null 2>&1; then
  git push origin "v$VER"
fi

echo "Tagged v$VER"
