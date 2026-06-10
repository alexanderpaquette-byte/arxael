#!/usr/bin/env bash
# observe-up.sh — stand up Prometheus + Grafana (Docker) watching the local arxael daemon's /metrics, with
# the bundled dashboard auto-loaded. The daemon binds loopback only, so watch it through an SSH tunnel.
#
#   ops/observe-up.sh                      # scrapes ARXAEL_PORT / ~/.arxael/port / 8723
#   on YOUR machine:  ssh -L 3000:localhost:3000 <user>@<box>   ->  http://localhost:3000
#   tear down:  ops/observe-down.sh
#
# Reliable by construction: pinned image versions, restart=unless-stopped (survives reboot/docker restart),
# persistent Prometheus + Grafana volumes, pre-flight checks, and it WAITS + verifies both came up healthy.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${ARXAEL_PORT:-$(cat "${HOME}/.arxael/port" 2>/dev/null || echo 8723)}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"; PROM_PORT="${PROM_PORT:-9090}"
# Pinned to verified-working versions (not :latest) so the stack is reproducible and can't drift under us.
PROM_IMG="${PROM_IMG:-prom/prometheus:v3.12.0}"; GRAFANA_IMG="${GRAFANA_IMG:-grafana/grafana:13.0.2}"
WORK="${HOME}/.arxael/observe"

err(){ printf '\e[31m✗ %s\e[0m\n' "$*" >&2; }; ok(){ printf '\e[32m✓ %s\e[0m\n' "$*"; }; warn(){ printf '\e[33m⚠ %s\e[0m\n' "$*"; }

# ---- pre-flight ----
command -v docker >/dev/null 2>&1 || { err "docker not found"; exit 1; }
docker info >/dev/null 2>&1 || { err "docker daemon not reachable (is it running? are you in the docker group?)"; exit 1; }
if ! curl -fsS -m2 "127.0.0.1:${PORT}/metrics" >/dev/null 2>&1; then
  warn "no daemon answering /metrics on 127.0.0.1:${PORT} — starting the stack anyway (it'll show 'no data' until the daemon is up)."
fi
# Remove our own containers up-front (we always recreate them) so the port check below only trips on a
# FOREIGN listener. Host-network containers don't show ports in `docker ps`, so we can't tell "ours" apart
# by port — removing first sidesteps that entirely.
docker rm -f arxael-prom arxael-grafana >/dev/null 2>&1 || true
for p in "$GRAFANA_PORT" "$PROM_PORT"; do
  ss -tlnH "sport = :$p" 2>/dev/null | grep -q . && { err "port $p already in use (by something other than this stack) — set GRAFANA_PORT/PROM_PORT to free ones"; exit 1; }
done

mkdir -p "$WORK/provisioning/datasources" "$WORK/provisioning/dashboards" "$WORK/dashboards"

cat > "$WORK/prometheus.yml" <<YML
global: { scrape_interval: 5s, scrape_timeout: 4s }
scrape_configs:
  - job_name: arxael
    metrics_path: /metrics
    static_configs: [ { targets: ["127.0.0.1:${PORT}"], labels: { service: arxael } } ]
YML

cat > "$WORK/provisioning/datasources/prometheus.yml" <<YML
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://localhost:${PROM_PORT}
    isDefault: true
YML

cat > "$WORK/provisioning/dashboards/provider.yml" <<'YML'
apiVersion: 1
providers:
  - name: arxael
    type: file
    options: { path: /var/lib/grafana/dashboards }
YML

# Transform the bundled dashboard for provisioning: drop the import-wizard __inputs, pin the datasource uid.
python3 - "$HERE/grafana-dashboard.json" "$WORK/dashboards/arxael.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d.pop("__inputs", None)
open(sys.argv[2], "w").write(json.dumps(d).replace("${DS_PROMETHEUS}", "prometheus"))
PY

docker volume create arxael-prom-data >/dev/null; docker volume create arxael-grafana-data >/dev/null

docker run -d --name arxael-prom --network host --restart unless-stopped \
  -v "$WORK/prometheus.yml:/etc/prometheus/prometheus.yml:ro" -v arxael-prom-data:/prometheus \
  "$PROM_IMG" \
  --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=15d --web.listen-address="127.0.0.1:${PROM_PORT}" >/dev/null

docker run -d --name arxael-grafana --network host --restart unless-stopped \
  -e GF_SERVER_HTTP_ADDR=127.0.0.1 -e GF_SERVER_HTTP_PORT="${GRAFANA_PORT}" \
  -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer \
  -e GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/var/lib/grafana/dashboards/arxael.json \
  -e GF_ANALYTICS_REPORTING_ENABLED=false -e GF_ANALYTICS_CHECK_FOR_UPDATES=false \
  -v "$WORK/provisioning:/etc/grafana/provisioning:ro" -v "$WORK/dashboards:/var/lib/grafana/dashboards:ro" \
  -v arxael-grafana-data:/var/lib/grafana "$GRAFANA_IMG" >/dev/null

# ---- wait + verify both are actually serving (don't fire-and-forget) ----
waitfor(){ local n="$1" url="$2"; for _ in $(seq 1 60); do curl -fsS -m2 "$url" >/dev/null 2>&1 && { ok "$n healthy"; return 0; }; sleep 1; done; err "$n did not become healthy in 60s (see: docker logs ${3:-})"; return 1; }
rc=0
waitfor "Prometheus :${PROM_PORT}" "http://127.0.0.1:${PROM_PORT}/-/healthy" arxael-prom || rc=1
waitfor "Grafana :${GRAFANA_PORT}" "http://127.0.0.1:${GRAFANA_PORT}/api/health" arxael-grafana || rc=1
# confirm Prometheus is actually scraping the daemon (the whole point)
sleep 6
tgt="$(curl -s "http://127.0.0.1:${PROM_PORT}/api/v1/targets" 2>/dev/null | python3 -c "import sys,json;t=json.load(sys.stdin)['data']['activeTargets'];print(t[0]['health'] if t else 'none')" 2>/dev/null || echo '?')"
[ "$tgt" = "up" ] && ok "Prometheus is scraping the daemon (target up)" || warn "Prometheus target = '$tgt' (the daemon may not be running yet — start it, the scrape recovers on its own)"

echo
ok "Stack up (pinned: $PROM_IMG, $GRAFANA_IMG; restart=unless-stopped; data persisted)."
echo "  Watch it from your machine:  ssh -L ${GRAFANA_PORT}:localhost:${GRAFANA_PORT} <user>@<box>  →  http://localhost:${GRAFANA_PORT}"
echo "  (anonymous read-only over the tunnel; admin/admin to edit. Tear down: ops/observe-down.sh)"
exit $rc
