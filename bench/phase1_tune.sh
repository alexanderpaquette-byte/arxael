#!/usr/bin/env bash
# Gradle-tuning sweep: with the shared-cache lock removed (per-worktree home), find the optimal SPLIT
# of concurrency x per-build-workers for max test throughput on this 32-core box. Hold total worker-
# slots ~= cores (avoid the oversubscription thrash that gave 0 goodput at 128xparallel); vary the
# split. The winning split + cores generalizes the box-adaptive model (best perf per machine type).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase1/tune"; LOG="$OUT/tune.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24
: > "$OUT/tune.jsonl"

cell() {  # agents workers label
  local ag=$1 w=$2 label=$3
  guard_disk || return 0
  clean_room
  local rd="$OUT/$label"
  say "per-home cores=32 agents=$ag workers=$w ($label, ~$((ag*w)) slots)"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores 32 --profile test \
    --cache-mode realistic --per-worktree-home --fixture "$FIX" --build-workers "$w" --test-forks 1 \
    --run-dir "$rd" --out "$OUT/tune.jsonl" --warmup 45 --window 120 --task-timeout 600 \
    --port 8788 >>"$LOG" 2>&1 || say "  !! FAILED $label"
  trim_bundle "$rd"
  python3 - "$rd/result.json" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1]))
print("    -> goodput=%.1f/min p50=%.1fs ok=%d wedged=%d cpu=%.0f%% ram=%.0f%% daemons=%d"%(
  d["goodput_per_min"],d["p50_latency_s"],d["ok"],d["wedged"],d["avg_cpu_pct"],
  d["peak_mem_used_pct"],d["peak_gradle_daemons"]))
PY
}

say "=== gradle-tuning split sweep START (per-home) — free=$(disk_free_gb)GB ==="
# split of ~32 worker-slots: more-concurrency<->more-workers-per-build
cell 4  8 "a4-w8"
cell 8  4 "a8-w4"
cell 16 2 "a16-w2"
cell 32 1 "a32-w1"
# oversubscription reference (2x slots) to confirm the penalty
cell 32 2 "a32-w2-over"
clean_room
say "=== gradle-tuning split sweep DONE ==="
