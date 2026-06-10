#!/usr/bin/env bash
# Deliverable #1 full campaign — runs the whole matrix the user asked for ("Both" modes x all
# three profiles), ordered so the HEADLINE (capped collapse) is captured first and the heavier
# add-ons follow. Designed to run in the background; appends per-phase results.jsonl + a progress
# log. Each cell leaves a full observability bundle.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(dirname "$HERE")"
OUT="$REPO/bench/out/campaign"
LOG="$OUT/campaign.log"
mkdir -p "$OUT"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

clean_room() {
  pkill -f GradleDaemon 2>/dev/null || true
  sudo docker ps -aq --filter "name=arxbench_" | xargs -r sudo docker rm -f >/dev/null 2>&1 || true
  sudo systemctl stop arxbench.slice 2>/dev/null || true
  sync; sleep 2
}

cell() {  # arm cores agents mem_gb profile classes warmup window results
  local arm=$1 cores=$2 agents=$3 mem=$4 prof=$5 classes=$6 wu=$7 win=$8 res=$9
  clean_room
  say "  cell arm=$arm cores=$cores agents=$agents mem=${mem}GB prof=$prof"
  python3 "$HERE/bench.py" --arm "$arm" --agents "$agents" --cores "$cores" --mem-gb "$mem" \
    --per-build-mb 1024 --profile "$prof" --classes "$classes" --warmup "$wu" --window "$win" \
    --fixture "/tmp/bench-$prof" --run-dir "$OUT/$prof-$([ "$mem" = 0 ] && echo unc || echo cap)-c$cores/${arm}-a${agents}" \
    --out "$res" >>"$LOG" 2>&1 || say "    !! cell FAILED"
}

gen() { python3 "$HERE/fixtures/gen_fixture.py" "/tmp/bench-$1" --classes "$2" >>"$LOG" 2>&1; }

say "=== CAMPAIGN START — box: $(nproc) cores / $(awk '/MemTotal/{printf "%.0fGB",$2/1048576}' /proc/meminfo) ==="

# ---- PHASE 1: capped collapse headline — profile=test, modeled 16-core/24GB box ----
say "PHASE 1: capped collapse (test, cores=16, mem=24GB)"
gen test 150
R="$OUT/p1-test-cap.jsonl"; : >"$R"
for A in 1 2 4 8 16 24 32 40; do
  for ARM in warm container; do cell "$ARM" 16 "$A" 24 test 150 20 45 "$R"; done
done
python3 "$HERE/analyze.py" "$R" | tee -a "$LOG"

# ---- PHASE 2: uncapped density ceiling vs cores — profile=test ----
say "PHASE 2: uncapped cores sweep (test, cores=4/16/32)"
R="$OUT/p2-test-unc.jsonl"; : >"$R"
for C in 4 16 32; do
  for A in 1 2 4 8 16 32 48; do
    for ARM in warm container; do cell "$ARM" "$C" "$A" 0 test 150 20 45 "$R"; done
  done
done
python3 "$HERE/analyze.py" "$R" | tee -a "$LOG"

# ---- PHASE 3: capped collapse — profile=coverage ----
say "PHASE 3: capped collapse (coverage, cores=16, mem=24GB)"
gen coverage 150
R="$OUT/p3-coverage-cap.jsonl"; : >"$R"
for A in 1 2 4 8 16 24 32; do
  for ARM in warm container; do cell "$ARM" 16 "$A" 24 coverage 150 20 50 "$R"; done
done
python3 "$HERE/analyze.py" "$R" | tee -a "$LOG"

# ---- PHASE 4: capped collapse — profile=mutation (smaller fixture; PIT is heavy) ----
say "PHASE 4: capped collapse (mutation, cores=16, mem=24GB, 40 classes)"
gen mutation 40
R="$OUT/p4-mutation-cap.jsonl"; : >"$R"
for A in 1 2 4 8 16 24; do
  for ARM in warm container; do cell "$ARM" 16 "$A" 24 mutation 40 35 90 "$R"; done
done
python3 "$HERE/analyze.py" "$R" | tee -a "$LOG"

clean_room
say "=== CAMPAIGN DONE -> $OUT ==="
