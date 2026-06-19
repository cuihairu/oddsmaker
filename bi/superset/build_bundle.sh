#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/bundle"
zip -r ../oddsmaker_dashboard_bundle.zip .
echo "Created: bi/superset/oddsmaker_dashboard_bundle.zip"
