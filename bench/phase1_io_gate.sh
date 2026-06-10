#!/usr/bin/env bash
# Phase 1b — IO decision gate (the open question: does disk EVER bind on gp3?). DIFFERENT from
# yesterday: parallel builds (not throttled), gp3 (not io2), and a deliberately HIGH-IO config
# (coverage profile = test+jacoco reports, build-cache ON = every build stores cache → max writes).
# Run the SAME cell on gp3 vs a tmpfs ramdisk (≈ infinite IOPS/BW). If gp3 ≈ tmpfs, disk does NOT
# bind; if tmpfs >> gp3, it does. tmpfs steals RAM but the box has 246 GB, so a 32 GB ramdisk is safe.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/phase1/io"; LOG="$OUT/io.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
FIX=/tmp/bench-mm24
TMPFS=/mnt/arxtmpfs
: > "$OUT/io.jsonl"

# mount tmpfs (idempotent)
if ! mountpoint -q "$TMPFS" 2>/dev/null; then
  sudo mkdir -p "$TMPFS"
  sudo mount -t tmpfs -o size=32g tmpfs "$TMPFS" && sudo chown "$(id -u):$(id -g)" "$TMPFS"
  say "mounted tmpfs at $TMPFS"
fi

cell() {  # disk(gp3|tmpfs) agents
  local disk=$1 ag=$2
  clean_room
  local state="/tmp/arxbench"; [ "$disk" = tmpfs ] && state="$TMPFS/state"
  rm -rf "$state" 2>/dev/null || true
  local rd="$OUT/$disk-a$ag"
  say "warm c32 a$ag coverage stress(build-cache ON) disk=$disk state=$state"
  python3 "$HERE/bench.py" --arm warm --agents "$ag" --cores 32 --profile coverage \
    --cache-mode stress --fixture "$FIX" --build-workers 32 --test-forks 2 --state "$state" \
    --run-dir "$rd" --out "$OUT/io.jsonl" --warmup 20 --window 90 --task-timeout 600 \
    --port 8782 >>"$LOG" 2>&1 || say "  !! FAILED $disk a$ag"
  trim_bundle "$rd"
  python3 - "$rd/result.json" "$disk" <<'PY' 2>>"$LOG" || true
import json,sys
d=json.load(open(sys.argv[1]))
print("    -> [%s] goodput=%.1f/min p50=%.1fs cpu=%.0f%% disk_util=%.0f%% wr_iops=%.0f wr_mbps=%.1f wedged=%d"%(
  sys.argv[2],d["goodput_per_min"],d["p50_latency_s"],d["avg_cpu_pct"],d["peak_disk_util_pct"],
  d.get("peak_write_iops",0),d.get("peak_wr_mbps",0),d["wedged"]))
PY
}

say "=== Phase 1b IO gate START — free=$(disk_free_gb)GB ==="
for A in 8 16; do
  cell gp3   "$A"
  cell tmpfs "$A"
done
clean_room
say "=== Phase 1b IO gate DONE (compare gp3 vs tmpfs goodput; ≈ means disk never binds) ==="
