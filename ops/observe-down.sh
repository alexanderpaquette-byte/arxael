#!/usr/bin/env bash
# observe-down.sh — stop + remove the Prometheus + Grafana containers (leaves the arxael daemon running).
# Data volumes are KEPT by default (history survives a restart); pass --purge to also drop them.
set -euo pipefail
docker rm -f arxael-prom arxael-grafana >/dev/null 2>&1 || true
if [ "${1:-}" = "--purge" ]; then
  docker volume rm arxael-prom-data arxael-grafana-data >/dev/null 2>&1 || true
  echo "✓ observability stack down + data volumes purged."
else
  echo "✓ observability stack down (data volumes kept; ops/observe-down.sh --purge to drop them)."
fi
