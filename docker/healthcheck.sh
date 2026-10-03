#!/bin/sh
set -e
PORT="${HEALTHCHECK_PORT:-8085}"
wget --no-verbose --tries=1 -qO- "http://localhost:${PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1