#!/usr/bin/env bash
# Real-world validation against an ACTUAL OSS project (google/gson) in an isolated container — the honest
# answer to "it's only been tested on synthetic fixtures." Many agents add real compiling tests to gson's
# core module and submit PRs; the real MergeOrchestrator gates each batch with gson's OWN `mvn` test suite
# (~35s real build) and lands the green ones; deliberately-broken ones are caught. main must stay green.
#
# Usage: bash bench/realworld_oss.sh [agents] [cores] [window_s]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="gradle:8.10.2-jdk21"
AGENTS="${1:-16}"; CORES="${2:-12}"; WINDOW="${3:-360}"
PROJECT_URL="${PROJECT_URL:-https://github.com/google/gson.git}"

command -v docker >/dev/null || { echo "docker required"; exit 1; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE"

echo "=== REAL OSS run: $PROJECT_URL — ${AGENTS} agents / ${CORES} cores / ${WINDOW}s ==="
docker run --rm -v "$REPO":/src:ro -w /work --cpus="${CORES}" -e RW_CORES="${CORES}" "$IMAGE" bash -euc '
  echo "[container] installing maven + copying kit source…"
  apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq maven >/dev/null 2>&1
  tar -C /src -cf - --exclude=.git --exclude=.gradle --exclude=build --exclude=core/build --exclude=bench/out . | tar -C /work -xf -
  export ARXAEL_GRADLE_HOME=/opt/gradle

  echo "[container] building the kit from source…"
  gradle -q :core:installDist 2>&1 | tail -2
  test -x /work/core/build/install/core/bin/core && echo "[container] kit built OK"

  echo "[container] cloning the REAL project: '"$PROJECT_URL"'"
  git clone --depth 1 -q "'"$PROJECT_URL"'" /tmp/gson
  echo "[container] baseline: prove the real project builds+tests here before the fleet…"
  ( cd /tmp/gson && mvn -q -pl gson -am test >/tmp/baseline.log 2>&1 && echo "[container] baseline gson mvn test: GREEN" ) \
    || { echo "[container] baseline build FAILED — see tail:"; tail -20 /tmp/baseline.log; exit 1; }

  echo "[container] running the agent fleet against the real gson repo…"
  python3 bench/realworld_oss.py /tmp/gson '"$AGENTS"' '"$WINDOW"'
' 2>&1 | tee /tmp/realworld-oss.log
