#!/usr/bin/env bash
# arxael-dev-kit — deterministic, agent-independent acceptance smoke test.
#
# "start daemon -> /invoke a trivial build -> assert green", no matter how
# fancy the install path. This script is the contract. Exit 0 = green, non-zero = red.
#
# It proves four things end to end:
#   1. the daemon comes up and /health reports ready
#   2. the executor + /invoke path work        (noop adapter -> SUCCESS)
#   3. the warm gradle adapter runs a real build (gradle smoke -> SUCCESS + sentinel)
#   4. the arg-allowlist fails closed          (subversive flag -> REJECTED)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${ARXAEL_PORT:-8723}"
BASE="http://127.0.0.1:${PORT}"
GRADLE_HOME="${ARXAEL_GRADLE_HOME:-/opt/gradle/gradle-8.10.2}"
STATE_DIR="${ARXAEL_STATE_DIR:-$(mktemp -d)/arxael-smoke}"
FIXTURE="${REPO_ROOT}/fixtures/gradle-hello"
LAUNCHER="${REPO_ROOT}/core/build/install/core/bin/core"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; exit 1; }
info() { printf '[smoke] %s\n' "$*"; }

# JSON field extractor (python3 is a hard prereq on the box).
field() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

[ -x "${LAUNCHER}" ] || { info "building daemon first"; ( cd "${REPO_ROOT}" && ./gradlew :core:installDist --console=plain -q ); }

info "state dir: ${STATE_DIR}"
mkdir -p "${STATE_DIR}"

# --- start daemon ---
ARXAEL_PORT="${PORT}" ARXAEL_GRADLE_HOME="${GRADLE_HOME}" ARXAEL_STATE_DIR="${STATE_DIR}" \
  "${LAUNCHER}" >"${STATE_DIR}/daemon.log" 2>&1 &
DAEMON_PID=$!
cleanup() {
  curl -fsS -X POST "${BASE}/shutdown" >/dev/null 2>&1 || true
  sleep 0.3
  kill "${DAEMON_PID}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- 1. wait for /health ---
info "waiting for daemon /health on ${BASE}"
ready=""
for _ in $(seq 1 50); do
  if curl -fsS "${BASE}/health" >/dev/null 2>&1; then ready=1; break; fi
  sleep 0.2
done
[ -n "${ready}" ] || { cat "${STATE_DIR}/daemon.log"; fail "daemon did not become ready"; }
HEALTH="$(curl -fsS "${BASE}/health")"
[ "$(echo "${HEALTH}" | field ok)" = "True" ] && pass "health ok (${HEALTH})" || fail "health not ok: ${HEALTH}"

# --- 2. noop invoke ---
NOOP="$(curl -fsS -X POST "${BASE}/invoke" -d '{"adapter":"noop","worktree":"'"${REPO_ROOT}"'","tasks":["x"],"args":["sleepMs=10"],"agentId":"smoke"}')"
[ "$(echo "${NOOP}" | field status)" = "SUCCESS" ] && pass "noop SUCCESS" || fail "noop not SUCCESS: ${NOOP}"

# --- 3. real gradle build ---
info "running gradle smoke build (first run warms the connection)"
GRADLE="$(curl -fsS -X POST "${BASE}/invoke" -d '{"adapter":"gradle","worktree":"'"${FIXTURE}"'","tasks":["smoke"],"args":["--quiet"],"agentId":"smoke"}')"
GSTATUS="$(echo "${GRADLE}" | field status)"
GOUT="$(echo "${GRADLE}" | field output)"
if [ "${GSTATUS}" = "SUCCESS" ] && echo "${GOUT}" | grep -q "ARXAEL_SMOKE_OK"; then
  pass "gradle build SUCCESS (sentinel present, server=$(echo "${GRADLE}" | field server))"
else
  fail "gradle build failed: status=${GSTATUS} output=${GOUT}"
fi

# --- 4. allowlist fails closed ---
REJ="$(curl -fsS -o /dev/null -w '%{http_code}' -X POST "${BASE}/invoke" -d '{"adapter":"gradle","worktree":"'"${FIXTURE}"'","tasks":["smoke"],"args":["--gradle-user-home","/tmp/evil"]}' || true)"
[ "${REJ}" = "422" ] && pass "subversive arg REJECTED (HTTP 422)" || fail "allowlist did not reject subversive arg (HTTP ${REJ})"

# --- 5. native Prometheus /metrics ---
METRICS="$(curl -fsS "${BASE}/metrics")"
echo "${METRICS}" | grep -q '^arxael_up 1$' && pass "metrics expose arxael_up" || fail "no arxael_up in /metrics: ${METRICS}"
echo "${METRICS}" | grep -q '^# TYPE arxael_executor_max_concurrent gauge$' \
  && pass "metrics expose executor gauges" || fail "no executor gauge in /metrics"

# --- 6. multi-language adapters registered (logged at startup) ---
grep -q 'adapters=\[.*pytest.*cargo.*\]' "${STATE_DIR}/daemon.log" \
  && pass "multi-language adapters registered" || fail "language adapters missing from startup log"

printf '\n[smoke] \033[32mALL GREEN\033[0m\n'
