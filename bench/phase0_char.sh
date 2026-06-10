#!/usr/bin/env bash
# Phase 0 — characterize the cache-lock ceiling on the DE-CONFOUNDED harness (realistic multi-module
# fixture, warmServers=cores, decoupled cache-mode, generous acquire timeout). Find where the warm
# shared-user-home cache-lock chokepoint actually bites, and isolate build-cache-store contribution
# (realistic = build-cache OFF) vs (stress = build-cache ON). NOT a "fix" run — a measurement run.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase0/char"; LOG="$OUT/char.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24            # 24 modules x 40 classes ~= 24s single warm build
PORT=8770

cell() {  # mode agents
  local mode=$1 ag=$2
  guard_disk || return 0
  clean_room
  local rd="$OUT/$mode-c16-a$ag"
  say "warm cores=16 agents=$ag cache=$mode"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores 16 --profile test \
    --cache-mode "$mode" --fixture "$FIX" --run-dir "$rd" --out "$OUT/$mode.jsonl" \
    --warmup 60 --window 180 --task-timeout 600 --port "$PORT" >>"$LOG" 2>&1 || say "  !! FAILED a$ag"
  trim_bundle "$rd"
  # quick one-line readout per cell
  python3 - "$rd/result.json" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1]))
print("    -> goodput=%.1f/min p50=%.1fs p95=%.1fs wedged=%d cpu=%.0f%% ram=%.0f%% disk=%.0f%% daemons=%d"%(
  d["goodput_per_min"],d["p50_latency_s"],d["p95_latency_s"],d["wedged"],
  d["avg_cpu_pct"],d["peak_mem_used_pct"],d["peak_disk_util_pct"],d["peak_gradle_daemons"]))
PY
}

say "=== Phase 0 characterization START — free=$(disk_free_gb)GB ==="
: > "$OUT/realistic.jsonl"; : > "$OUT/stress.jsonl"
say "--- realistic ramp (build-cache OFF): the de-confounded cache-lock ceiling ---"
for A in 4 8 16 24 32 48; do cell realistic "$A"; done
say "--- stress comparison (build-cache ON) at knee region ---"
for A in 16 32; do cell stress "$A"; done
clean_room
say "=== Phase 0 characterization DONE — free=$(disk_free_gb)GB ==="
