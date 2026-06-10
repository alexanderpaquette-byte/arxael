#!/usr/bin/env bash
# Real-world validation against a real GRADLE OSS project (ben-manes/caffeine) in an isolated container,
# gated via the project's OWN gradle wrapper (the gradlew adapter). Complements realworld_oss.sh (gson/Maven).
# Usage: bash bench/realworld_caffeine.sh [agents] [cores] [window_s]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="gradle:8.10.2-jdk21"
AGENTS="${1:-6}"; CORES="${2:-8}"; WINDOW="${3:-300}"
command -v docker >/dev/null || { echo "docker required"; exit 1; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE"

echo "=== REAL gradle OSS (caffeine) — ${AGENTS} agents / ${CORES} cores / ${WINDOW}s ==="
docker run --rm -v "$REPO":/src:ro -w /work --cpus="${CORES}" -e RW_CORES="${CORES}" "$IMAGE" bash -euc '
  tar -C /src -cf - --exclude=.git --exclude=.gradle --exclude=build --exclude=core/build --exclude=bench/out . | tar -C /work -xf -
  export ARXAEL_GRADLE_HOME=/opt/gradle
  echo "[container] building the kit…"; gradle -q :core:installDist 2>&1 | tail -2
  echo "[container] cloning caffeine…"; git clone --depth 1 -q https://github.com/ben-manes/caffeine.git /tmp/caffeine
  echo "[container] baseline: warm caffeine via its OWN wrapper (downloads gradle 9.6 + deps, compiles)…"
  ( cd /tmp/caffeine && ./gradlew :caffeine:compileTestJava --console=plain -q >/tmp/baseline.log 2>&1 && echo "[container] caffeine baseline: GREEN" ) \
    || { echo "[container] baseline FAILED:"; tail -20 /tmp/baseline.log; exit 1; }
  echo "[container] running the agent fleet against real caffeine…"
  python3 bench/realworld_caffeine.py /tmp/caffeine '"$AGENTS"' '"$WINDOW"'
' 2>&1 | tee /tmp/realworld-caffeine.log
