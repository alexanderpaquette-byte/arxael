#!/usr/bin/env bash
# Master runner for the remaining phases after the disk-fill incident reset us mid-campaign.
# P1-P3 results survived; this re-does P4 (it was partial) then runs P5, P6, and the disk study P7
# LAST (by which point io2 is fully optimized). Hardened clean_room (rm -fv + volume prune) prevents
# the anonymous-volume leak that filled the disk.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"
OUT="$REPO/bench/out/campaign"; LOG="$OUT/remaining.log"; mkdir -p "$OUT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

cell() {  # results arm agents profile classes warmup window extra...
  local res=$1 arm=$2 ag=$3 prof=$4 cls=$5 wu=$6 win=$7; shift 7
  guard_disk || return 0
  clean_room
  local rd="$OUT/rem-$prof-$arm-a$ag-$(echo "$*" | tr ' /=' '___')"
  say "  $arm agents=$ag prof=$prof $*"
  python3 "$HERE/bench.py" --arm "$arm" --agents "$ag" --cores 16 --profile "$prof" \
    --classes "$cls" --fixture "/tmp/bench-$prof" --run-dir "$rd" --out "$res" \
    --warmup "$wu" --window "$win" "$@" >>"$LOG" 2>&1 || say "    !! FAILED"
  trim_bundle "$rd"
}

say "=== REMAINING CAMPAIGN START (post-incident) — free=$(disk_free_gb)GB ==="

# ---- P4 redo: mutation capped collapse (was partial) ----
say "P4: mutation capped collapse (cores=16, mem=24GB, 40 classes)"
python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-mutation --classes 40 >>"$LOG" 2>&1
R="$OUT/p4-mutation-cap.jsonl"; : >"$R"
for A in 1 2 4 8 16 24; do
  for ARM in warm container; do cell "$R" "$ARM" "$A" mutation 40 35 90 --mem-gb 24; done
done
python3 "$HERE/analyze.py" "$R" >>"$LOG" 2>&1 || true

# ---- P5: agentsPerCore tuning (cores=32, agents=96, uncapped) ----
say "P5: agentsPerCore tuning sweep (cores=32, agents=96)"
python3 "$HERE/fixtures/gen_fixture.py" /tmp/bench-test --classes 150 >>"$LOG" 2>&1
R="$OUT/p5-tuning.jsonl"; : >"$R"
for APC in 1 1.5 2 3; do
  guard_disk || break; clean_room
  rd="$OUT/p5-warm-apc$APC"
  say "  warm cores=32 agents=96 apc=$APC"
  python3 "$HERE/bench.py" --arm warm --agents 96 --cores 32 --profile test --classes 150 \
    --fixture /tmp/bench-test --run-dir "$rd" --out "$R" --warmup 20 --window 45 \
    --agents-per-core "$APC" --no-prewarm >>"$LOG" 2>&1 || say "    !! FAILED"
  trim_bundle "$rd"
done
clean_room
python3 "$HERE/bench.py" --arm container --agents 96 --cores 32 --profile test --classes 150 \
  --fixture /tmp/bench-test --run-dir "$OUT/p5-container" --out "$R" --warmup 20 --window 45 \
  --no-prewarm >>"$LOG" 2>&1 || say "  !! container baseline FAILED"
trim_bundle "$OUT/p5-container"

# ---- P6: warm-collapse + churn + open-loop ----
say "P6: handing off to phase6.sh"
bash "$HERE/phase6.sh" || say "  phase6 reported failure"

# ---- P7: detailed disk study (LAST, on optimized io2) ----
say "P7: handing off to phase7_disk.sh"
bash "$HERE/phase7_disk.sh" || say "  phase7 reported failure"

clean_room
say "=== REMAINING CAMPAIGN DONE — free=$(disk_free_gb)GB ==="
