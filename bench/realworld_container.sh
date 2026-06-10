#!/usr/bin/env bash
# Real-world validation in an ISOLATED container (addresses LIMITATIONS #5: "validated only on synthetic
# setups on one box"). This:
#   1. spins a clean gradle:8.10.2-jdk21 container (nothing of this box leaks in),
#   2. BUILDS the kit from source inside it (validates the from-scratch build/install path),
#   3. starts the daemon with the shipped defaults (per-worktree home + self-warming shared dep cache),
#   4. runs a fleet of agents that make real edits to a real multi-module Gradle project (real Maven Central
#      dependency downloads on FRESH per-worktree gradle homes — the actual 429 scenario), branch-gate, and
#      submit PRs through the real MergeOrchestrator,
#   5. reports merges/min, reverts, errors — main must never break.
#
# Usage: bash bench/realworld_container.sh [agents] [cores] [window_s]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="gradle:8.10.2-jdk21"
AGENTS="${1:-8}"; CORES="${2:-8}"; WINDOW="${3:-90}"

command -v docker >/dev/null || { echo "docker required"; exit 1; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "pulling $IMAGE…"; docker pull "$IMAGE"; }

echo "=== isolated container real-world run: ${AGENTS} agents / ${CORES} cores / ${WINDOW}s ==="
docker run --rm -v "$REPO":/src:ro -w /work --cpus="${CORES}" "$IMAGE" bash -euc '
  echo "[container] copying source (excluding build artifacts + .git)…"
  tar -C /src -cf - --exclude=.git --exclude=.gradle --exclude=build --exclude=core/build --exclude=bench/out . | tar -C /work -xf -
  export ARXAEL_GRADLE_HOME=/opt/gradle

  echo "[container] building the kit from source (clean box)…"
  gradle -q :core:installDist 2>&1 | tail -3
  test -x /work/core/build/install/core/bin/core && echo "[container] kit built OK"

  echo "[container] generating a real multi-module Gradle project (8 modules, wide DAG, real JUnit5 deps)…"
  python3 bench/fixtures/gen_fixture.py /tmp/wide-dag --modules 8 --classes 30 --methods 3 --fanin 3

  echo "[container] running the agent fleet (fresh per-worktree gradle homes -> REAL Maven Central downloads)…"
  python3 bench/merge_http_load.py --auto-warm --fixture /tmp/wide-dag \
    --agents '"$AGENTS"' --cores '"$CORES"' --max-concurrent '"$CORES"' --reserved-high 2 \
    --window '"$WINDOW"' --threshold 4 --modules 8 --methods 3 \
    --port 8796 --root /tmp/rw --out /tmp/rw/result.jsonl
' 2>&1 | tee /tmp/realworld-container.log

echo ""
echo "=== verdict ==="
python3 - <<'PY'
import json
try:
    d = json.loads(open("/tmp/rw-host-result.jsonl").read().splitlines()[-1])
except Exception:
    # the result is inside the container log (printed json block); parse the merges_per_min line
    import re
    txt = open("/tmp/realworld-container.log").read()
    m = re.search(r'"merges_per_min":\s*([\d.]+)', txt)
    r = re.search(r'"reverts":\s*(\d+)', txt); e = re.search(r'"errors":\s*(\d+)', txt)
    l = re.search(r'"landed":\s*(\d+)', txt)
    if m:
        print(f"  landed={l.group(1) if l else '?'}  merges/min={m.group(1)}  "
              f"reverts={r.group(1) if r else '?'}  errors={e.group(1) if e else '?'}")
        ok = (r and int(r.group(1)) == 0) and (e and int(e.group(1)) == 0) and (l and int(l.group(1)) > 0)
        print("  " + ("\033[32mPASS\033[0m real project landed PRs, main never broke, no errors"
                      if ok else "\033[31mCHECK\033[0m see log above"))
    else:
        print("  no result parsed — see /tmp/realworld-container.log")
PY
