#!/usr/bin/env bash
# Phase 3 — the TRUE-CEILING run (the suite's headline question): how many concurrent builds can the
# whole UNCAPPED box hold, and where does it actually fall over? Bound lifted (agentsPerCore=64 ->
# maxConcurrent effectively unbounded), warmServers high, NO --mem-gb (full 246 GB), all 32 cores.
# Light per-build (workers=2, forks=1) so we push CONCURRENCY/MEMORY to the wall, not CPU-thrash on
# one build. Ramp agents to collapse and record which resource gives first (RAM-fill/OOM vs wedge).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase3/ceiling"; LOG="$OUT/ceiling.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24
: > "$OUT/ceiling.jsonl"

cell() {  # agents
  local ag=$1
  guard_disk || { say "ABORT: low disk"; return 0; }
  clean_room
  sudo dmesg -C 2>/dev/null || true   # clear kernel ring so we can detect OOM-kills from THIS cell
  local rd="$OUT/a$ag"
  say "warm UNCAPPED cores=32 agents=$ag workers=2 (bound lifted, full 246GB)"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores 32 --profile test \
    --cache-mode realistic --fixture "$FIX" --build-workers 2 --test-forks 1 \
    --agents-per-core 64 --warm-servers 512 --run-dir "$rd" --out "$OUT/ceiling.jsonl" \
    --warmup 30 --window 120 --task-timeout 900 --no-prewarm --port 8785 >>"$LOG" 2>&1 || say "  !! FAILED a$ag"
  trim_bundle "$rd"
  local oom; oom=$(sudo dmesg 2>/dev/null | grep -ci 'out of memory\|oom-kill\|killed process' || echo 0)
  python3 - "$rd/result.json" "$oom" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1]))
print("    -> a%d goodput=%.1f/min p50=%.0fs ok=%d wedged=%d peakRAM=%dMB(%.0f%%) swap=%dMB jvms=%d daemons=%d load1=%.0f cpu=%.0f%% oom_lines=%s"%(
  d["agents"],d["goodput_per_min"],d["p50_latency_s"],d["ok"],d["wedged"],
  d.get("peak_mem_used_mb",0),d.get("peak_mem_used_pct",0),d.get("peak_swap_used_mb",0),
  d.get("peak_jvm_count",0),d["peak_gradle_daemons"],d.get("peak_load1",0),d["avg_cpu_pct"],sys.argv[2]))
PY
}

say "=== Phase 3 TRUE-CEILING ramp START — free=$(disk_free_gb)GB total_ram=$(free -g|awk '/Mem/{print $2}')GB ==="
for A in 32 64 128 256; do cell "$A"; done
clean_room
say "=== Phase 3 DONE ==="
