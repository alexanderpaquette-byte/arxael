#!/usr/bin/env bash
# Phase 0 re-baseline: with builds now parallelized (each pins the box), ramp UNCAPPED concurrency on
# all 32 cores and confirm we can drive the box to a real wall (CPU first on this RAM-rich box) — the
# saturation yesterday never reached because builds were throttled to ~1 worker.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase0/sat"; LOG="$OUT/sat.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24
: > "$OUT/sat.jsonl"
cell() {
  local ag=$1
  clean_room
  local rd="$OUT/a$ag"
  say "warm cores=32 agents=$ag workers=32 forks=2 (uncapped)"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores 32 --profile test \
    --cache-mode realistic --fixture "$FIX" --build-workers 32 --test-forks 2 \
    --run-dir "$rd" --out "$OUT/sat.jsonl" --warmup 20 --window 90 --task-timeout 600 \
    --port 8781 >>"$LOG" 2>&1 || say "  !! FAILED a$ag"
  trim_bundle "$rd"
  python3 - "$rd/result.json" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1]))
print("    -> goodput=%.1f/min p50=%.1fs ok=%d wedged=%d cpu=%.0f%% load1=%.0f ram=%.0f%% disk=%.0f%% daemons=%d"%(
  d["goodput_per_min"],d["p50_latency_s"],d["ok"],d["wedged"],d["avg_cpu_pct"],d.get("peak_load1",0),
  d["peak_mem_used_pct"],d["peak_disk_util_pct"],d["peak_gradle_daemons"]))
PY
}
say "=== Phase 0 saturation ramp START — free=$(disk_free_gb)GB ==="
for A in 1 2 4 8 16; do cell "$A"; done
clean_room
say "=== Phase 0 saturation ramp DONE ==="
