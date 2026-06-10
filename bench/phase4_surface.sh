#!/usr/bin/env bash
# Phase 4a — warm scaling surface (model-tuning deliverable). Sweep modeled box sizes (--mem-gb sets
# the executor's memBound, the product's self-limit) x cores, with PARALLEL builds, pushing agents
# past the bound. Key outputs per cell: sustainable goodput, binding constraint (cores vs memory),
# and PEAK RAM vs the modeled budget — i.e. does memBound actually keep the box under budget,
# or is perBuildFootprint (default 1 GB) too optimistic now that each build parallelizes (more JVMs)?
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase4/surface"; LOG="$OUT/surface.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24
: > "$OUT/surface.jsonl"

cell() {  # cores mem_gb agents
  local c=$1 mem=$2 ag=$3
  clean_room
  local rd="$OUT/c${c}-m${mem}-a${ag}"
  say "warm cores=$c mem_gb=$mem agents=$ag (parallel builds)"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores "$c" --profile test \
    --cache-mode realistic --fixture "$FIX" --build-workers "$c" --test-forks 2 \
    --mem-gb "$mem" --run-dir "$rd" --out "$OUT/surface.jsonl" \
    --warmup 20 --window 90 --task-timeout 600 --port 8784 >>"$LOG" 2>&1 || say "  !! FAILED c$c m$mem a$ag"
  trim_bundle "$rd"
  python3 - "$rd/result.json" "$mem" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1])); budget=float(sys.argv[2])*1024
peak=d.get("peak_mem_used_mb",0)
print("    -> goodput=%.1f/min p50=%.1fs wedged=%d cpu=%.0f%% peakRAM=%dMB / budget=%dMB (%s) daemons=%d"%(
  d["goodput_per_min"],d["p50_latency_s"],d["wedged"],d["avg_cpu_pct"],peak,budget,
  "OVER" if peak>budget else "under",d["peak_gradle_daemons"]))
PY
}

say "=== Phase 4a warm scaling surface START — free=$(disk_free_gb)GB ==="
# push agents past the modeled memBound so the bound is actually exercised
for C in 16 32; do
  for MEM in 8 16 32; do
    cell "$C" "$MEM" $(( C * 2 ))
  done
done
clean_room
say "=== Phase 4a DONE ==="
