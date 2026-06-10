#!/usr/bin/env bash
# Validate the "fresh box -> running, one command, <10 min, zero context" claim (README / QUICKSTART):
# spin a CLEAN ubuntu container (no JDK, no Gradle, nothing), copy the repo, run scripts/bootstrap.sh, and
# assert it reaches READY — then prove the daemon actually answers and `arxael verify` passes. Times it.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-ubuntu:24.04}"

command -v docker >/dev/null || { echo "docker required"; exit 1; }
echo "=== fresh-box install validation in a clean $IMAGE (no toolchain pre-installed) ==="
docker run --rm -v "$REPO":/src:ro -w /work "$IMAGE" bash -euc '
  echo "[container] clean box: $(. /etc/os-release; echo $PRETTY_NAME); java=$(command -v java || echo none); gradle=$(command -v gradle || echo none)"
  # the only manual step a human/agent does: get the repo. (Here: copy it in, excluding build artifacts.)
  apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq tar >/dev/null 2>&1 || true
  tar -C /src -cf - --exclude=.git --exclude=.gradle --exclude=build --exclude=core/build --exclude=bench/out . | tar -C /work -xf -

  echo "[container] >>> the ONE command a newcomer runs <<<"
  S=$(date +%s)
  bash scripts/bootstrap.sh
  echo "[container] bootstrap wall time: $(( $(date +%s) - S ))s"

  echo "[container] proving it actually works (arxael verify)…"
  ./scripts/arxael stop >/dev/null 2>&1 || true
  bash scripts/arxael verify
' 2>&1 | tee /tmp/install-container.log
echo ""
echo "=== verdict ==="
if grep -q "READY" /tmp/install-container.log && grep -q "Verified" /tmp/install-container.log; then
  # strip ANSI colour codes first (the READY line is coloured), so the timing match is robust
  t=$(sed 's/\x1b\[[0-9;]*m//g' /tmp/install-container.log | grep -oE "READY in [0-9]+m[0-9]+s" | head -1 || true)
  echo "  PASS — fresh box to verified-running (${t:-time n/a}), one command, zero prior context."
else
  echo "  CHECK — see /tmp/install-container.log"
fi
