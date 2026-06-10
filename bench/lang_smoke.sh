#!/usr/bin/env bash
# Multi-language end-to-end proof: run REAL toolchains through the daemon's named adapters and assert the SPI
# maps results correctly (exit 0 -> SUCCESS, non-zero -> FAILED, missing binary -> ERROR).
#
# Proves "multi-language" is real, not just registered: HTTP -> executor -> CommandAdapter -> a real
# pytest/go/cargo/npm process -> result, identical to the gradle path but with no JVM. Each language section
# is skipped if its toolchain is absent, so the script is portable; on this box all four run.
set -euo pipefail

ROOT="${ARXAEL_GRADLE_HOME:-/opt/gradle/gradle-8.10.2}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCHER="${HERE}/core/build/install/core/bin/core"
PORT="${PORT:-8806}"
BASE="http://127.0.0.1:${PORT}"
WORK="$(mktemp -d)"
STATE="${WORK}/state"
RAN=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; cleanup; exit 1; }
cleanup() { curl -fsS -X POST "${BASE}/shutdown" >/dev/null 2>&1 || true; rm -rf "${WORK}" 2>/dev/null || true; }
trap cleanup EXIT
field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"; }

# adapter, worktree, expected-status, label  [, tasks-json]   (no tasks => the adapter's zero-config default)
expect() {
  local adapter="$1" wt="$2" want="$3" label="$4" tasks="${5:-[]}"
  local body resp got
  body='{"adapter":"'"${adapter}"'","worktree":"'"${wt}"'","tasks":'"${tasks}"',"agentId":"lang"}'
  resp="$(curl -sS -X POST "${BASE}/invoke" -d "${body}")"   # no -f: keep the body even on 500 so we can report it
  got="$(echo "${resp}" | field status)"
  RAN=$((RAN+1))
  [ "${got}" = "${want}" ] && pass "${label} -> ${got}" || fail "${label}: want ${want} got ${got} :: ${resp}"
}

[ -x "${LAUNCHER}" ] || { echo "build first: ./gradlew :core:installDist"; exit 1; }

# --- start the daemon ---
ARXAEL_PORT="${PORT}" ARXAEL_STATE_DIR="${STATE}" ARXAEL_CORES=4 ARXAEL_GRADLE_HOME="${ROOT}" \
  "${LAUNCHER}" > "${WORK}/daemon.log" 2>&1 &
for _ in $(seq 1 60); do curl -fsS "${BASE}/health" >/dev/null 2>&1 && break; sleep 0.25; done
curl -fsS "${BASE}/health" >/dev/null 2>&1 || { cat "${WORK}/daemon.log"; fail "daemon never ready"; }

# === pytest (Python) ===
if python3 -m pytest --version >/dev/null 2>&1; then
  PG="${WORK}/py-green"; PR="${WORK}/py-red"; mkdir -p "${PG}" "${PR}"
  printf 'def test_ok():\n    assert 1 + 1 == 2\n' > "${PG}/test_ok.py"
  printf 'def test_bad():\n    assert 1 + 1 == 3\n' > "${PR}/test_bad.py"
  expect pytest "${PG}" SUCCESS "pytest green (zero-config default cmd)"
  expect pytest "${PR}" FAILED  "pytest red"
else skip "pytest not installed"; fi

# === go ===
if command -v go >/dev/null 2>&1; then
  G="${WORK}/go"; mkdir -p "${G}"
  ( cd "${G}" && go mod init example >/dev/null 2>&1 )
  printf 'package main\nimport "testing"\nfunc TestOk(t *testing.T){ if 1+1!=2 {t.Fatal("no")} }\n' > "${G}/m_test.go"
  expect go "${G}" SUCCESS "go test green (zero-config default cmd)"
else skip "go not installed"; fi

# === cargo (Rust) ===
if command -v cargo >/dev/null 2>&1; then
  C="${WORK}/rs"
  ( cargo init --lib --quiet "${C}" >/dev/null 2>&1 )
  expect cargo "${C}" SUCCESS "cargo test green (default lib test)"
else skip "cargo not installed"; fi

# === npm (Node) ===
if command -v npm >/dev/null 2>&1; then
  N="${WORK}/node"; mkdir -p "${N}"
  printf '{"name":"x","version":"1.0.0","scripts":{"test":"node -e \\"process.exit(1+1===2?0:1)\\""}}\n' > "${N}/package.json"
  expect npm "${N}" SUCCESS "npm test green (package.json test script)"
else skip "npm not installed"; fi

# === missing binary -> ERROR (fail closed, any adapter) ===
E="$(curl -fsS -o /dev/null -w '%{http_code}' -X POST "${BASE}/invoke" -d '{"adapter":"exec","worktree":"'"${WORK}"'","tasks":["definitely-not-real-xyz"],"agentId":"lang"}' || true)"
[ "${E}" = "500" ] && pass "missing binary -> ERROR (HTTP 500, fail closed)" || fail "missing binary not ERROR: HTTP ${E}"

printf '\n[lang-smoke] \033[32mALL GREEN\033[0m — %d real-toolchain invocations proven end-to-end\n' "${RAN}"
