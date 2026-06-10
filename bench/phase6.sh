#!/usr/bin/env bash
# Phase 6 — the three studies that close the headline (no disk dependency):
#   6A warm's-own-collapse : ramp warm FAR past container's collapse point under the same 24GB cap
#                            -> show warm has no OOM cliff (plateaus goodput, sheds via 503).
#   6B churn               : container fresh-cold-per-task vs warm -> the cold-start tax.
#   6C open-loop           : Poisson arrivals vs capacity -> graceful 503 shedding + queue-wait.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/campaign"; LOG="$OUT/phase6.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

cell() {  # results_file arm agents extra...
  local res=$1 arm=$2 ag=$3; shift 3
  guard_disk || return 0
  clean_room
  local rd="$OUT/p6-$arm-a$ag-$(echo "$*" | tr ' /=' '___')"
  say "  $arm agents=$ag $*"
  python3 "$HERE/bench.py" --arm "$arm" --agents "$ag" --cores 16 --profile test \
    --classes 150 --fixture /tmp/bench-test --run-dir "$rd" --out "$res" "$@" >>"$LOG" 2>&1 \
    || say "    !! FAILED"
  trim_bundle "$rd"
}

python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-test --classes 150 >>"$LOG" 2>&1

# 6A — warm's own collapse (capped 24GB, ramp high). Container collapsed at ~24 in P1; warm should ride it.
say "PHASE 6A: warm's-own-collapse (capped 24GB, cores=16)"
R="$OUT/p6a-warm-collapse.jsonl"; : >"$R"
for A in 32 48 64 96 128; do cell "$R" warm "$A" --mem-gb 24 --warmup 30 --window 45 --no-prewarm; done

# 6B — cold-start tax: container churns a fresh cold daemon per task; warm stays warm. Uncapped.
say "PHASE 6B: churn / cold-start tax (uncapped, cores=16)"
R="$OUT/p6b-churn.jsonl"; : >"$R"
for A in 1 4 8 16 32; do
  cell "$R" container "$A" --churn --warmup 15 --window 45
  cell "$R" warm "$A" --warmup 15 --window 45
done

# 6C — open-loop arrivals vs capacity (capped 24GB so warm is bounded and must shed gracefully).
say "PHASE 6C: open-loop arrival-rate sweep (capped 24GB, cores=16, pool=32)"
R="$OUT/p6c-openloop.jsonl"; : >"$R"
for RATE in 1 2 4 8 16; do
  cell "$R" warm 32 --mem-gb 24 --rate "$RATE" --warmup 15 --window 45 --no-prewarm
  cell "$R" container 32 --mem-gb 24 --rate "$RATE" --warmup 15 --window 45 --no-prewarm
done

clean_room
say "=== PHASE 6 DONE ==="
